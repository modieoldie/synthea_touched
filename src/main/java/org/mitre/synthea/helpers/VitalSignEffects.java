package org.mitre.synthea.helpers;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.BiometricsConfig;

/**
 * Applies a person's conditions, then medications, to one vital sign and bounds the result
 * (vital_sign_effect_bounds in biometrics.yml). Either part may be absent.
 */
public class VitalSignEffects implements Serializable {
  private static final long serialVersionUID = 1L;

  private final ConditionVitalSignEffects conditions;
  private final MedicationVitalSignEffects medications;
  private final double minimumValue;
  private final double maximumValue;

  /**
   * Combine condition and medication effects on one vital sign.
   *
   * @param conditions condition effects, or null for none
   * @param medications medication effects, or null for none
   * @param minimumValue lower bound of the vital sign with effects
   * @param maximumValue upper bound of the vital sign with effects
   */
  public VitalSignEffects(ConditionVitalSignEffects conditions,
      MedicationVitalSignEffects medications, double minimumValue, double maximumValue) {
    checkArgument(minimumValue <= maximumValue, "bounds are reversed");
    this.conditions = conditions;
    this.medications = medications == null || medications.isEmpty() ? null : medications;
    this.minimumValue = minimumValue;
    this.maximumValue = maximumValue;
  }

  /**
   * Load the effects on one vital sign from biometrics.yml.
   *
   * @param vitalSign the vital sign's key, e.g. "heart_rate"
   * @param conditions whether conditions act on it
   * @param course whether acute condition effects resolve during the condition
   * @param severity whether each episode draws a severity factor
   * @param medications whether medications act on it
   * @return the effects, or null when nothing acts on this vital sign
   */
  public static VitalSignEffects load(String vitalSign, boolean conditions, boolean course,
      boolean severity, boolean medications) {
    ConditionVitalSignEffects c = conditions
        ? ConditionVitalSignEffects.load(vitalSign, course, severity) : null;
    MedicationVitalSignEffects m = medications ? MedicationVitalSignEffects.load(vitalSign) : null;
    if (c == null && (m == null || m.isEmpty())) {
      return null;
    }
    double[] bounds = loadBounds(vitalSign);
    return new VitalSignEffects(c, m, bounds[0], bounds[1]);
  }

  /**
   * The bounds of a vital sign with effects, from biometrics.yml.
   *
   * @param vitalSign the vital sign's key, e.g. "heart_rate"
   * @return {min, max}
   */
  @SuppressWarnings("unchecked")
  public static double[] loadBounds(String vitalSign) {
    Object bounds = BiometricsConfig.get("vital_sign_effect_bounds");
    checkArgument(bounds instanceof Map && ((Map<String, Object>) bounds).get(vitalSign)
        instanceof List, "biometrics.yml has no vital_sign_effect_bounds for " + vitalSign);
    List<Object> range = (List<Object>) ((Map<String, Object>) bounds).get(vitalSign);
    checkArgument(range.size() == 2, "bounds for " + vitalSign + " must be [min, max]");
    return new double[] {((Number) range.get(0)).doubleValue(),
        ((Number) range.get(1)).doubleValue()};
  }

  /** A new index of the entries these effects read. */
  VitalSignRecordIndex newIndex() {
    Set<String> conditionCodes = conditions == null ? Collections.emptySet()
        : conditions.conditionCodes();
    Set<String> medicationCodes = new HashSet<>();
    if (conditions != null) {
      medicationCodes.addAll(conditions.treatmentCodes());
    }
    if (medications != null) {
      medicationCodes.addAll(medications.codes());
    }
    return new VitalSignRecordIndex(conditionCodes, medicationCodes);
  }

  /** Apply the person's conditions and medications to a value and bound the result. */
  public double apply(Person person, long time, double value) {
    return apply(person, newIndex(), time, value);
  }

  double apply(Person person, VitalSignRecordIndex index, long time, double value) {
    index.update(person);
    double rise = conditions == null ? 0 : conditions.offset(person, index, time);
    double result = medications == null ? value + rise
        : medications.apply(person, index, time, value, rise);
    return Math.min(maximumValue, Math.max(minimumValue, result));
  }

  /** Condition effects, or null. */
  public ConditionVitalSignEffects getConditions() {
    return conditions;
  }

  /** Medication effects, or null. */
  public MedicationVitalSignEffects getMedications() {
    return medications;
  }

  public double getMinimumValue() {
    return minimumValue;
  }

  public double getMaximumValue() {
    return maximumValue;
  }

  @Override
  public String toString() {
    return "VitalSignEffects {conditions=" + conditions + ", medications=" + medications
        + ", bounds=[" + minimumValue + ", " + maximumValue + "]}";
  }
}
