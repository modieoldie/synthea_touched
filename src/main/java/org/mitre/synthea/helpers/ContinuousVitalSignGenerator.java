package org.mitre.synthea.helpers;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Map;

import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.BiometricsConfig;

/**
 * A ValueGenerator for vital signs that vary continuously in time, such as heart rate.
 *
 * <p>The value is a per-person baseline plus a slow and a fast mean-reverting
 * (Ornstein-Uhlenbeck) deviation plus white measurement noise, reflected into [min, max].
 * Each deviation is an AR(1) process on its own fixed time grid and is linearly
 * interpolated between grid points.</p>
 *
 * <p>The random innovations are a hash of the person's seed, the vital sign and the grid
 * index, so getValue(time) depends only on the time asked for. It does not depend on
 * generate.timestep, on how often or in which order values are requested, and it does not
 * consume the person's random number stream.</p>
 */
public class ContinuousVitalSignGenerator extends ValueGenerator {
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

  /** One mean-reverting deviation with stationary SD sd and time constant tau. */
  private static class Deviation implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    final long salt;
    final double gridMs;
    final double phi;
    final double innovationSd;
    final int terms;

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
      double sum = 0;
      double weight = 1;
      for (int j = 0; j <= terms; j++) {
        sum += weight * gaussian(seed, salt, k - j);
        weight *= phi;
      }
      return innovationSd * sum;
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
   * @param person the person the values belong to
   * @param vitalSalt distinguishes this vital sign's random path from the person's others
   * @param minimumValue lower bound of the values; the baseline is drawn inside the bounds
   * @param maximumValue upper bound of the values
   * @param config parameters, see the "continuous" entries in biometrics.yml
   */
  public ContinuousVitalSignGenerator(Person person, long vitalSalt,
      double minimumValue, double maximumValue, Map<String, Object> config) {
    super(person);
    checkArgument(minimumValue <= maximumValue);
    this.seed = mix(person.getSeed() ^ mix(vitalSalt));
    this.minimumValue = minimumValue;
    this.maximumValue = maximumValue;
    double margin = number(config, "baseline_margin");
    checkArgument(margin >= 0 && margin < 0.5);
    double width = maximumValue - minimumValue;
    this.baseline = minimumValue + width * (margin + (1 - 2 * margin) * uniform(seed, 0, -1));
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
        + measurementSd * gaussian(seed, 3, Math.floorDiv(time, NOISE_BUCKET_MS));
    return reflect(value);
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

  /** SplitMix64 finalizer. */
  private static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }

  /** Uniform in (0, 1), a pure function of its arguments. */
  private static double uniform(long seed, long salt, long index) {
    long h = mix(seed + mix(salt * 0x9e3779b97f4a7c15L + index));
    return ((h >>> 11) + 0.5) * 0x1.0p-53;
  }

  /** Standard normal (Box-Muller), a pure function of its arguments. */
  private static double gaussian(long seed, long salt, long index) {
    double u1 = uniform(seed, salt, 2 * index);
    double u2 = uniform(seed, salt, 2 * index + 1);
    return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
  }

  @Override
  public String toString() {
    return "ContinuousVitalSignGenerator {baseline=" + baseline + ", minimumValue=" + minimumValue
        + ", maximumValue=" + maximumValue + "}";
  }
}
