package org.mitre.synthea.helpers;

import static com.google.common.base.Preconditions.checkArgument;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.mitre.synthea.helpers.ContinuousVitalSignGenerator.Deviation;
import org.mitre.synthea.modules.BloodPressureValueGenerator;
import org.mitre.synthea.modules.BloodPressureValueGenerator.SysDias;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.VitalSign;

/**
 * A ValueGenerator for systolic or diastolic blood pressure that varies continuously in time.
 *
 * <p>The resting level is the stock generator's (BloodPressureValueGenerator.restingLevel: the
 * baseline for the person's hypertension status plus hypertension drugs and a lifestyle care
 * plan). Conditions scale it (septic shock lowers it by about a third), it dips at night while the
 * person is in a stay, and a slow and a fast mean-reverting deviation plus per-minute measurement
 * noise vary it. Each deviation is partly shared between systolic and diastolic, so the two move
 * together. The diastolic level is kept below max_diastolic_share of the systolic level (pulse
 * pressure), and each diastolic reading at least min_pulse_pressure below the systolic one. Both
 * caps are soft, so values do not pile up at them.</p>
 *
 * <p>Every random number is a hash of the person's seed, so a value depends only on the time asked
 * for and the person's health record and attributes. It does not depend on how often or in which
 * order values are requested, and the person's random number stream is never used. A baseline,
 * drug response or care plan adherence the stock generator already kept in the person's attributes
 * (a population snapshot made with it) is reused.</p>
 */
public class ContinuousBloodPressureGenerator extends ValueGenerator {
  private static final long serialVersionUID = 1L;
  /** Measurement noise is drawn once per minute. */
  private static final long NOISE_BUCKET_MS = 60_000L;
  /** Softness (mmHg) of the cap on the diastolic level. */
  private static final double LEVEL_SOFTNESS = 3;
  /** Softness (mmHg) of the cap on the diastolic reading. */
  private static final double READING_SOFTNESS = 2;

  /** Distinguishes the person's blood pressure seed from their other stateless seeds. */
  private static final long SEED_SALT = 0x62705f73L;
  // salts within the blood pressure seed: index 0 is systolic, 1 diastolic
  private static final long COMMON_SLOW = 1;
  private static final long COMMON_FAST = 2;
  private static final long[] OWN_SLOW = {3, 5};
  private static final long[] OWN_FAST = {4, 6};
  private static final long COMMON_NOISE = 7;
  private static final long[] OWN_NOISE = {8, 9};
  private static final long DIP = 10;
  private static final long DRAWS = 11;
  private static final int SYS = 0;
  private static final int DIA = 1;

  private final SysDias sysDias;
  private final long seed;
  /** Weights of the shared and the own path of each deviation: sqrt(r) and sqrt(1 - r). */
  private final double sharedWeight;
  private final double ownWeight;
  private final double[] slowSd;
  private final double[] fastSd;
  private final double[] measurementSd;
  private final Deviation commonSlow;
  private final Deviation commonFast;
  private final Deviation[] ownSlow;
  private final Deviation[] ownFast;
  /** The person's night dip at its deepest, as a share of the level. */
  private final double dip;
  private final double troughHour;
  private final double halfWidthHours;
  private final double maxDiastolicShare;
  private final double minPulsePressure;
  private final double[] lowerBound;
  private final double[] upperBound;
  /** Condition effects on systolic and diastolic (shares of the resting level); null for none. */
  private final ConditionVitalSignEffects[] effects;
  /** The entries of the person's record that the effects read, built on first use. */
  private transient VitalSignRecordIndex index;
  private transient String zoneState;
  private transient ZoneId zone;

  /**
   * Mean arterial pressure, (systolic + 2 x diastolic) / 3, from the person's blood pressure as
   * recorded (rounded), so a recorded MAP always matches the recorded blood pressure.
   */
  public static class MeanArterialPressure extends ValueGenerator {
    private static final long serialVersionUID = 1L;

    public MeanArterialPressure(Person person) {
      super(person);
    }

    @Override
    public double getValue(long time) {
      return (person.getVitalSign(VitalSign.SYSTOLIC_BLOOD_PRESSURE, time)
          + 2 * person.getVitalSign(VitalSign.DIASTOLIC_BLOOD_PRESSURE, time)) / 3;
    }
  }

  /** Stateless draws for the resting level, not kept in the person's attributes. */
  private static final class StatelessDraws implements BloodPressureValueGenerator.Draws {
    private final long seed;

    StatelessDraws(long seed) {
      this.seed = seed;
    }

    @Override
    public double uniform(String key, double low, double high) {
      return low + (high - low) * StatelessRandom.uniform(seed, DRAWS, key.hashCode());
    }

    @Override
    public boolean keep() {
      return false;
    }
  }

  /**
   * Create a generator for one person's systolic or diastolic blood pressure. The person's two
   * generators share their random numbers, so they agree on every reading.
   *
   * @param person the person the values belong to
   * @param sysDias systolic or diastolic
   * @param config parameters, see metabolic.blood_pressure.continuous in biometrics.yml
   * @param systolicEffects condition effects on systolic, or null for none
   * @param diastolicEffects condition effects on diastolic, or null for none
   * @param systolicBounds {min, max} of systolic
   * @param diastolicBounds {min, max} of diastolic
   */
  @SuppressWarnings("unchecked")
  public ContinuousBloodPressureGenerator(Person person, SysDias sysDias,
      Map<String, Object> config, ConditionVitalSignEffects systolicEffects,
      ConditionVitalSignEffects diastolicEffects, double[] systolicBounds,
      double[] diastolicBounds) {
    super(person);
    this.sysDias = sysDias;
    this.seed = StatelessRandom.mix(person.getSeed() ^ StatelessRandom.mix(SEED_SALT));
    double correlation = number(config, "correlation");
    checkArgument(correlation >= 0 && correlation <= 1, "correlation must be in [0, 1]");
    this.sharedWeight = Math.sqrt(correlation);
    this.ownWeight = Math.sqrt(1 - correlation);
    double slowTau = number(config, "slow_tau_hours");
    double fastTau = number(config, "fast_tau_hours");
    this.commonSlow = new Deviation(COMMON_SLOW, 1, slowTau);
    this.commonFast = new Deviation(COMMON_FAST, 1, fastTau);
    this.ownSlow = new Deviation[] {new Deviation(OWN_SLOW[SYS], 1, slowTau),
        new Deviation(OWN_SLOW[DIA], 1, slowTau)};
    this.ownFast = new Deviation[] {new Deviation(OWN_FAST[SYS], 1, fastTau),
        new Deviation(OWN_FAST[DIA], 1, fastTau)};
    this.slowSd = new double[2];
    this.fastSd = new double[2];
    this.measurementSd = new double[2];
    String[] keys = {"systolic", "diastolic"};
    for (int i = 0; i < 2; i++) {
      Object sds = config.get(keys[i]);
      checkArgument(sds instanceof Map, "missing parameter map '" + keys[i] + "'");
      slowSd[i] = number((Map<String, Object>) sds, "slow_sd");
      fastSd[i] = number((Map<String, Object>) sds, "fast_sd");
      measurementSd[i] = number((Map<String, Object>) sds, "measurement_sd");
      checkArgument(slowSd[i] >= 0 && fastSd[i] >= 0 && measurementSd[i] >= 0,
          "standard deviations of " + keys[i] + " must not be negative");
    }

    Object dipConfig = config.get("nocturnal_dip");
    checkArgument(dipConfig instanceof Map, "missing parameter map 'nocturnal_dip'");
    Map<String, Object> d = (Map<String, Object>) dipConfig;
    double dipMax = number(d, "max");
    checkArgument(dipMax >= 0 && dipMax < 1, "nocturnal_dip.max must be in [0, 1)");
    double drawn = number(d, "mean") + number(d, "sd") * StatelessRandom.gaussian(seed, DIP, 0);
    this.dip = Math.min(dipMax, Math.max(0, drawn));
    this.troughHour = number(config, "dip_trough_hour");
    this.halfWidthHours = number(config, "dip_half_width_hours");
    checkArgument(halfWidthHours > 0 && halfWidthHours <= 12,
        "dip_half_width_hours must be in (0, 12]");

    this.maxDiastolicShare = number(config, "max_diastolic_share");
    checkArgument(maxDiastolicShare > 0 && maxDiastolicShare < 1,
        "max_diastolic_share must be in (0, 1)");
    this.minPulsePressure = number(config, "min_pulse_pressure");
    checkArgument(minPulsePressure >= 0, "min_pulse_pressure must not be negative");
    checkArgument(systolicBounds[0] <= systolicBounds[1]
        && diastolicBounds[0] <= diastolicBounds[1], "bounds are reversed");
    checkArgument(diastolicBounds[0] < systolicBounds[0] - minPulsePressure,
        "the lowest systolic minus min_pulse_pressure must be above the lowest diastolic");
    this.lowerBound = new double[] {systolicBounds[0], diastolicBounds[0]};
    this.upperBound = new double[] {systolicBounds[1], diastolicBounds[1]};
    this.effects = new ConditionVitalSignEffects[] {systolicEffects, diastolicEffects};
  }

  @Override
  public double getValue(long time) {
    if (index == null && (effects[SYS] != null || effects[DIA] != null)) {
      index = newIndex();
    }
    if (index != null) {
      index.update(person);
    }
    double night = 1 - dip * nightShare(time);
    double systolicLevel = level(SYS, time, night);
    double systolic = bound(SYS, systolicLevel + deviation(SYS, time));
    if (sysDias == SysDias.SYSTOLIC) {
      return systolic;
    }
    double diastolicLevel = softMin(level(DIA, time, night),
        maxDiastolicShare * systolicLevel, LEVEL_SOFTNESS);
    double diastolic = softMin(diastolicLevel + deviation(DIA, time),
        systolic - minPulsePressure, READING_SOFTNESS);
    return bound(DIA, diastolic);
  }

  /**
   * The resting level (stock baseline, hypertension drugs, lifestyle) before conditions, the night
   * dip and variation.
   *
   * @param which systolic or diastolic
   * @param time the time
   * @return the resting level
   */
  public double restingLevel(SysDias which, long time) {
    return BloodPressureValueGenerator.restingLevel(person, which, time, new StatelessDraws(seed));
  }

  /**
   * The resting level, scaled by the person's conditions and the night dip. The conditions'
   * combined offset is the log of their factor, so a severe episode or several effects compound
   * towards 0 rather than past it.
   */
  private double level(int i, long time, double night) {
    double resting = restingLevel(i == SYS ? SysDias.SYSTOLIC : SysDias.DIASTOLIC, time);
    double factor = effects[i] == null ? 1 : Math.exp(effects[i].offset(person, index, time));
    return resting * factor * night;
  }

  /** Slow and fast deviation plus measurement noise, each partly shared with the other value. */
  private double deviation(int i, long time) {
    long bucket = Math.floorDiv(time, NOISE_BUCKET_MS);
    double slow = sharedWeight * commonSlow.at(seed, time) + ownWeight * ownSlow[i].at(seed, time);
    double fast = sharedWeight * commonFast.at(seed, time) + ownWeight * ownFast[i].at(seed, time);
    double noise = sharedWeight * StatelessRandom.gaussian(seed, COMMON_NOISE, bucket)
        + ownWeight * StatelessRandom.gaussian(seed, OWN_NOISE[i], bucket);
    return slowSd[i] * slow + fastSd[i] * fast + measurementSd[i] * noise;
  }

  /**
   * How deep into the night dip a time is: 1 at the trough hour, falling as a raised cosine to 0
   * dip_half_width_hours either side of it. 0 when the person is not in a stay.
   */
  double nightShare(long time) {
    if (dip == 0 || !VitalSignTime.inStay(person, time)) {
      return 0;
    }
    return VitalSignTime.raisedCosine(localHour(time), troughHour, halfWidthHours);
  }

  /** Hour of the day (0 to 24) in the time zone of the person's state. */
  private double localHour(long time) {
    String state = (String) person.attributes.get(Person.STATE);
    if (zone == null || !Objects.equals(state, zoneState)) {
      zoneState = state;
      zone = zoneOf(state);
    }
    return VitalSignTime.localHour(zone, time);
  }

  /** The time zone of a state, America/New_York if it is unknown. */
  static ZoneId zoneOf(String state) {
    return VitalSignTime.zoneOf(state);
  }

  private VitalSignRecordIndex newIndex() {
    Set<String> conditionCodes = new HashSet<>();
    Set<String> treatmentCodes = new HashSet<>();
    for (ConditionVitalSignEffects e : effects) {
      if (e != null) {
        conditionCodes.addAll(e.conditionCodes());
        treatmentCodes.addAll(e.treatmentCodes());
      }
    }
    return new VitalSignRecordIndex(conditionCodes, treatmentCodes);
  }

  private double bound(int i, double value) {
    return Math.min(upperBound[i], Math.max(lowerBound[i], value));
  }

  /** A smooth minimum: below both a and b, by at most softness * ln 2 where they are equal. */
  static double softMin(double a, double b, double softness) {
    return Math.min(a, b) - softness * Math.log1p(Math.exp(-Math.abs(a - b) / softness));
  }

  /** The person's night dip at its deepest, as a share of the level. */
  public double getNocturnalDip() {
    return dip;
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
    return "ContinuousBloodPressureGenerator {" + sysDias + ", dip=" + dip
        + ", effects=" + effects[sysDias == SysDias.SYSTOLIC ? SYS : DIA] + "}";
  }
}
