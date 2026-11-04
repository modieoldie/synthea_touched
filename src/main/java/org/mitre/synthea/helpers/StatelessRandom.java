package org.mitre.synthea.helpers;

/** Random numbers that are pure functions of a seed, a salt and an index. */
final class StatelessRandom {
  private StatelessRandom() {
  }

  /** SplitMix64 finalizer. */
  static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }

  /** Uniform in (0, 1). */
  static double uniform(long seed, long salt, long index) {
    long h = mix(seed + mix(salt * 0x9e3779b97f4a7c15L + index));
    return ((h >>> 11) + 0.5) * 0x1.0p-53;
  }

  /** Standard normal (Box-Muller). */
  static double gaussian(long seed, long salt, long index) {
    double u1 = uniform(seed, salt, 2 * index);
    double u2 = uniform(seed, salt, 2 * index + 1);
    return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
  }
}
