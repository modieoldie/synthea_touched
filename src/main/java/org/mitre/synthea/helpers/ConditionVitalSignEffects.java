package org.mitre.synthea.helpers;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.BiometricsConfig;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.Entry;

/**
 * How much a person's conditions raise or lower one vital sign. Parameters are in biometrics.yml
 * (vital_sign_condition_effects).
 *
 * <p>An offset depends only on the health record up to the time asked for. The course is timed
 * from onset and treatment, never discharge, so a reading does not reveal when a stay ends.</p>
 */
public class ConditionVitalSignEffects implements Serializable {
  /** Kept at 1 so snapshots holding older effects still load. */
  private static final long serialVersionUID = 1L;
  private static final double MS_PER_HOUR = 3_600_000.0;
  private static final double MS_PER_DAY = 24 * MS_PER_HOUR;
  private static final long SEVERITY_SALT = 0x73657665L;

  private final double recoveryMs;
  private final double combinationWeight;
  /** Log-SD of each episode's severity; 0 for none. */
  private final double severitySd;
  private final double untreatedFactor;
  private final double treatmentWindowMs;
  private final Map<String, Integer> effectOfCode;
  private final String[] names;
  /** Offset of each effect while its condition is active. */
  private final double[] magnitudes;
  /** Longest an acute effect lasts after onset; infinite for chronic effects. */
  private final double[] maxDurationMs;
  /** Time constant of an acute effect's resolution; 0 for a flat effect. */
  private final double[] resolutionMs;
  /** Share of an acute effect left once resolved. */
  private final double[] residuals;
  /** Drug codes that treat each effect; null when its course starts at onset. */
  private final Set<String>[] treatmentCodes;
  private final long[] severitySalts;
  /** Share of each effect's episodes that are reversed (null in old snapshots). */
  private final double[] reversalShares;
  /** Offset of a reversed episode (null in old snapshots). */
  private final double[] reversalOffsets;

  /**
   * Load the effects on one vital sign from biometrics.yml, with course and severity.
   *
   * @param vitalSign the vital sign's key in the effects table, e.g. "heart_rate"
   * @return the effects; effects that do not list this vital sign are left out
   */
  public static ConditionVitalSignEffects load(String vitalSign) {
    return load(vitalSign, true, true);
  }

  /**
   * Load the effects on one vital sign from biometrics.yml.
   *
   * @param vitalSign the vital sign's key in the effects table, e.g. "heart_rate"
   * @param course whether acute effects resolve during the condition (resolution_hours)
   * @param severity whether each episode draws a severity factor (severity_sd)
   * @return the effects; effects that do not list this vital sign are left out
   */
  @SuppressWarnings("unchecked")
  public static ConditionVitalSignEffects load(String vitalSign, boolean course,
      boolean severity) {
    Object config = BiometricsConfig.get("vital_sign_condition_effects");
    if (!(config instanceof Map)) {
      throw new IllegalStateException(
          "biometrics.yml has no parameter map at vital_sign_condition_effects");
    }
    return new ConditionVitalSignEffects((Map<String, Object>) config, vitalSign, course,
        severity, MedicationVitalSignEffects.drugClasses());
  }

  /**
   * Create the effects on one vital sign, with course and severity if the parameters give them.
   *
   * @param config parameters in the layout of biometrics.yml vital_sign_condition_effects
   * @param vitalSign the vital sign's key in each effect, e.g. "heart_rate"
   */
  public ConditionVitalSignEffects(Map<String, Object> config, String vitalSign) {
    this(config, vitalSign, true, true, MedicationVitalSignEffects.drugClasses());
  }

  /**
   * Create the effects on one vital sign.
   *
   * @param config parameters in the layout of biometrics.yml vital_sign_condition_effects
   * @param vitalSign the vital sign's key in each effect, e.g. "heart_rate"
   * @param course whether acute effects resolve during the condition (resolution_hours)
   * @param severity whether each episode draws a severity factor (severity_sd)
   * @param drugClasses drug code to class name, for treated_by
   */
  @SuppressWarnings("unchecked")
  public ConditionVitalSignEffects(Map<String, Object> config, String vitalSign, boolean course,
      boolean severity, Map<String, String> drugClasses) {
    double recoveryHours = number(config, "recovery_hours");
    checkArgument(recoveryHours >= 0, "recovery_hours must not be negative");
    this.recoveryMs = recoveryHours * MS_PER_HOUR;
    this.combinationWeight = number(config, "combination_weight");
    checkArgument(combinationWeight >= 0 && combinationWeight <= 1,
        "combination_weight must be in [0, 1]");
    double sd = config.containsKey("severity_sd") ? number(config, "severity_sd") : 0;
    checkArgument(sd >= 0, "severity_sd must not be negative");
    this.severitySd = severity ? sd : 0;
    this.untreatedFactor = config.containsKey("untreated_factor")
        ? number(config, "untreated_factor") : 1;
    checkArgument(untreatedFactor >= 1, "untreated_factor must be at least 1");
    double windowHours = config.containsKey("treatment_window_hours")
        ? number(config, "treatment_window_hours") : 0;
    checkArgument(windowHours >= 0, "treatment_window_hours must not be negative");
    this.treatmentWindowMs = windowHours * MS_PER_HOUR;

    Map<String, Object> effects = (Map<String, Object>) config.get("effects");
    checkArgument(effects != null, "missing effects");
    this.effectOfCode = new HashMap<>();
    List<String> names = new ArrayList<>();
    List<double[]> rows = new ArrayList<>();
    List<Set<String>> treatments = new ArrayList<>();
    for (Map.Entry<String, Object> e : effects.entrySet()) {
      String name = e.getKey();
      Map<String, Object> effect = (Map<String, Object>) e.getValue();
      Object magnitude = effect.get(vitalSign);
      if (magnitude == null || ((Number) magnitude).doubleValue() == 0) {
        continue;
      }
      double maxDuration = Double.POSITIVE_INFINITY;
      if (effect.containsKey("max_days")) {
        double maxDays = number(effect, "max_days");
        checkArgument(maxDays > 0, "max_days of " + name + " must be positive");
        maxDuration = maxDays * MS_PER_DAY;
      }
      double resolutionHours = resolutionHours(effect, name, vitalSign);
      checkArgument(resolutionHours == 0 || maxDuration < Double.POSITIVE_INFINITY,
          "resolution_hours of " + name + " needs max_days: only acute effects resolve");
      double residual = effect.containsKey("residual") ? number(effect, "residual") : 0;
      checkArgument(residual >= 0 && residual < 1, "residual of " + name + " must be in [0, 1)");
      Set<String> treatment = null;
      if (effect.containsKey("treated_by")) {
        checkArgument(resolutionHours > 0, "treated_by of " + name + " needs resolution_hours");
        checkArgument(effect.get("treated_by") instanceof List,
            "treated_by of " + name + " must be a list of drug classes");
        treatment = new HashSet<>();
        for (Object drugClass : (List<Object>) effect.get("treated_by")) {
          checkArgument(drugClasses.containsValue(String.valueOf(drugClass)),
              "treated_by of " + name + ": no codes of drug class " + drugClass
              + " in vital_sign_drug_classes.csv");
          for (Map.Entry<String, String> drug : drugClasses.entrySet()) {
            if (drug.getValue().equals(String.valueOf(drugClass))) {
              treatment.add(drug.getKey());
            }
          }
        }
      }
      Object codes = effect.get("codes");
      checkArgument(codes instanceof List && !((List<Object>) codes).isEmpty(),
          "effect " + name + " lists no codes");
      int index = names.size();
      for (Object code : (List<Object>) codes) {
        Integer previous = effectOfCode.put(String.valueOf(code), index);
        checkArgument(previous == null || previous == index,
            "code " + code + " is in more than one effect");
      }
      double[] reversal = reversal(effect, name, vitalSign);
      names.add(name);
      rows.add(new double[] {((Number) magnitude).doubleValue(), maxDuration,
          course ? resolutionHours * MS_PER_HOUR : 0, course ? residual : 0,
          reversal[0], reversal[1]});
      treatments.add(course ? treatment : null);
    }
    int n = names.size();
    this.names = names.toArray(new String[0]);
    this.magnitudes = new double[n];
    this.maxDurationMs = new double[n];
    this.resolutionMs = new double[n];
    this.residuals = new double[n];
    this.treatmentCodes = treatments.toArray(new Set[0]);
    this.severitySalts = new long[n];
    this.reversalShares = new double[n];
    this.reversalOffsets = new double[n];
    for (int i = 0; i < n; i++) {
      magnitudes[i] = rows.get(i)[0];
      maxDurationMs[i] = rows.get(i)[1];
      resolutionMs[i] = rows.get(i)[2];
      residuals[i] = rows.get(i)[3];
      reversalShares[i] = rows.get(i)[4];
      reversalOffsets[i] = rows.get(i)[5];
      severitySalts[i] = this.names[i].hashCode();
    }
  }

  /** {share, offset} of an effect's reversed episodes for one vital sign; {0, 0} for none. */
  @SuppressWarnings("unchecked")
  private static double[] reversal(Map<String, Object> effect, String name, String vitalSign) {
    Object all = effect.get("reversal");
    Object own = all instanceof Map ? ((Map<String, Object>) all).get(vitalSign) : null;
    if (!(own instanceof Map)) {
      return new double[] {0, 0};
    }
    Map<String, Object> reversal = (Map<String, Object>) own;
    double share = number(reversal, "share");
    checkArgument(share >= 0 && share <= 1, "reversal share of " + name + " must be in [0, 1]");
    return new double[] {share, number(reversal, "offset")};
  }

  /** resolution_hours of an effect for one vital sign: a number, or a map by vital sign. */
  @SuppressWarnings("unchecked")
  private static double resolutionHours(Map<String, Object> effect, String name,
      String vitalSign) {
    Object value = effect.get("resolution_hours");
    if (value instanceof Map) {
      value = ((Map<String, Object>) value).get(vitalSign);
    }
    if (value == null) {
      return 0;
    }
    checkArgument(value instanceof Number && ((Number) value).doubleValue() > 0,
        "resolution_hours of " + name + " must be positive");
    return ((Number) value).doubleValue();
  }

  Set<String> conditionCodes() {
    return effectOfCode.keySet();
  }

  Set<String> treatmentCodes() {
    Set<String> codes = new HashSet<>();
    for (Set<String> treatment : treatmentCodes) {
      if (treatment != null) {
        codes.addAll(treatment);
      }
    }
    return codes;
  }

  /** The combined offset of the person's conditions at a time, before bounding. */
  public double offset(Person person, long time) {
    VitalSignRecordIndex index = new VitalSignRecordIndex(conditionCodes(), treatmentCodes());
    index.update(person);
    return offset(person, index, time);
  }

  double offset(Person person, VitalSignRecordIndex index, long time) {
    double[] strength = null;
    double[] magnitude = null;
    long seed = severitySd > 0 || hasReversal()
        ? StatelessRandom.mix(person.getSeed() ^ StatelessRandom.mix(SEVERITY_SALT)) : 0;
    for (VitalSignRecordIndex.Item item : index.conditions()) {
      Entry condition = item.entry;
      Integer effect = effectOfCode.get(condition.type);
      if (effect == null || condition.start > time) {
        continue;
      }
      double a = activation(effect, item.encounter, condition, time, index);
      if (a > 0) {
        if (strength == null) {
          strength = new double[magnitudes.length];
          magnitude = magnitudes.clone();
        }
        double s = a * severity(seed, effect, condition.start);
        if (s > strength[effect]) {
          // the strongest episode of the effect decides its direction too
          strength[effect] = s;
          magnitude[effect] = episodeMagnitude(seed, effect, condition.start);
        }
      }
    }
    if (strength == null) {
      return 0;
    }
    double[] contributions = new double[magnitudes.length];
    for (int i = 0; i < magnitudes.length; i++) {
      contributions[i] = magnitude[i] * strength[i];
    }
    // largest effect first, whatever its sign
    double[] sorted = Arrays.stream(contributions).boxed()
        .sorted((a, b) -> Double.compare(Math.abs(b), Math.abs(a)))
        .mapToDouble(Double::doubleValue).toArray();
    double total = 0;
    double weight = 1;
    for (double c : sorted) {
      total += weight * c;
      weight *= combinationWeight;
    }
    return total;
  }

  /** The course while the condition is active, then decaying towards 0 after it ends. */
  private double activation(int effect, Encounter encounter, Entry condition, long time,
      VitalSignRecordIndex index) {
    // 0 means "not ended"; times before 1970 are negative
    double end = condition.stop != 0L ? condition.stop : Double.POSITIVE_INFINITY;
    if (maxDurationMs[effect] < Double.POSITIVE_INFINITY) {
      // an encounter's stop is provisional until it has ended
      if (encounter.ended && encounter.stop >= condition.start) {
        end = Math.min(end, encounter.stop);
      }
      end = Math.min(end, condition.start + maxDurationMs[effect]);
    }
    end = Math.max(end, condition.start);
    if (time < end) {
      return level(effect, condition.start, time, index);
    }
    if (recoveryMs == 0) {
      return 0;
    }
    return level(effect, condition.start, end, index) * Math.exp(-(time - end) / recoveryMs);
  }

  /** Share of the effect left at a time during the condition: 1 at onset, then resolving. */
  private double level(int effect, long onset, double time, VitalSignRecordIndex index) {
    double tau = resolutionMs[effect];
    if (tau == 0) {
      return 1;
    }
    double treated = treatmentCodes[effect] == null ? onset
        : treatmentStart(effect, onset, time, index);
    double remaining;
    if (treated > time) {
      remaining = Math.exp(-(time - onset) / (tau * untreatedFactor));
    } else {
      remaining = Math.exp(-(treated - onset) / (tau * untreatedFactor) - (time - treated) / tau);
    }
    return residuals[effect] + (1 - residuals[effect]) * remaining;
  }

  /** When treatment of an episode began, no earlier than onset; infinite if not yet. */
  private double treatmentStart(int effect, long onset, double time,
      VitalSignRecordIndex index) {
    double first = Double.POSITIVE_INFINITY;
    for (VitalSignRecordIndex.Item item : index.medications()) {
      long start = item.entry.start;
      if (start <= time && start >= onset - treatmentWindowMs && start < first
          && treatmentCodes[effect].contains(item.entry.type)) {
        first = start;
      }
    }
    return Math.max(first, onset);
  }

  private boolean hasReversal() {
    if (reversalShares != null) {
      for (double share : reversalShares) {
        if (share > 0) {
          return true;
        }
      }
    }
    return false;
  }

  /** The offset of an episode at full strength: reversed for a share of the episodes. */
  private double episodeMagnitude(long seed, int effect, long onset) {
    if (reversalShares == null || reversalShares[effect] == 0) {
      return magnitudes[effect];
    }
    // salted differently from the severity draw of the same episode
    double u = StatelessRandom.uniform(seed, ~severitySalts[effect], onset);
    return u < reversalShares[effect] ? reversalOffsets[effect] : magnitudes[effect];
  }

  /**
   * Whether one episode of an effect is reversed.
   *
   * @param effect the effect's name in biometrics.yml
   * @param onset the start of the episode's condition
   */
  public boolean reversed(Person person, String effect, long onset) {
    int index = Arrays.asList(names).indexOf(effect);
    checkArgument(index >= 0, "no effect " + effect + " on this vital sign");
    long seed = StatelessRandom.mix(person.getSeed() ^ StatelessRandom.mix(SEVERITY_SALT));
    return episodeMagnitude(seed, index, onset) != magnitudes[index];
  }

  /** The episode's severity factor (1 without severity). */
  private double severity(long seed, int effect, long onset) {
    if (severitySd == 0) {
      return 1;
    }
    double z = StatelessRandom.gaussian(seed, severitySalts[effect], onset);
    return Math.exp(severitySd * z - severitySd * severitySd / 2);
  }

  /**
   * The severity factor of one episode of an effect (1 without severity).
   *
   * @param effect the effect's name in biometrics.yml
   * @param onset the start of the episode's condition
   */
  public double severity(Person person, String effect, long onset) {
    int index = Arrays.asList(names).indexOf(effect);
    checkArgument(index >= 0, "no effect " + effect + " on this vital sign");
    long seed = StatelessRandom.mix(person.getSeed() ^ StatelessRandom.mix(SEVERITY_SALT));
    return severity(seed, index, onset);
  }

  /** The effects' names, for tests and diagnostics. */
  List<String> names() {
    return Collections.unmodifiableList(Arrays.asList(names));
  }

  private static double number(Map<String, Object> config, String key) {
    Object value = config.get(key);
    if (!(value instanceof Number)) {
      throw new IllegalStateException("missing numeric parameter '" + key + "'");
    }
    return ((Number) value).doubleValue();
  }

  @Override
  public String toString() {
    return "ConditionVitalSignEffects {effects=" + Arrays.toString(names) + ", magnitudes="
        + Arrays.toString(magnitudes) + ", severitySd=" + severitySd + "}";
  }
}
