package org.mitre.synthea.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Map;

import org.junit.Test;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.VitalSign;

public class ContinuousBodyTemperatureGeneratorTest {
  private static final long HOUR = 3_600_000L;
  private static final long DAY = 24 * HOUR;
  private static final long YEAR = 365L * DAY;
  private static final ZoneId EASTERN = ZoneId.of("America/New_York");
  /** 2026-09-01 15:00 in Massachusetts. */
  private static final long AFTERNOON = ZonedDateTime.of(2026, 9, 1, 15, 0, 0, 0, EASTERN)
      .toInstant().toEpochMilli();
  private static final String FEVER = "386661006";
  private static final String SEPSIS = "91302008";
  private static final String SEPTIC_SHOCK = "76571007";
  private static final String ARDS = "67782005";
  private static final String ACETAMINOPHEN = "313782";

  private static Map<String, Object> config() {
    return ContinuousVitalSignGenerator.loadConfig("cardiovascular.body_temperature.continuous");
  }

  private static double[] bounds() {
    return VitalSignEffects.loadBounds("body_temperature");
  }

  private static ContinuousBodyTemperatureGenerator plain(Person person) {
    return new ContinuousBodyTemperatureGenerator(person, config(), null, bounds());
  }

  /** With the conditions and medications of biometrics.yml, as listed or with course/severity. */
  private static ContinuousBodyTemperatureGenerator withEffects(Person person, boolean flat) {
    return new ContinuousBodyTemperatureGenerator(person, config(),
        VitalSignEffects.load("body_temperature", true, !flat, !flat, true), bounds());
  }

  private static Person patient(long seed) {
    return ConditionVitalSignEffectsTest.patient(seed);
  }

  /** Person caches the birth date as a LocalDate the first time it computes an age. */
  private static void bornYearsAgo(Person person, int years) {
    person.attributes.put(Person.BIRTHDATE, AFTERNOON - years * YEAR);
    person.attributes.remove(Person.BIRTHDATE_AS_LOCALDATE);
  }

  private static double mean(double[] x) {
    return Arrays.stream(x).average().orElse(Double.NaN);
  }

  private static double quantile(double[] x, double q) {
    double[] sorted = x.clone();
    Arrays.sort(sorted);
    return sorted[(int) Math.min(sorted.length - 1, Math.floor(q * sorted.length))];
  }

  @Test
  public void valueDependsOnlyOnTime() {
    ContinuousBodyTemperatureGenerator a = plain(patient(42L));
    ContinuousBodyTemperatureGenerator b = plain(patient(42L));
    double[] forward = new double[72];
    for (int i = 0; i < 72; i++) {
      forward[i] = a.getValue(AFTERNOON + i * HOUR);
    }
    for (int i = 71; i >= 0; i--) {
      b.getValue(AFTERNOON + i * HOUR + 17 * 60_000L);
      assertEquals(forward[i], b.getValue(AFTERNOON + i * HOUR), 0.0);
    }
    assertNotEquals(forward[0], plain(patient(43L)).getValue(AFTERNOON), 1e-9);
  }

  @Test
  public void drawsNothingFromThePerson() {
    Person person = patient(7L);
    person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
    person.record.conditionStart(AFTERNOON, SEPSIS);
    person.record.medicationAdministration(AFTERNOON, ACETAMINOPHEN);
    ContinuousBodyTemperatureGenerator g = withEffects(person, false);
    long before = person.getCount();
    int attributes = person.attributes.size();
    for (int i = 0; i < 100; i++) {
      g.getValue(AFTERNOON + i * HOUR);
    }
    assertEquals(before, person.getCount());
    assertEquals(attributes, person.attributes.size());
  }

  /** Reading a value draws nothing, but each recorded observation draws a random UUID. */
  @Test
  public void recordingAReadingMovesTheRandomNumbersThroughItsEntry() {
    Person person = patient(8L);
    person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
    ContinuousBodyTemperatureGenerator g = plain(person);
    long before = person.getCount();
    double value = g.getValue(AFTERNOON);
    assertEquals(before, person.getCount());
    person.record.observation(AFTERNOON, "8310-5", value);
    assertTrue(person.getCount() > before);
  }

  /** As in a population snapshot. */
  @Test
  public void survivesSerialization() throws Exception {
    Person person = patient(11L);
    person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
    person.record.conditionStart(AFTERNOON, FEVER);
    ContinuousBodyTemperatureGenerator g = withEffects(person, false);
    double value = g.getValue(AFTERNOON + 5 * HOUR);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(g);
    }
    try (ObjectInputStream in = new ObjectInputStream(
        new ByteArrayInputStream(bytes.toByteArray()))) {
      ContinuousBodyTemperatureGenerator copy =
          (ContinuousBodyTemperatureGenerator) in.readObject();
      assertEquals(value, copy.getValue(AFTERNOON + 5 * HOUR), 0.0);
    }
  }

  /** Afebrile outpatient readings: mean 36.6, 95% in 35.7-37.3 (Obermeyer et al., BMJ 2017). */
  @Test
  public void healthyOutpatientReadingsMatchThePublishedRange() {
    int people = 6000;
    double[] readings = new double[people];
    for (int i = 0; i < people; i++) {
      Person person = patient(100 + i);
      person.attributes.put(Person.GENDER, i % 100 < 64 ? "F" : "M");
      bornYearsAgo(person, 53);
      readings[i] = plain(person).getValue(AFTERNOON + i * DAY / 7);
    }
    double mean = mean(readings);
    double low = quantile(readings, 0.025);
    double high = quantile(readings, 0.975);
    System.out.printf("temperature, healthy outpatients: mean %.2f, 95%% range %.2f-%.2f%n",
        mean, low, high);
    assertEquals(36.6, mean, 0.03);
    assertEquals(35.7, low, 0.2);
    assertEquals(37.3, high, 0.2);
  }

  /** Lowest around 06:00, highest 16:00-18:00, about 0.5 apart (Mackowiak et al., JAMA 1992). */
  @Test
  public void rhythmFollowsTheLocalHourDuringStays() {
    int people = 1500;
    double[] byHour = new double[24];
    for (int i = 0; i < people; i++) {
      Person person = patient(9000 + i);
      person.attributes.put(Person.STATE, "Massachusetts");
      person.record.encounterStart(AFTERNOON - 2 * DAY, EncounterType.INPATIENT);
      ContinuousBodyTemperatureGenerator g = plain(person);
      for (int h = 0; h < 24; h++) {
        // AFTERNOON is 15:00 local
        byHour[(15 + h) % 24] += g.getValue(AFTERNOON + h * HOUR) / people;
      }
    }
    int lowest = 0;
    int highest = 0;
    for (int h = 0; h < 24; h++) {
      lowest = byHour[h] < byHour[lowest] ? h : lowest;
      highest = byHour[h] > byHour[highest] ? h : highest;
    }
    double swing = byHour[highest] - byHour[lowest];
    System.out.printf("temperature, stay: lowest at %02d:00, highest at %02d:00, swing %.2f%n",
        lowest, highest, swing);
    assertTrue("lowest at " + lowest, lowest >= 3 && lowest <= 7);
    assertTrue("highest at " + highest, highest >= 15 && highest <= 19);
    assertEquals(0.5, swing, 0.12);
  }

  /** A person's visits all fall at one time of day, which must not shift their readings. */
  @Test
  public void rhythmIsFixedOutsideStays() {
    Person person = patient(5L);
    person.attributes.put(Person.STATE, "Massachusetts");
    person.record.encounterStart(AFTERNOON, EncounterType.WELLNESS);
    ContinuousBodyTemperatureGenerator g = plain(person);
    double afternoon = g.rhythm(AFTERNOON);
    for (int h = 0; h < 24; h++) {
      assertEquals(afternoon, g.rhythm(AFTERNOON + h * HOUR), 0.0);
    }
    // the same person, once in a stay
    person.record.encounterStart(AFTERNOON + DAY, EncounterType.INPATIENT);
    assertNotEquals(g.rhythm(AFTERNOON + DAY), g.rhythm(AFTERNOON + DAY + 12 * HOUR), 1e-3);
    // and after discharge
    person.record.encounterEnd(AFTERNOON + 2 * DAY, EncounterType.INPATIENT);
    assertEquals(afternoon, g.rhythm(AFTERNOON + 3 * DAY), 0.0);
  }

  @Test
  public void olderPeopleAndMenRunCooler() {
    int people = 4000;
    double young = 0;
    double old = 0;
    double women = 0;
    double men = 0;
    for (int i = 0; i < people; i++) {
      Person a = patient(20000 + i);
      bornYearsAgo(a, 25);
      young += plain(a).getValue(AFTERNOON) / people;
      Person b = patient(20000 + i);
      bornYearsAgo(b, 85);
      old += plain(b).getValue(AFTERNOON) / people;
      Person c = patient(20000 + i);
      c.attributes.put(Person.GENDER, "F");
      women += plain(c).getValue(AFTERNOON) / people;
      Person d = patient(20000 + i);
      d.attributes.put(Person.GENDER, "M");
      men += plain(d).getValue(AFTERNOON) / people;
    }
    // the same seeds, so these are exact
    assertEquals(0.18, young - old, 2e-3);
    assertEquals(0.05, women - men, 1e-9);
  }

  @Test
  public void feverAddsItsOffsetAndEndsAfterTheCondition() {
    Person person = patient(3L);
    person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
    ContinuousBodyTemperatureGenerator before = plain(person);
    ContinuousBodyTemperatureGenerator flat = withEffects(person, true);
    person.record.conditionStart(AFTERNOON, FEVER);
    // listed offset: a fever of about 1.5 degrees
    assertEquals(1.5, flat.getValue(AFTERNOON) - before.getValue(AFTERNOON), 1e-9);
    person.record.conditionEnd(AFTERNOON + DAY, FEVER);
    person.record.encounterEnd(AFTERNOON + DAY, EncounterType.INPATIENT);
    // recovered within a few days of the condition's end
    assertEquals(0, flat.getValue(AFTERNOON + 5 * DAY) - before.getValue(AFTERNOON + 5 * DAY),
        0.01);
  }

  @Test
  public void antipyreticsBluntFeverButNotANormalTemperature() {
    int people = 1500;
    double blunted = 0;
    int treated = 0;
    for (int i = 0; i < people; i++) {
      Person fever = patient(30000 + i);
      fever.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
      ContinuousBodyTemperatureGenerator plain = plain(fever);
      ContinuousBodyTemperatureGenerator flat = withEffects(fever, true);
      fever.record.conditionStart(AFTERNOON, FEVER);
      double untreated = flat.getValue(AFTERNOON + HOUR) - plain.getValue(AFTERNOON + HOUR);
      fever.record.medicationAdministration(AFTERNOON + HOUR, ACETAMINOPHEN);
      double reduced = flat.getValue(AFTERNOON + HOUR) - plain.getValue(AFTERNOON + HOUR);
      assertTrue(reduced <= untreated + 1e-9);
      if (reduced < untreated - 1e-9) {
        treated++;
        blunted += 1 - reduced / untreated;
        // it wears off after the dose
        assertTrue(flat.getValue(AFTERNOON + 8 * HOUR) - plain.getValue(AFTERNOON + 8 * HOUR)
            > reduced);
      }

      Person afebrile = patient(30000 + i);
      afebrile.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
      ContinuousBodyTemperatureGenerator normal = withEffects(afebrile, true);
      double base = normal.getValue(AFTERNOON + HOUR);
      afebrile.record.medicationAdministration(AFTERNOON + HOUR, ACETAMINOPHEN);
      assertEquals(base, normal.getValue(AFTERNOON + HOUR), 1e-9);
    }
    assertEquals("share taking it (adherence)", 0.8, (double) treated / people, 0.04);
    assertEquals("blunting in those who do", 0.3, blunted / treated, 0.02);
  }

  /**
   * Severe sepsis: 26% at or below 36.5 (Kushimoto et al., Crit Care 2013). A cold patient stays
   * cold, and heart rate is not affected.
   */
  @Test
  public void someSepsisEpisodesRunCold() {
    int people = 2000;
    ConditionVitalSignEffects effects = ConditionVitalSignEffects.load("body_temperature");
    ConditionVitalSignEffects heart = ConditionVitalSignEffects.load("heart_rate");
    int cold = 0;
    int reversed = 0;
    double hotMean = 0;
    for (int i = 0; i < people; i++) {
      Person person = patient(40000 + i);
      person.record.encounterStart(AFTERNOON, EncounterType.EMERGENCY);
      ContinuousBodyTemperatureGenerator g = withEffects(person, false);
      person.record.conditionStart(AFTERNOON, SEPSIS);
      double first = g.getValue(AFTERNOON);
      boolean isCold = effects.reversed(person, "sepsis", AFTERNOON);
      reversed += isCold ? 1 : 0;
      cold += first <= 36.5 ? 1 : 0;
      if (isCold) {
        // still below normal hours later: the same episode, the same direction
        assertTrue(effects.offset(person, AFTERNOON + 6 * HOUR) < 0);
      } else {
        hotMean += first;
        assertTrue(effects.offset(person, AFTERNOON + 6 * HOUR) > 0);
      }
      assertTrue(heart.offset(person, AFTERNOON) > 0);
    }
    hotMean /= people - reversed;
    double coldShare = (double) cold / people;
    System.out.printf("sepsis: %.0f%% at or below 36.5, %.0f%% cold episodes, others mean %.1f%n",
        100 * coldShare, 100.0 * reversed / people, hotMean);
    assertEquals(0.25, (double) reversed / people, 0.03);
    assertEquals(0.26, coldShare, 0.05);
    assertTrue("fever " + hotMean, hotMean > 37.7 && hotMean < 38.6);
  }

  @Test
  public void staysInsideTheBoundsAtTheExtremes() {
    double highest = Double.NEGATIVE_INFINITY;
    double lowest = Double.POSITIVE_INFINITY;
    int atBound = 0;
    int readings = 0;
    for (int i = 0; i < 1500; i++) {
      Person person = patient(50000 + i);
      person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
      ContinuousBodyTemperatureGenerator g = withEffects(person, false);
      person.record.conditionStart(AFTERNOON, SEPTIC_SHOCK);
      person.record.conditionStart(AFTERNOON, SEPSIS);
      person.record.conditionStart(AFTERNOON, ARDS);
      person.record.conditionStart(AFTERNOON, FEVER);
      for (int h = 0; h < 48; h += 2) {
        double value = g.getValue(AFTERNOON + h * HOUR);
        highest = Math.max(highest, value);
        lowest = Math.min(lowest, value);
        atBound += value <= 33 || value >= 42 ? 1 : 0;
        readings++;
      }
    }
    System.out.printf("temperature at the extremes: %.1f-%.1f, %.2f%% at a bound%n", lowest,
        highest, 100.0 * atBound / readings);
    assertTrue(lowest >= 33 && highest <= 42);
    assertTrue("readings piled up at a bound", (double) atBound / readings < 0.01);
  }

  @Test
  public void recordedWithOneDecimal() {
    Person person = patient(9L);
    person.setVitalSign(VitalSign.BODY_TEMPERATURE, plain(person));
    for (int i = 0; i < 20; i++) {
      double value = person.getVitalSign(VitalSign.BODY_TEMPERATURE, AFTERNOON + i * HOUR);
      assertEquals(Math.rint(value * 10) / 10, value, 1e-9);
    }
  }
}
