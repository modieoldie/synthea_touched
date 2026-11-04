package org.mitre.synthea.helpers;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Map;

import org.mitre.synthea.helpers.ContinuousVitalSignGenerator.Deviation;
import org.mitre.synthea.world.agents.Person;

/**
 * A ValueGenerator for body temperature (degrees Celsius) that varies continuously in time.
 *
 * <p>A reading is the person's baseline (with small age and sex terms), plus a daily rhythm, plus a
 * slow and a fast mean-reverting deviation and per-minute measurement noise. VitalSignEffects then
 * applies conditions (fever, or the fall in temperature of a cold sepsis episode) and antipyretics,
 * and bounds the result.</p>
 *
 * <p>The rhythm follows the local hour during stays (inpatient, nursing facility, hospice). Outside
 * them it is read at a fixed clinic hour: a person's timestep events all fall at one time of day,
 * so following the hour there would shift all of one person's outpatient readings the same way.</p>
 *
 * <p>Every random number is a hash of the person's seed, so a value depends only on the time asked
 * for and the person's health record and attributes. It does not depend on how often or in which
 * order values are requested, and the person's random number stream is never used.</p>
 */
public class ContinuousBodyTemperatureGenerator extends ValueGenerator {
  private static final long serialVersionUID = 1L;
  /** Measurement noise is drawn once per minute. */
  private static final long NOISE_BUCKET_MS = 60_000L;
  /** Distinguishes the person's temperature seed from their other stateless seeds. */
  private static final long SEED_SALT = 0x74656d70L;
  private static final long SLOW = 1;
  private static final long FAST = 2;
  private static final long NOISE = 3;
  private static final long BASELINE = 4;
  private static final long AMPLITUDE = 5;

  private final long seed;
  private final double baseline;
  private final double ageSlopePerYear;
  private final double referenceAge;
  private final double rhythmAmplitude;
  private final double peakHour;
  private final double clinicHour;
  private final double measurementSd;
  private final double minimumValue;
  private final double maximumValue;
  private final Deviation slow;
  private final Deviation fast;
  /** The person's conditions and medications; null for none. */
  private final VitalSignEffects effects;
  /** The entries of the person's record that the effects read, built on first use. */
  private transient VitalSignRecordIndex index;

  /**
   * Create a generator for one person's body temperature.
   *
   * @param person the person the values belong to
   * @param config parameters, see cardiovascular.body_temperature.continuous in biometrics.yml
   * @param effects the person's conditions and medications, or null for none
   * @param bounds {min, max} of the values
   */
  public ContinuousBodyTemperatureGenerator(Person person, Map<String, Object> config,
      VitalSignEffects effects, double[] bounds) {
    super(person);
    checkArgument(bounds[0] <= bounds[1], "bounds are reversed");
    this.seed = StatelessRandom.mix(person.getSeed() ^ StatelessRandom.mix(SEED_SALT));
    this.effects = effects;
    this.minimumValue = bounds[0];
    this.maximumValue = bounds[1];
    double baselineSd = number(config, "baseline_sd");
    double slowSd = number(config, "slow_sd");
    double fastSd = number(config, "fast_sd");
    this.measurementSd = number(config, "measurement_sd");
    checkArgument(baselineSd >= 0 && slowSd >= 0 && fastSd >= 0 && measurementSd >= 0,
        "standard deviations must not be negative");
    double femaleOffset = "F".equals(person.attributes.get(Person.GENDER))
        ? number(config, "female_offset") : 0;
    this.baseline = number(config, "baseline_mean") + femaleOffset
        + baselineSd * StatelessRandom.gaussian(seed, BASELINE, 0);
    this.ageSlopePerYear = number(config, "age_slope_per_decade") / 10;
    this.referenceAge = number(config, "reference_age");
    double amplitudeSd = number(config, "rhythm_amplitude_sd");
    checkArgument(amplitudeSd >= 0, "rhythm_amplitude_sd must not be negative");
    this.rhythmAmplitude = Math.max(0, number(config, "rhythm_amplitude")
        + amplitudeSd * StatelessRandom.gaussian(seed, AMPLITUDE, 0));
    this.peakHour = number(config, "rhythm_peak_hour");
    this.clinicHour = number(config, "clinic_hour");
    this.slow = new Deviation(SLOW, slowSd, number(config, "slow_tau_hours"));
    this.fast = new Deviation(FAST, fastSd, number(config, "fast_tau_hours"));
  }

  @Override
  public double getValue(long time) {
    double value = baseline + ageSlopePerYear * (person.ageInDecimalYears(time) - referenceAge)
        + rhythm(time) + slow.at(seed, time) + fast.at(seed, time)
        + measurementSd * StatelessRandom.gaussian(seed, NOISE,
            Math.floorDiv(time, NOISE_BUCKET_MS));
    if (effects != null) {
      if (index == null) {
        index = effects.newIndex();
      }
      value = effects.apply(person, index, time, value);
    }
    return Math.min(maximumValue, Math.max(minimumValue, value));
  }

  /** The daily rhythm: by the local hour during a stay, at a fixed clinic hour otherwise. */
  double rhythm(long time) {
    double hour = VitalSignTime.inStay(person, time) ? VitalSignTime.localHour(person, time)
        : clinicHour;
    return rhythmAmplitude * Math.cos(2 * Math.PI * (hour - peakHour) / 24);
  }

  /** The person's baseline (at the reference age, before the rhythm and variation). */
  public double getBaseline() {
    return baseline;
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
    return "ContinuousBodyTemperatureGenerator {baseline=" + baseline + ", amplitude="
        + rhythmAmplitude + ", effects=" + effects + "}";
  }
}
