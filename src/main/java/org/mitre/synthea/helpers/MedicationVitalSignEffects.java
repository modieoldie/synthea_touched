package org.mitre.synthea.helpers;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.BiometricsConfig;
import org.mitre.synthea.world.concepts.HealthRecord.Medication;

/**
 * How a person's medications change one vital sign: a shift of the resting value and a blunting of
 * the rise from conditions. Parameters are in biometrics.yml (vital_sign_medication_effects) and
 * vital_sign_drug_classes.csv.
 *
 * <p>Whether a person takes a class, and how strongly they respond, are pure functions of the
 * person's seed and the class, so they hold for every vital sign and reading.</p>
 */
public class MedicationVitalSignEffects implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final double MS_PER_HOUR = 3_600_000.0;
  private static final long SALT = 0x6d656473L;
  private static Map<String, String> drugClasses;

  private final double combinationWeight;
  /** Drug code to the index of its class, for classes that act on this vital sign. */
  private final Map<String, Integer> classOfCode;
  private final String[] names;
  private final long[] salts;
  private final double[] shifts;
  private final double[] bluntings;
  private final double[] adherences;
  private final double[] responseLows;
  private final double[] responseHighs;
  private final double[] washoutMs;
  /** How long a one-off dose acts; 0 to ignore one-off doses. */
  private final double[] administrationMs;

  /**
   * The drug classes of vital_sign_drug_classes.csv.
   *
   * @return drug code (RxNorm) to class name
   */
  public static synchronized Map<String, String> drugClasses() {
    if (drugClasses == null) {
      try {
        Map<String, String> classes = new HashMap<>();
        for (Map<String, String> line : SimpleCSV.parse(
            Utilities.readResource("vital_sign_drug_classes.csv"))) {
          String previous = classes.put(line.get("RxNorm"), line.get("Class"));
          checkArgument(previous == null, "drug code " + line.get("RxNorm") + " is listed twice");
        }
        drugClasses = Collections.unmodifiableMap(classes);
      } catch (Exception e) {
        throw new IllegalStateException("cannot read vital_sign_drug_classes.csv", e);
      }
    }
    return drugClasses;
  }

  /**
   * Load the effects on one vital sign from biometrics.yml.
   *
   * @param vitalSign the vital sign's key in each class, e.g. "heart_rate"
   * @return the effects; classes that do not list this vital sign are left out
   */
  @SuppressWarnings("unchecked")
  public static MedicationVitalSignEffects load(String vitalSign) {
    Object config = BiometricsConfig.get("vital_sign_medication_effects");
    if (!(config instanceof Map)) {
      throw new IllegalStateException(
          "biometrics.yml has no parameter map at vital_sign_medication_effects");
    }
    return new MedicationVitalSignEffects((Map<String, Object>) config, vitalSign, drugClasses());
  }

  /**
   * Create the effects on one vital sign.
   *
   * @param config parameters in the layout of biometrics.yml vital_sign_medication_effects
   * @param vitalSign the vital sign's key in each class, e.g. "heart_rate"
   * @param drugClasses drug code to class name
   */
  @SuppressWarnings("unchecked")
  public MedicationVitalSignEffects(Map<String, Object> config, String vitalSign,
      Map<String, String> drugClasses) {
    this.combinationWeight = number(config, "combination_weight");
    checkArgument(combinationWeight >= 0 && combinationWeight <= 1,
        "combination_weight must be in [0, 1]");
    Map<String, Object> classes = (Map<String, Object>) config.get("classes");
    checkArgument(classes != null, "missing classes");

    Map<String, Integer> indexOfClass = new LinkedHashMap<>();
    List<double[]> rows = new ArrayList<>();
    for (Map.Entry<String, Object> e : classes.entrySet()) {
      String name = e.getKey();
      Map<String, Object> drug = (Map<String, Object>) e.getValue();
      checkArgument(drugClasses.containsValue(name),
          "drug class " + name + " has no codes in vital_sign_drug_classes.csv");
      Object effect = drug.get(vitalSign);
      if (effect == null) {
        continue;
      }
      checkArgument(effect instanceof Map, name + "." + vitalSign + " must be a map");
      Map<String, Object> onVital = (Map<String, Object>) effect;
      double shift = number(onVital, "shift");
      double blunting = onVital.containsKey("blunting") ? number(onVital, "blunting") : 0;
      checkArgument(blunting >= 0 && blunting <= 1, "blunting of " + name + " must be in [0, 1]");
      double adherence = number(drug, "adherence");
      checkArgument(adherence >= 0 && adherence <= 1,
          "adherence of " + name + " must be in [0, 1]");
      Object response = drug.get("response");
      checkArgument(response instanceof List && ((List<Object>) response).size() == 2,
          "response of " + name + " must be [low, high]");
      double low = ((Number) ((List<Object>) response).get(0)).doubleValue();
      double high = ((Number) ((List<Object>) response).get(1)).doubleValue();
      checkArgument(0 <= low && low <= high, "response of " + name + " must be 0 <= low <= high");
      double washoutHours = number(drug, "washout_hours");
      checkArgument(washoutHours >= 0, "washout_hours of " + name + " must not be negative");
      double administrationHours = drug.containsKey("administration_hours")
          ? number(drug, "administration_hours") : 0;
      checkArgument(administrationHours >= 0,
          "administration_hours of " + name + " must not be negative");
      indexOfClass.put(name, rows.size());
      rows.add(new double[] {shift, blunting, adherence, low, high,
          washoutHours * MS_PER_HOUR, administrationHours * MS_PER_HOUR});
    }

    int n = rows.size();
    this.names = indexOfClass.keySet().toArray(new String[0]);
    this.salts = new long[n];
    this.shifts = new double[n];
    this.bluntings = new double[n];
    this.adherences = new double[n];
    this.responseLows = new double[n];
    this.responseHighs = new double[n];
    this.washoutMs = new double[n];
    this.administrationMs = new double[n];
    for (int i = 0; i < n; i++) {
      double[] row = rows.get(i);
      salts[i] = names[i].hashCode();
      shifts[i] = row[0];
      bluntings[i] = row[1];
      adherences[i] = row[2];
      responseLows[i] = row[3];
      responseHighs[i] = row[4];
      washoutMs[i] = row[5];
      administrationMs[i] = row[6];
    }
    this.classOfCode = new HashMap<>();
    for (Map.Entry<String, String> e : drugClasses.entrySet()) {
      Integer index = indexOfClass.get(e.getValue());
      if (index != null) {
        classOfCode.put(e.getKey(), index);
      }
    }
  }

  public boolean isEmpty() {
    return names.length == 0;
  }

  /** Codes of the drugs that act on this vital sign. */
  Set<String> codes() {
    return classOfCode.keySet();
  }

  /**
   * Apply the person's medications to a value.
   *
   * @param value the vital sign without conditions or medications
   * @param rise the combined offset of the person's conditions
   * @return the vital sign with conditions and medications, not bounded
   */
  public double apply(Person person, long time, double value, double rise) {
    VitalSignRecordIndex index = new VitalSignRecordIndex(Collections.emptySet(), codes());
    index.update(person);
    return apply(person, index, time, value, rise);
  }

  double apply(Person person, VitalSignRecordIndex index, long time, double value, double rise) {
    double[] activation = activations(index, time);
    if (activation == null) {
      return value + rise;
    }
    long seed = StatelessRandom.mix(person.getSeed() ^ StatelessRandom.mix(SALT));
    double[] contributions = new double[names.length];
    double kept = 1;
    for (int i = 0; i < names.length; i++) {
      if (activation[i] == 0 || !takes(seed, i)) {
        continue;
      }
      double strength = activation[i] * response(seed, i);
      contributions[i] = shifts[i] * strength;
      kept *= 1 - Math.min(1, bluntings[i] * strength);
    }
    return value + combine(contributions) + (rise > 0 ? kept * rise : rise);
  }

  /** Each class's activation: 1 while taken, then wearing off; null when none is active. */
  private double[] activations(VitalSignRecordIndex index, long time) {
    double[] activation = null;
    for (VitalSignRecordIndex.Item item : index.medications()) {
      Integer drug = classOfCode.get(item.entry.type);
      if (drug == null || item.entry.start > time) {
        continue;
      }
      Medication medication = (Medication) item.entry;
      double end;
      if (medication.administration) {
        if (administrationMs[drug] == 0) {
          continue;
        }
        end = medication.start + administrationMs[drug];
      } else {
        // 0 means "not stopped"; times before 1970 are negative
        end = medication.stop != 0L ? medication.stop : Double.POSITIVE_INFINITY;
      }
      end = Math.max(end, medication.start);
      double a = time < end ? 1
          : washoutMs[drug] > 0 ? Math.exp(-(time - end) / washoutMs[drug]) : 0;
      if (a > 0) {
        if (activation == null) {
          activation = new double[names.length];
        }
        activation[drug] = Math.max(activation[drug], a);
      }
    }
    return activation;
  }

  /** Sub-additive: sorted by size, the largest counts fully, the next times the weight, ... */
  private double combine(double[] contributions) {
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

  /** Whether the person takes drugs of this class when prescribed. */
  private boolean takes(long seed, int drug) {
    return StatelessRandom.uniform(seed, salts[drug], 0) < adherences[drug];
  }

  /** The person's response to this class, as a factor on its effect. */
  private double response(long seed, int drug) {
    return responseLows[drug] + (responseHighs[drug] - responseLows[drug])
        * StatelessRandom.uniform(seed, salts[drug], 1);
  }

  /**
   * The person's factor on a class's effect: 0 if the class does not act on this vital sign or the
   * person does not take it.
   */
  public double personalFactor(Person person, String drugClass) {
    int drug = Arrays.asList(names).indexOf(drugClass);
    if (drug < 0) {
      return 0;
    }
    long seed = StatelessRandom.mix(person.getSeed() ^ StatelessRandom.mix(SALT));
    return takes(seed, drug) ? response(seed, drug) : 0;
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
    return "MedicationVitalSignEffects {classes=" + Arrays.toString(names) + ", shifts="
        + Arrays.toString(shifts) + ", bluntings=" + Arrays.toString(bluntings) + "}";
  }
}
