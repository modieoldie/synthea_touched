package org.mitre.synthea.helpers;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Map;

import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.BiometricsConfig;

/**
 * A vital sign that varies continuously in time, such as heart rate: a per-person baseline plus
 * slow and fast mean-reverting deviations and measurement noise, reflected into [min, max].
 * VitalSignEffects may then take the value outside that range, up to its own bounds.
 *
 * <p>A value depends only on the time, not on generate.timestep or the order of requests.</p>
 */
public class ContinuousVitalSignGenerator extends ValueGenerator {
  /** Kept at 1 so older snapshots still load; LifecycleModule then replaces the generator. */
  private static final long serialVersionUID = 1L;
  private static final double MS_PER_HOUR = 3_600_000.0;
  /** Measurement noise is drawn once per minute. */
  private static final long NOISE_BUCKET_MS = 60_000L;
  /** Grid spacing of each deviation as a fraction of its time constant. */
  private static final double GRID_FRACTION_OF_TAU = 0.1;
  /** Truncate the AR(1) sum once the weight of older innovations falls below this. */
  private static final double TRUNCATION_WEIGHT = 1e-3;

  private final long seed;
  private final double baseline;
  private final double minimumValue;
  private final double maximumValue;
  private final double measurementSd;
  private final Deviation slow;
  private final Deviation fast;
  /** The person's conditions and medications; null for none. */
  private final VitalSignEffects effects;
  /** Built on first use. */
  private transient VitalSignRecordIndex index;

  /** One mean-reverting deviation with stationary SD sd and time constant tau. */
  static class Deviation implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    final long salt;
    final double gridMs;
    final double phi;
    final double innovationSd;
    final int terms;
    /** The last two grid points computed (readings close in time share them). */
    private transient long[] cachedK;
    private transient double[] cachedValue;

    Deviation(long salt, double sd, double tauHours) {
      checkArgument(sd >= 0 && tauHours > 0);
      this.salt = salt;
      this.gridMs = tauHours * GRID_FRACTION_OF_TAU * MS_PER_HOUR;
      this.phi = Math.exp(-GRID_FRACTION_OF_TAU);
      this.innovationSd = sd * Math.sqrt(1 - phi * phi);
      this.terms = (int) Math.ceil(Math.log(TRUNCATION_WEIGHT) / Math.log(phi));
    }

    /** AR(1) value at grid index k: sum over j of phi^j * innovation(k - j). */
    double atGridPoint(long seed, long k) {
      if (cachedK == null) {
        cachedK = new long[] {Long.MIN_VALUE, Long.MIN_VALUE};
        cachedValue = new double[2];
      }
      for (int i = 0; i < 2; i++) {
        if (cachedK[i] == k) {
          return cachedValue[i];
        }
      }
      double sum = 0;
      double weight = 1;
      for (int j = 0; j <= terms; j++) {
        sum += weight * StatelessRandom.gaussian(seed, salt, k - j);
        weight *= phi;
      }
      double value = innovationSd * sum;
      // replace the earlier of the two cached points
      int slot = cachedK[0] < cachedK[1] ? 0 : 1;
      cachedK[slot] = k;
      cachedValue[slot] = value;
      return value;
    }

    double at(long seed, long time) {
      double position = time / gridMs;
      long k = (long) Math.floor(position);
      double frac = position - k;
      return (1 - frac) * atGridPoint(seed, k) + frac * atGridPoint(seed, k + 1);
    }
  }

  /**
   * Create a generator for one vital sign of one person.
   *
   * @param vitalSalt distinguishes this vital sign's random path from the person's others
   * @param minimumValue lower bound of the values; the baseline is drawn inside the bounds
   * @param maximumValue upper bound of the values
   * @param config parameters, see the "continuous" entries in biometrics.yml
   */
  public ContinuousVitalSignGenerator(Person person, long vitalSalt,
      double minimumValue, double maximumValue, Map<String, Object> config) {
    this(person, vitalSalt, minimumValue, maximumValue, config, null);
  }

  /**
   * Create a generator for one vital sign of one person that also responds to the person's
   * conditions and medications.
   *
   * @param vitalSalt distinguishes this vital sign's random path from the person's others
   * @param minimumValue lower bound of the values without effects
   * @param maximumValue upper bound of the values without effects
   * @param config parameters, see the "continuous" entries in biometrics.yml
   * @param effects the person's conditions and medications, or null for none
   */
  public ContinuousVitalSignGenerator(Person person, long vitalSalt,
      double minimumValue, double maximumValue, Map<String, Object> config,
      VitalSignEffects effects) {
    super(person);
    this.effects = effects;
    checkArgument(minimumValue <= maximumValue);
    this.seed = StatelessRandom.mix(person.getSeed() ^ StatelessRandom.mix(vitalSalt));
    this.minimumValue = minimumValue;
    this.maximumValue = maximumValue;
    double margin = number(config, "baseline_margin");
    checkArgument(margin >= 0 && margin < 0.5);
    double width = maximumValue - minimumValue;
    this.baseline = minimumValue
        + width * (margin + (1 - 2 * margin) * StatelessRandom.uniform(seed, 0, -1));
    this.slow = new Deviation(1, number(config, "slow_sd"), number(config, "slow_tau_hours"));
    this.fast = new Deviation(2, number(config, "fast_sd"), number(config, "fast_tau_hours"));
    this.measurementSd = number(config, "measurement_sd");
  }

  /**
   * Load the parameters for one vital sign from biometrics.yml.
   *
   * @param path path of the parameter map, e.g. "cardiovascular.heart_rate.continuous"
   * @return the parameter map
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> loadConfig(String path) {
    Object config = BiometricsConfig.get(path);
    if (!(config instanceof Map)) {
      throw new IllegalStateException("biometrics.yml has no parameter map at " + path);
    }
    return (Map<String, Object>) config;
  }

  @Override
  public double getValue(long time) {
    double value = baseline + slow.at(seed, time) + fast.at(seed, time)
        + measurementSd * StatelessRandom.gaussian(seed, 3, Math.floorDiv(time, NOISE_BUCKET_MS));
    value = reflect(value);
    if (effects == null) {
      return value;
    }
    if (index == null) {
      index = effects.newIndex();
    }
    return effects.apply(person, index, time, value);
  }

  /** Fold the value back into [min, max] at the bounds (clamping would pile values up there). */
  private double reflect(double value) {
    double width = maximumValue - minimumValue;
    if (width == 0) {
      return minimumValue;
    }
    double offset = (value - minimumValue) % (2 * width);
    if (offset < 0) {
      offset += 2 * width;
    }
    return minimumValue + (offset > width ? 2 * width - offset : offset);
  }

  /** The person's baseline (long-run mean before bounding). */
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
    return "ContinuousVitalSignGenerator {baseline=" + baseline + ", minimumValue=" + minimumValue
        + ", maximumValue=" + maximumValue + ", effects=" + effects + "}";
  }
}
