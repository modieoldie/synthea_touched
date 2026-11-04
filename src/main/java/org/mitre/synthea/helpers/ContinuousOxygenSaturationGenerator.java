package org.mitre.synthea.helpers;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mitre.synthea.helpers.ContinuousVitalSignGenerator.Deviation;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.VitalSign;

/**
 * A ValueGenerator for oxygen saturation by pulse oximetry (%, whole numbers) that varies
 * continuously in time.
 *
 * <p>It models the deficit below 100 rather than the value: the person's deficit (with a small age
 * term) plus what conditions and an acute encounter for a chronic lung or heart condition add,
 * multiplied by log-normal slow and fast deviations. A sicker patient therefore swings more, as on
 * the steep part of the oxygen dissociation curve, and a reading can not pass 100. During stays the
 * deficit grows at night.</p>
 *
 * <p>Staff start oxygen in hospital when the room-air level is low: the level rises towards a
 * target over the first hour of the encounter, so the first reading of an encounter is on room air.
 * InhaledOxygen gives the matching oxygen concentration, so a reading can be interpreted.</p>
 *
 * <p>Every random number is a hash of the person's seed, so a value depends only on the time asked
 * for and the person's health record and attributes. It does not depend on how often or in which
 * order values are requested, and the person's random number stream is never used.</p>
 */
public class ContinuousOxygenSaturationGenerator extends ValueGenerator {
  private static final long serialVersionUID = 1L;
  private static final double MS_PER_HOUR = 3_600_000.0;
  /** Measurement noise is drawn once per minute. */
  private static final long NOISE_BUCKET_MS = 60_000L;
  private static final double ROOM_AIR_PERCENT = 21;
  /** Distinguishes the person's oxygen saturation seed from their other stateless seeds. */
  private static final long SEED_SALT = 0x6f327361L;
  private static final long SLOW = 1;
  private static final long FAST = 2;
  private static final long NOISE = 3;
  private static final long BASELINE = 4;
  private static final long EXACERBATION = 5;

  private final long seed;
  private final double baselineDeficit;
  private final double ageSlopePerYear;
  private final double ageSlopeFrom;
  private final double measurementSd;
  private final double nightFactor;
  private final double troughHour;
  private final double halfWidthHours;
  private final double minimumValue;
  private final double maximumValue;
  private final Deviation slow;
  private final Deviation fast;
  private final double combinationWeight;
  private final double oxygenStart;
  private final double oxygenSoftness;
  private final double oxygenTarget;
  private final double oxygenEfficacy;
  private final double oxygenRampMs;
  private final double oxygenMinLift;
  private final double fio2Linear;
  private final double fio2Quadratic;
  private final Set<String> exacerbationCodes;
  private final double exacerbationDeficit;
  private final double exacerbationResolutionMs;
  private final double exacerbationResidual;
  private final double exacerbationSeveritySd;
  /** Condition effects, in points of saturation (negative lowers it); null for none. */
  private final ConditionVitalSignEffects effects;
  /** The entries of the person's record that the effects read, built on first use. */
  private transient VitalSignRecordIndex index;

  /**
   * The oxygen concentration (%) the person breathes: 21 on room air, higher while the oxygen
   * saturation generator gives them oxygen. 21 if a module has set their saturation directly.
   */
  public static class InhaledOxygen extends ValueGenerator {
    private static final long serialVersionUID = 1L;

    public InhaledOxygen(Person person) {
      super(person);
    }

    @Override
    public double getValue(long time) {
      ValueGenerator saturation = person.vitalSigns.get(VitalSign.OXYGEN_SATURATION);
      return saturation instanceof ContinuousOxygenSaturationGenerator
          ? ((ContinuousOxygenSaturationGenerator) saturation).inhaledOxygen(time)
          : ROOM_AIR_PERCENT;
    }
  }

  /**
   * Create a generator for one person's oxygen saturation.
   *
   * @param person the person the values belong to
   * @param config parameters, see cardiovascular.oxygen_saturation.continuous in biometrics.yml
   * @param effects the person's conditions, or null for none
   * @param bounds {min, max} of the values
   */
  @SuppressWarnings("unchecked")
  public ContinuousOxygenSaturationGenerator(Person person, Map<String, Object> config,
      ConditionVitalSignEffects effects, double[] bounds) {
    super(person);
    checkArgument(bounds[0] <= bounds[1] && bounds[1] <= 100, "bounds must be within 100");
    this.seed = StatelessRandom.mix(person.getSeed() ^ StatelessRandom.mix(SEED_SALT));
    this.effects = effects;
    this.minimumValue = bounds[0];
    this.maximumValue = bounds[1];
    double median = number(config, "baseline_deficit_median");
    double logSd = number(config, "baseline_deficit_log_sd");
    checkArgument(median > 0 && logSd >= 0, "baseline deficit must be positive");
    this.baselineDeficit = median * Math.exp(logSd * StatelessRandom.gaussian(seed, BASELINE, 0));
    this.ageSlopePerYear = number(config, "age_slope_per_decade") / 10;
    this.ageSlopeFrom = number(config, "age_slope_from");
    this.measurementSd = number(config, "measurement_sd");
    checkArgument(measurementSd >= 0, "measurement_sd must not be negative");
    this.nightFactor = number(config, "night_factor");
    this.troughHour = number(config, "night_trough_hour");
    this.halfWidthHours = number(config, "night_half_width_hours");
    checkArgument(halfWidthHours > 0 && halfWidthHours <= 12,
        "night_half_width_hours must be in (0, 12]");
    this.slow = new Deviation(SLOW, number(config, "slow_log_sd"),
        number(config, "slow_tau_hours"));
    this.fast = new Deviation(FAST, number(config, "fast_log_sd"),
        number(config, "fast_tau_hours"));
    this.combinationWeight = number(config, "combination_weight");

    Object oxygenConfig = config.get("oxygen");
    checkArgument(oxygenConfig instanceof Map, "missing parameter map 'oxygen'");
    Map<String, Object> oxygen = (Map<String, Object>) oxygenConfig;
    this.oxygenStart = number(oxygen, "start_below");
    this.oxygenSoftness = number(oxygen, "softness");
    checkArgument(oxygenSoftness > 0, "oxygen.softness must be positive");
    this.oxygenTarget = number(oxygen, "target");
    this.oxygenEfficacy = number(oxygen, "efficacy");
    checkArgument(oxygenEfficacy >= 0 && oxygenEfficacy <= 1, "oxygen.efficacy must be in [0, 1]");
    this.oxygenRampMs = number(oxygen, "ramp_hours") * MS_PER_HOUR;
    checkArgument(oxygenRampMs > 0, "oxygen.ramp_hours must be positive");
    this.oxygenMinLift = number(oxygen, "min_lift");
    checkArgument(oxygenMinLift >= 0, "oxygen.min_lift must not be negative");
    this.fio2Linear = number(oxygen, "fio2_per_point");
    this.fio2Quadratic = number(oxygen, "fio2_per_point_squared");

    Object exacerbationConfig = config.get("acute_encounter");
    checkArgument(exacerbationConfig instanceof Map, "missing parameter map 'acute_encounter'");
    Map<String, Object> exacerbation = (Map<String, Object>) exacerbationConfig;
    Set<String> codes = new HashSet<>();
    for (Object code : (List<Object>) exacerbation.get("codes")) {
      codes.add(String.valueOf(code));
    }
    this.exacerbationCodes = Collections.unmodifiableSet(new HashSet<>(codes));
    this.exacerbationDeficit = number(exacerbation, "deficit");
    this.exacerbationResolutionMs = number(exacerbation, "resolution_hours") * MS_PER_HOUR;
    checkArgument(exacerbationResolutionMs > 0,
        "acute_encounter.resolution_hours must be positive");
    this.exacerbationResidual = number(exacerbation, "residual");
    this.exacerbationSeveritySd = number(exacerbation, "severity_sd");
  }

  @Override
  public double getValue(long time) {
    double slowDeficit = slowDeficit(time);
    double treated = Math.max(0, slowDeficit - oxygenLift(time, slowDeficit));
    double reading = 100 - treated * Math.exp(fast.at(seed, time))
        + measurementSd * StatelessRandom.gaussian(seed, NOISE,
            Math.floorDiv(time, NOISE_BUCKET_MS));
    return Math.rint(Math.min(maximumValue, Math.max(minimumValue, reading)));
  }

  /** The room-air reading at a time, before oxygen, for tests and diagnostics. */
  public double roomAirValue(long time) {
    double reading = 100 - slowDeficit(time) * Math.exp(fast.at(seed, time))
        + measurementSd * StatelessRandom.gaussian(seed, NOISE,
            Math.floorDiv(time, NOISE_BUCKET_MS));
    return Math.rint(Math.min(maximumValue, Math.max(minimumValue, reading)));
  }

  /** The inhaled oxygen concentration (%) at a time; 21 on room air. */
  double inhaledOxygen(long time) {
    double lift = oxygenLift(time, slowDeficit(time));
    return Math.min(100, ROOM_AIR_PERCENT + fio2Linear * lift + fio2Quadratic * lift * lift);
  }

  /** The deficit on room air, without the fast deviation and measurement noise. */
  private double slowDeficit(long time) {
    double age = person.ageInDecimalYears(time);
    double base = baselineDeficit + ageSlopePerYear * Math.max(0, age - ageSlopeFrom);
    return (base + illnessDeficit(time)) * Math.exp(slow.at(seed, time)) * (1 + nightShare(time));
  }

  /** Points of saturation lost to conditions and any acute encounter, combined sub-additively. */
  private double illnessDeficit(long time) {
    double conditions = 0;
    if (effects != null) {
      if (index == null) {
        index = new VitalSignRecordIndex(effects.conditionCodes(), effects.treatmentCodes());
      }
      index.update(person);
      conditions = Math.max(0, -effects.offset(person, index, time));
    }
    double encounter = exacerbation(time);
    return Math.max(conditions, encounter) + combinationWeight * Math.min(conditions, encounter);
  }

  /**
   * What an emergency or inpatient encounter for a chronic lung or heart condition adds: strongest
   * at arrival, with a severity drawn for the encounter. Timed from arrival, never from discharge.
   */
  private double exacerbation(long time) {
    Encounter encounter = VitalSignTime.oxygenEncounter(person, time);
    if (encounter == null || encounter.reason == null
        || !exacerbationCodes.contains(encounter.reason.code)) {
      return 0;
    }
    double z = StatelessRandom.gaussian(seed, EXACERBATION, encounter.start);
    double severity = Math.exp(exacerbationSeveritySd * z
        - exacerbationSeveritySd * exacerbationSeveritySd / 2);
    double remaining = Math.exp(-(time - encounter.start) / exacerbationResolutionMs);
    return exacerbationDeficit * severity
        * (exacerbationResidual + (1 - exacerbationResidual) * remaining);
  }

  /** How much of the deficit is added at night: only during a stay. */
  private double nightShare(long time) {
    if (nightFactor == 0 || !VitalSignTime.inStay(person, time)) {
      return 0;
    }
    return nightFactor * VitalSignTime.raisedCosine(VitalSignTime.localHour(person, time),
        troughHour, halfWidthHours);
  }

  /**
   * How many points oxygen adds: none outside hospital, none at the start of an encounter, and in
   * hospital a share of the gap to the target once the room-air level is below the start level.
   * The start is judged on the slow level, so a patient does not flicker on and off oxygen. Less
   * than min_lift counts as none: nobody is given a clinically meaningless trickle of oxygen.
   */
  private double oxygenLift(long time, double slowDeficit) {
    Encounter encounter = VitalSignTime.oxygenEncounter(person, time);
    if (encounter == null) {
      return 0;
    }
    double level = 100 - slowDeficit;
    double gap = oxygenTarget - level;
    if (gap <= 0) {
      return 0;
    }
    double started = 1 / (1 + Math.exp((level - oxygenStart) / oxygenSoftness));
    double ramp = 1 - Math.exp(-(time - encounter.start) / oxygenRampMs);
    double lift = oxygenEfficacy * started * ramp * gap;
    return lift < oxygenMinLift ? 0 : lift;
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
    return "ContinuousOxygenSaturationGenerator {baselineDeficit=" + baselineDeficit + ", effects="
        + effects + "}";
  }
}
