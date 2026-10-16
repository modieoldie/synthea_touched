package org.mitre.synthea.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;
import org.mitre.synthea.world.agents.Person;

public class ContinuousVitalSignGeneratorTest {
  private static final long HOUR = 3_600_000L;
  private static final long START = 1_788_000_000_000L;  // Sep 2026

  private static ContinuousVitalSignGenerator heartRate(long seed) {
    Map<String, Object> config =
        ContinuousVitalSignGenerator.loadConfig("cardiovascular.heart_rate.continuous");
    return new ContinuousVitalSignGenerator(new Person(seed), 1, 60, 100, config);
  }

  @Test
  public void valueDependsOnlyOnTime() {
    ContinuousVitalSignGenerator a = heartRate(42L);
    ContinuousVitalSignGenerator b = heartRate(42L);
    double[] forward = new double[72];
    for (int i = 0; i < 72; i++) {
      forward[i] = a.getValue(START + i * HOUR);
    }
    // a second generator queried backwards, and with extra queries in between
    for (int i = 71; i >= 0; i--) {
      b.getValue(START + i * HOUR + 17 * 60_000L);
      assertEquals(forward[i], b.getValue(START + i * HOUR), 0.0);
    }
  }

  @Test
  public void doesNotConsumeThePersonsRandomNumbers() {
    Person person = new Person(7L);
    Map<String, Object> config =
        ContinuousVitalSignGenerator.loadConfig("cardiovascular.heart_rate.continuous");
    ContinuousVitalSignGenerator g = new ContinuousVitalSignGenerator(person, 1, 60, 100, config);
    long before = person.getCount();
    for (int i = 0; i < 100; i++) {
      g.getValue(START + i * HOUR);
    }
    assertEquals(before, person.getCount());
  }

  @Test
  public void personsDiffer() {
    assertNotEquals(heartRate(1L).getBaseline(), heartRate(2L).getBaseline(), 1e-9);
  }

  @Test
  public void boundedSmoothAndVariable() {
    int people = 200;
    int hours = 72;
    double sumWithinSd = 0;
    double sumStep = 0;
    int nearBound = 0;
    for (long seed = 0; seed < people; seed++) {
      ContinuousVitalSignGenerator g = heartRate(seed);
      double[] v = new double[hours];
      for (int i = 0; i < hours; i++) {
        v[i] = g.getValue(START + i * HOUR);
        assertTrue(v[i] >= 60 && v[i] <= 100);
        if (v[i] < 60.5 || v[i] >= 99.5) {
          nearBound++;   // would be recorded as exactly 60 or 100
        }
      }
      double mean = 0;
      for (double x : v) {
        mean += x / hours;
      }
      double var = 0;
      double step = 0;
      for (int i = 0; i < hours; i++) {
        var += (v[i] - mean) * (v[i] - mean) / (hours - 1);
        if (i > 0) {
          step += Math.abs(v[i] - v[i - 1]) / (hours - 1);
        }
      }
      sumWithinSd += Math.sqrt(var);
      sumStep += step;
    }
    double withinSd = sumWithinSd / people;
    double meanStep = sumStep / people;
    // varies within a stay, but hour-to-hour changes are much smaller than independent draws
    // from the normal range (SD 11.5, mean absolute difference about 13)
    assertTrue("within-stay SD " + withinSd, withinSd > 2 && withinSd < 9);
    assertTrue("mean hourly step " + meanStep, meanStep < 5);
    // no pile-up at the bounds: one 1-wide bin at each end of a 40-wide range is about 2.5%
    double fractionAtBounds = nearBound / (double) (people * hours);
    assertTrue("fraction at bounds " + fractionAtBounds, fractionAtBounds < 0.04);
  }
}
