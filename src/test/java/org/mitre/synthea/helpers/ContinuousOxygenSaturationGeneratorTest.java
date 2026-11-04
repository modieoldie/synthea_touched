package org.mitre.synthea.helpers;

import static org.junit.Assert.assertEquals;
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
import org.mitre.synthea.helpers.ContinuousOxygenSaturationGenerator.InhaledOxygen;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord.Code;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.VitalSign;

public class ContinuousOxygenSaturationGeneratorTest {
  private static final long HOUR = 3_600_000L;
  private static final long DAY = 24 * HOUR;
  private static final long YEAR = 365L * DAY;
  private static final ZoneId EASTERN = ZoneId.of("America/New_York");
  /** 2026-09-01 15:00 in Massachusetts. */
  private static final long AFTERNOON = ZonedDateTime.of(2026, 9, 1, 15, 0, 0, 0, EASTERN)
      .toInstant().toEpochMilli();
  private static final String CHRONIC_HEART_FAILURE = "88805009";
  private static final String COPD = "185086009";
  private static final String ARDS = "67782005";
  private static final String RESPIRATORY_FAILURE = "65710008";
  private static final String HYPOXEMIA = "389087006";

  private static Map<String, Object> config() {
    return ContinuousVitalSignGenerator.loadConfig("cardiovascular.oxygen_saturation.continuous");
  }

  private static double[] bounds() {
    return VitalSignEffects.loadBounds("oxygen_saturation");
  }

  private static ContinuousOxygenSaturationGenerator plain(Person person) {
    return new ContinuousOxygenSaturationGenerator(person, config(), null, bounds());
  }

  private static ContinuousOxygenSaturationGenerator withEffects(Person person) {
    return new ContinuousOxygenSaturationGenerator(person, config(),
        ConditionVitalSignEffects.load("oxygen_saturation"), bounds());
  }

  private static Person patient(long seed) {
    return ConditionVitalSignEffectsTest.patient(seed);
  }

  /** Person caches the birth date as a LocalDate the first time it computes an age. */
  private static void bornYearsAgo(Person person, int years) {
    person.attributes.put(Person.BIRTHDATE, AFTERNOON - years * YEAR);
    person.attributes.remove(Person.BIRTHDATE_AS_LOCALDATE);
  }

  private static double quantile(double[] x, double q) {
    double[] sorted = x.clone();
    Arrays.sort(sorted);
    return sorted[(int) Math.min(sorted.length - 1, Math.floor(q * sorted.length))];
  }

  /** An encounter with a chronic condition as its reason, as a Synthea admission for it. */
  private static Encounter admit(Person person, long time, EncounterType type, String reason) {
    Encounter encounter = person.record.encounterStart(time, type);
    encounter.reason = new Code("SNOMED-CT", reason, reason);
    return encounter;
  }

  @Test
  public void valueDependsOnlyOnTime() {
    ContinuousOxygenSaturationGenerator a = plain(patient(42L));
    ContinuousOxygenSaturationGenerator b = plain(patient(42L));
    double[] forward = new double[72];
    for (int i = 0; i < 72; i++) {
      forward[i] = a.getValue(AFTERNOON + i * HOUR);
    }
    for (int i = 71; i >= 0; i--) {
      b.getValue(AFTERNOON + i * HOUR + 17 * 60_000L);
      assertEquals(forward[i], b.getValue(AFTERNOON + i * HOUR), 0.0);
    }
  }

  @Test
  public void drawsNothingFromThePerson() {
    Person person = patient(7L);
    admit(person, AFTERNOON, EncounterType.INPATIENT, CHRONIC_HEART_FAILURE);
    person.record.conditionStart(AFTERNOON, ARDS);
    ContinuousOxygenSaturationGenerator g = withEffects(person);
    long before = person.getCount();
    int attributes = person.attributes.size();
    for (int i = 0; i < 100; i++) {
      g.getValue(AFTERNOON + i * HOUR);
      g.inhaledOxygen(AFTERNOON + i * HOUR);
    }
    assertEquals(before, person.getCount());
    assertEquals(attributes, person.attributes.size());
  }

  /** As in a population snapshot. */
  @Test
  public void survivesSerialization() throws Exception {
    Person person = patient(11L);
    admit(person, AFTERNOON, EncounterType.INPATIENT, COPD);
    person.record.conditionStart(AFTERNOON, COPD);
    ContinuousOxygenSaturationGenerator g = withEffects(person);
    double value = g.getValue(AFTERNOON + 5 * HOUR);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(g);
    }
    try (ObjectInputStream in = new ObjectInputStream(
        new ByteArrayInputStream(bytes.toByteArray()))) {
      ContinuousOxygenSaturationGenerator copy =
          (ContinuousOxygenSaturationGenerator) in.readObject();
      assertEquals(value, copy.getValue(AFTERNOON + 5 * HOUR), 0.0);
    }
  }

  @Test
  public void readingsAreWholeNumbersAtMostOneHundred() {
    int atCeiling = 0;
    int readings = 0;
    for (int i = 0; i < 300; i++) {
      ContinuousOxygenSaturationGenerator g = plain(patient(100 + i));
      for (int h = 0; h < 48; h++) {
        double value = g.getValue(AFTERNOON + h * HOUR);
        assertEquals(Math.rint(value), value, 0.0);
        assertTrue(value <= 100);
        atCeiling += value == 100 ? 1 : 0;
        readings++;
      }
    }
    assertTrue("readings piled up at 100: " + atCeiling, (double) atCeiling / readings < 0.1);
  }

  /** Healthy awake adults read about 95-99, a little lower with age. */
  @Test
  public void healthyAdultsReadInTheNormalRange() {
    int people = 5000;
    double[] young = new double[people];
    double[] old = new double[people];
    for (int i = 0; i < people; i++) {
      Person a = patient(1000 + i);
      bornYearsAgo(a, 30);
      young[i] = plain(a).getValue(AFTERNOON + i * HOUR);
      Person b = patient(1000 + i);
      bornYearsAgo(b, 80);
      old[i] = plain(b).getValue(AFTERNOON + i * HOUR);
    }
    double youngMean = Arrays.stream(young).average().orElse(0);
    double oldMean = Arrays.stream(old).average().orElse(0);
    long youngLow = Arrays.stream(young).filter(v -> v < 94).count();
    long oldLow = Arrays.stream(old).filter(v -> v < 92).count();
    System.out.printf("SpO2, healthy: age 30 mean %.1f (5th-95th %.0f-%.0f), age 80 mean %.1f "
        + "(5th-95th %.0f-%.0f)%n", youngMean, quantile(young, 0.05), quantile(young, 0.95),
        oldMean, quantile(old, 0.05), quantile(old, 0.95));
    assertEquals(97.5, youngMean, 0.5);
    assertTrue(quantile(young, 0.05) >= 95);
    assertTrue(quantile(young, 0.95) <= 100);
    assertTrue("older people read lower", oldMean < youngMean - 0.4);
    assertTrue(youngLow < people * 0.01);
    assertTrue(oldLow < people * 0.01);
  }

  /** The stock value is below 90 for everyone with heart failure, always. */
  @Test
  public void stableHeartFailureIsNotHypoxemic() {
    int people = 3000;
    int below90 = 0;
    double mean = 0;
    for (int i = 0; i < people; i++) {
      Person person = patient(2000 + i);
      person.record.encounterStart(AFTERNOON - 30 * DAY, EncounterType.AMBULATORY);
      person.record.conditionStart(AFTERNOON - 30 * DAY, CHRONIC_HEART_FAILURE);
      person.record.encounterEnd(AFTERNOON - 30 * DAY + HOUR, EncounterType.AMBULATORY);
      person.record.encounterStart(AFTERNOON, EncounterType.AMBULATORY);
      double value = withEffects(person).getValue(AFTERNOON);
      below90 += value < 90 ? 1 : 0;
      mean += value / people;
    }
    System.out.printf("SpO2, stable heart failure: mean %.1f, %.1f%% below 90%n", mean,
        100.0 * below90 / people);
    assertTrue("below 90: " + below90, (double) below90 / people < 0.02);
    assertTrue("mean " + mean, mean > 94 && mean < 97.5);
  }

  /**
   * An acute MI cohort read 92 on average in Killip class 2 and 85 in class 3 (Rev Esp Cardiol
   * 2012). An admission for heart failure arrives near 92, some of them below the module's oxygen
   * threshold of 90; the first reading is on room air, so the module's branch still sees it.
   */
  @Test
  public void heartFailureAdmissionArrivesLowOnRoomAir() {
    int people = 3000;
    double[] arrival = new double[people];
    int below90 = 0;
    for (int i = 0; i < people; i++) {
      Person person = patient(3000 + i);
      person.record.encounterStart(AFTERNOON - 30 * DAY, EncounterType.AMBULATORY);
      person.record.conditionStart(AFTERNOON - 30 * DAY, CHRONIC_HEART_FAILURE);
      person.record.encounterEnd(AFTERNOON - 30 * DAY + HOUR, EncounterType.AMBULATORY);
      admit(person, AFTERNOON, EncounterType.INPATIENT, CHRONIC_HEART_FAILURE);
      ContinuousOxygenSaturationGenerator g = withEffects(person);
      arrival[i] = g.getValue(AFTERNOON);
      assertEquals(arrival[i], g.roomAirValue(AFTERNOON), 0.0);
      below90 += arrival[i] < 90 ? 1 : 0;
    }
    double median = quantile(arrival, 0.5);
    double share = (double) below90 / people;
    System.out.printf("SpO2, heart failure admission: median %.0f, %.0f%% below 90%n", median,
        100 * share);
    assertTrue("median " + median, median >= 91 && median <= 94);
    assertTrue("below 90: " + share, share > 0.1 && share < 0.4);
  }

  @Test
  public void oxygenStartsDuringTheEncounterAndStopsAtDischarge() {
    int people = 1000;
    double arrival = 0;
    double twoHours = 0;
    double roomAirTwoHours = 0;
    double fio2Arrival = 0;
    double fio2TwoHours = 0;
    for (int i = 0; i < people; i++) {
      Person person = patient(4000 + i);
      person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
      person.record.conditionStart(AFTERNOON, RESPIRATORY_FAILURE);
      ContinuousOxygenSaturationGenerator g = withEffects(person);
      person.setVitalSign(VitalSign.OXYGEN_SATURATION, g);
      InhaledOxygen fio2 = new InhaledOxygen(person);
      arrival += g.getValue(AFTERNOON) / people;
      fio2Arrival += fio2.getValue(AFTERNOON) / people;
      twoHours += g.getValue(AFTERNOON + 2 * HOUR) / people;
      roomAirTwoHours += g.roomAirValue(AFTERNOON + 2 * HOUR) / people;
      fio2TwoHours += fio2.getValue(AFTERNOON + 2 * HOUR) / people;
      person.record.encounterEnd(AFTERNOON + 2 * DAY, EncounterType.INPATIENT);
      // no oxygen outside the encounter
      assertEquals(21, fio2.getValue(AFTERNOON + 3 * DAY), 0.0);
      assertEquals(g.roomAirValue(AFTERNOON + 3 * DAY), g.getValue(AFTERNOON + 3 * DAY), 0.0);
    }
    System.out.printf("SpO2, respiratory failure: arrival %.1f on FiO2 %.0f, two hours %.1f on "
        + "FiO2 %.0f (%.1f on room air)%n", arrival, fio2Arrival, twoHours, fio2TwoHours,
        roomAirTwoHours);
    assertEquals("arrival is on room air", 21, fio2Arrival, 0.5);
    assertTrue("oxygen lifts the reading", twoHours > roomAirTwoHours + 3);
    assertTrue("towards the target", twoHours > 90 && twoHours < 96);
    assertTrue("oxygen concentration rises", fio2TwoHours > 25 && fio2TwoHours < 60);
  }

  @Test
  public void healthyPeopleStayOnRoomAirInHospital() {
    for (int i = 0; i < 300; i++) {
      Person person = patient(5000 + i);
      person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
      ContinuousOxygenSaturationGenerator g = plain(person);
      for (int h = 0; h < 72; h += 3) {
        assertEquals(21, g.inhaledOxygen(AFTERNOON + h * HOUR), 0.0);
      }
    }
  }

  /** On oxygen means a real flow, about 2 L/min or more, never a trickle of 22%. */
  @Test
  public void oxygenIsNeverATrickle() {
    int onOxygen = 0;
    for (int i = 0; i < 400; i++) {
      Person person = patient(5500 + i);
      person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
      person.record.conditionStart(AFTERNOON, RESPIRATORY_FAILURE);
      ContinuousOxygenSaturationGenerator g = withEffects(person);
      for (int h = 3; h < 72; h += 3) {
        double fio2 = g.inhaledOxygen(AFTERNOON + h * HOUR);
        assertTrue("oxygen concentration " + fio2, fio2 == 21 || fio2 >= 22);
        onOxygen += fio2 > 21 ? 1 : 0;
      }
    }
    assertTrue(onOxygen > 0);
  }

  @Test
  public void moduleSetValueGivesRoomAir() {
    Person person = patient(6L);
    person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
    person.setVitalSign(VitalSign.OXYGEN_SATURATION, 80.0);
    assertEquals(21, new InhaledOxygen(person).getValue(AFTERNOON + 5 * HOUR), 0.0);
  }

  /** Lower at night in a stay, and not at all outside one, as a person's visits share an hour. */
  @Test
  public void lowerAtNightOnlyDuringStays() {
    int people = 1500;
    double day = 0;
    double night = 0;
    double outpatientAtNight = 0;
    double outpatientAtDay = 0;
    for (int i = 0; i < people; i++) {
      Person person = patient(6000 + i);
      person.attributes.put(Person.STATE, "Massachusetts");
      person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
      ContinuousOxygenSaturationGenerator g = plain(person);
      // AFTERNOON is 15:00 local, +12 h is 03:00
      day += g.roomAirValue(AFTERNOON + DAY) / people;
      night += g.roomAirValue(AFTERNOON + DAY + 12 * HOUR) / people;
      Person outpatient = patient(6000 + i);
      outpatient.attributes.put(Person.STATE, "Massachusetts");
      outpatient.record.encounterStart(AFTERNOON, EncounterType.WELLNESS);
      ContinuousOxygenSaturationGenerator h = plain(outpatient);
      outpatientAtDay += h.getValue(AFTERNOON + DAY) / people;
      outpatientAtNight += h.getValue(AFTERNOON + DAY + 12 * HOUR) / people;
    }
    System.out.printf("SpO2, stay: day %.2f, night %.2f; outside: %.2f, %.2f%n", day, night,
        outpatientAtDay, outpatientAtNight);
    assertTrue("night " + night + " day " + day, night < day - 0.6 && night > day - 2);
    assertEquals(outpatientAtDay, outpatientAtNight, 0.15);
  }

  @Test
  public void staysInsideTheBoundsAtTheExtremes() {
    double lowest = 100;
    double lowestAfterTitration = 100;
    for (int i = 0; i < 800; i++) {
      Person person = patient(7000 + i);
      admit(person, AFTERNOON, EncounterType.INPATIENT, CHRONIC_HEART_FAILURE);
      person.record.conditionStart(AFTERNOON, ARDS);
      person.record.conditionStart(AFTERNOON, RESPIRATORY_FAILURE);
      person.record.conditionStart(AFTERNOON, HYPOXEMIA);
      ContinuousOxygenSaturationGenerator g = withEffects(person);
      for (int h = 0; h < 48; h += 2) {
        double value = g.getValue(AFTERNOON + h * HOUR);
        assertTrue(value >= 50 && value <= 100);
        lowest = Math.min(lowest, value);
        if (h >= 6) {
          lowestAfterTitration = Math.min(lowestAfterTitration, value);
        }
      }
    }
    System.out.printf("SpO2 at the extremes: lowest %.0f at arrival, %.0f on oxygen%n", lowest,
        lowestAfterTitration);
    assertTrue(lowest < 80);
    assertTrue("oxygen keeps most of the gap closed", lowestAfterTitration > lowest);
  }
}
