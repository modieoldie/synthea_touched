package org.mitre.synthea.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;

import org.junit.Test;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.VitalSign;

public class ContinuousVitalSignGeneratorTest {
  private static final long HOUR = 3_600_000L;
  private static final long START = 1_788_000_000_000L;  // Sep 2026

  private static ContinuousVitalSignGenerator heartRate(long seed) {
    Map<String, Object> config =
        ContinuousVitalSignGenerator.loadConfig("cardiovascular.heart_rate.continuous");
    return new ContinuousVitalSignGenerator(new Person(seed), 1, 60, 100, config);
  }

  private static ContinuousVitalSignGenerator heartRate(Person person,
      ConditionVitalSignEffects effects) {
    Map<String, Object> config =
        ContinuousVitalSignGenerator.loadConfig("cardiovascular.heart_rate.continuous");
    return new ContinuousVitalSignGenerator(person, 1, 60, 100, config, effects);
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
  public void conditionEffectsChangeNothingWithoutConditions() {
    Person person = new Person(42L);
    ContinuousVitalSignGenerator plain = heartRate(person, null);
    ContinuousVitalSignGenerator withEffects =
        heartRate(person, ConditionVitalSignEffects.load("heart_rate"));
    for (int i = 0; i < 72; i++) {
      assertEquals(plain.getValue(START + i * HOUR), withEffects.getValue(START + i * HOUR), 0.0);
    }
  }

  @Test
  public void conditionEffectsShiftTheHealthyPath() {
    Person person = ConditionVitalSignEffectsTest.patient(42L);
    ConditionVitalSignEffects effects = ConditionVitalSignEffects.load("heart_rate");
    ContinuousVitalSignGenerator plain = heartRate(person, null);
    ContinuousVitalSignGenerator withEffects = heartRate(person, effects);
    long onset = START + 24 * HOUR;
    person.record.encounterStart(onset, EncounterType.EMERGENCY);
    person.record.conditionStart(onset, "91302008");  // sepsis
    double sepsis = effects.offset(person, onset);
    assertTrue(sepsis > 0);
    for (int i = 0; i < 72; i++) {
      long time = START + i * HOUR;
      // not reflected into the normal range: conditions can take the value above 100
      double expected = time < onset ? plain.getValue(time)
          : Math.min(effects.getMaximumValue(), plain.getValue(time) + sepsis);
      assertEquals(expected, withEffects.getValue(time), 1e-9);
    }
  }

  @Test
  public void conditionEffectsDoNotConsumeThePersonsRandomNumbers() {
    Person person = ConditionVitalSignEffectsTest.patient(7L);
    person.record.encounterStart(START, EncounterType.EMERGENCY);
    person.record.conditionStart(START, "91302008");
    ContinuousVitalSignGenerator g =
        heartRate(person, ConditionVitalSignEffects.load("heart_rate"));
    long before = person.getCount();
    for (int i = 0; i < 100; i++) {
      g.getValue(START + i * HOUR);
    }
    assertEquals(before, person.getCount());
  }

  /** Population snapshots (-u / -i) serialize people with their vital sign generators. */
  @Test
  public void survivesSerialization() throws Exception {
    Person person = ConditionVitalSignEffectsTest.patient(42L);
    person.record.encounterStart(START, EncounterType.EMERGENCY);
    person.record.conditionStart(START, "91302008");
    person.setVitalSign(VitalSign.HEART_RATE,
        heartRate(person, ConditionVitalSignEffects.load("heart_rate")));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(person);
    }
    Person copy;
    try (ObjectInputStream in =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      copy = (Person) in.readObject();
    }
    for (int i = -12; i < 72; i++) {
      long time = START + i * HOUR;
      assertEquals(person.vitalSigns.get(VitalSign.HEART_RATE).getValue(time),
          copy.vitalSigns.get(VitalSign.HEART_RATE).getValue(time), 0.0);
    }
    // the copy reads the copied record, not the original one
    copy.record.conditionEnd(START + HOUR, "91302008");
    assertNotEquals(person.vitalSigns.get(VitalSign.HEART_RATE).getValue(START + 48 * HOUR),
        copy.vitalSigns.get(VitalSign.HEART_RATE).getValue(START + 48 * HOUR), 1e-9);
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
