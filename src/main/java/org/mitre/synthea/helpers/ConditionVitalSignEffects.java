package org.mitre.synthea.helpers;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.BiometricsConfig;
import org.mitre.synthea.world.concepts.HealthRecord;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.Entry;

/**
 * How much a person's conditions raise (or lower) one vital sign, such as heart rate.
 *
 * <p>Each effect lists condition codes and the offset it adds to the vital sign while one of
 * them is active, from the time the condition is recorded in the health record. After the
 * condition ends the offset decays exponentially (recovery_hours). An acute effect (one with
 * max_days) also ends when the encounter its condition was recorded in ends, and at the latest
 * max_days after onset, because Synthea leaves some acute conditions, such as sepsis, active
 * forever. Several active effects combine sub-additively: sorted by size, the largest counts
 * fully, the next times combination_weight, the next times its square, and so on. The result
 * is bounded to the vital sign's bounds.</p>
 *
 * <p>The offset at a time depends only on the person's health record up to that time, so a
 * value does not change with when or how often it is asked for. Parameters are in
 * biometrics.yml (vital_sign_condition_effects).</p>
 */
public class ConditionVitalSignEffects implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final double MS_PER_HOUR = 3_600_000.0;
  private static final double MS_PER_DAY = 24 * MS_PER_HOUR;

  private final double recoveryMs;
  private final double combinationWeight;
  private final double minimumValue;
  private final double maximumValue;
  /** Condition code to the index of its effect. */
  private final Map<String, Integer> effectOfCode;
  private final String[] names;
  /** Offset of each effect while its condition is active. */
  private final double[] magnitudes;
  /** Longest an acute effect lasts after onset; infinite for chronic effects. */
  private final double[] maxDurationMs;

  /**
   * Load the effects on one vital sign from biometrics.yml.
   *
   * @param vitalSign the vital sign's key in the effects table, e.g. "heart_rate"
   * @return the effects; effects that do not list this vital sign are left out
   */
  @SuppressWarnings("unchecked")
  public static ConditionVitalSignEffects load(String vitalSign) {
    Object config = BiometricsConfig.get("vital_sign_condition_effects");
    if (!(config instanceof Map)) {
      throw new IllegalStateException(
          "biometrics.yml has no parameter map at vital_sign_condition_effects");
    }
    return new ConditionVitalSignEffects((Map<String, Object>) config, vitalSign);
  }

  /**
   * Create the effects on one vital sign.
   *
   * @param config parameters in the layout of biometrics.yml vital_sign_condition_effects
   * @param vitalSign the vital sign's key in the bounds and in each effect, e.g. "heart_rate"
   */
  @SuppressWarnings("unchecked")
  public ConditionVitalSignEffects(Map<String, Object> config, String vitalSign) {
    double recoveryHours = number(config, "recovery_hours");
    checkArgument(recoveryHours >= 0, "recovery_hours must not be negative");
    this.recoveryMs = recoveryHours * MS_PER_HOUR;
    this.combinationWeight = number(config, "combination_weight");
    checkArgument(combinationWeight >= 0 && combinationWeight <= 1,
        "combination_weight must be in [0, 1]");

    Map<String, Object> bounds = (Map<String, Object>) config.get("bounds");
    checkArgument(bounds != null && bounds.get(vitalSign) instanceof List,
        "missing bounds for " + vitalSign);
    List<Object> range = (List<Object>) bounds.get(vitalSign);
    checkArgument(range.size() == 2, "bounds for " + vitalSign + " must be [min, max]");
    this.minimumValue = ((Number) range.get(0)).doubleValue();
    this.maximumValue = ((Number) range.get(1)).doubleValue();
    checkArgument(minimumValue <= maximumValue, "bounds for " + vitalSign + " are reversed");

    Map<String, Object> effects = (Map<String, Object>) config.get("effects");
    checkArgument(effects != null, "missing effects");
    this.effectOfCode = new HashMap<>();
    List<String> names = new ArrayList<>();
    List<Double> magnitudes = new ArrayList<>();
    List<Double> maxDurations = new ArrayList<>();
    for (Map.Entry<String, Object> e : effects.entrySet()) {
      Map<String, Object> effect = (Map<String, Object>) e.getValue();
      Object magnitude = effect.get(vitalSign);
      if (magnitude == null || ((Number) magnitude).doubleValue() == 0) {
        continue;
      }
      double maxDuration = Double.POSITIVE_INFINITY;
      if (effect.containsKey("max_days")) {
        double maxDays = number(effect, "max_days");
        checkArgument(maxDays > 0, "max_days of " + e.getKey() + " must be positive");
        maxDuration = maxDays * MS_PER_DAY;
      }
      Object codes = effect.get("codes");
      checkArgument(codes instanceof List && !((List<Object>) codes).isEmpty(),
          "effect " + e.getKey() + " lists no codes");
      int index = names.size();
      for (Object code : (List<Object>) codes) {
        Integer previous = effectOfCode.put(String.valueOf(code), index);
        checkArgument(previous == null || previous == index,
            "code " + code + " is in more than one effect");
      }
      names.add(e.getKey());
      magnitudes.add(((Number) magnitude).doubleValue());
      maxDurations.add(maxDuration);
    }
    this.names = names.toArray(new String[0]);
    this.magnitudes = magnitudes.stream().mapToDouble(Double::doubleValue).toArray();
    this.maxDurationMs = maxDurations.stream().mapToDouble(Double::doubleValue).toArray();
  }

  /**
   * Add the offset of the person's conditions to a value and bound the result.
   *
   * @param person the person
   * @param time the time of the value
   * @param value the vital sign without condition effects
   * @return the vital sign with condition effects
   */
  public double apply(Person person, long time, double value) {
    return Math.min(maximumValue, Math.max(minimumValue, value + offset(person, time)));
  }

  /**
   * The combined offset of the person's conditions at a time, before bounding.
   *
   * @param person the person
   * @param time the time
   * @return the offset
   */
  public double offset(Person person, long time) {
    double[] activation = null;
    for (HealthRecord record : records(person)) {
      for (Encounter encounter : record.encounters) {
        for (Entry condition : encounter.conditions) {
          Integer effect = effectOfCode.get(condition.type);
          if (effect == null || condition.start > time) {
            continue;
          }
          double a = activation(effect, encounter, condition, time);
          if (a > 0) {
            if (activation == null) {
              activation = new double[magnitudes.length];
            }
            activation[effect] = Math.max(activation[effect], a);
          }
        }
      }
    }
    if (activation == null) {
      return 0;
    }
    double[] contributions = new double[magnitudes.length];
    for (int i = 0; i < magnitudes.length; i++) {
      contributions[i] = magnitudes[i] * activation[i];
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

  /** 1 while the condition is active, then decaying towards 0. */
  private double activation(int effect, Encounter encounter, Entry condition, long time) {
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
      return 1;
    }
    return recoveryMs > 0 ? Math.exp(-(time - end) / recoveryMs) : 0;
  }

  /** The person's distinct health records (more than one with split records or loss of care). */
  private static List<HealthRecord> records(Person person) {
    List<HealthRecord> records = new ArrayList<>(3);
    addRecord(records, person.defaultRecord);
    addRecord(records, person.record);
    addRecord(records, person.lossOfCareRecord);
    if (person.records != null) {
      for (HealthRecord record : person.records.values()) {
        addRecord(records, record);
      }
    }
    return records;
  }

  private static void addRecord(List<HealthRecord> records, HealthRecord record) {
    if (record != null && !records.contains(record)) {
      records.add(record);
    }
  }

  private static double number(Map<String, Object> config, String key) {
    Object value = config.get(key);
    if (!(value instanceof Number)) {
      throw new IllegalStateException("missing numeric parameter '" + key + "'");
    }
    return ((Number) value).doubleValue();
  }

  /** Lower bound of the vital sign with condition effects. */
  public double getMinimumValue() {
    return minimumValue;
  }

  /** Upper bound of the vital sign with condition effects. */
  public double getMaximumValue() {
    return maximumValue;
  }

  @Override
  public String toString() {
    return "ConditionVitalSignEffects {effects=" + Arrays.toString(names) + ", magnitudes="
        + Arrays.toString(magnitudes) + "}";
  }
}
