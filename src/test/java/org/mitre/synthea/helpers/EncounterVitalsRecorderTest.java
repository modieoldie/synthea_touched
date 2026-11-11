package org.mitre.synthea.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.SerializationUtils;
import org.junit.Test;
import org.mitre.synthea.modules.BloodPressureValueGenerator;
import org.mitre.synthea.modules.BloodPressureValueGenerator.SysDias;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord.Code;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.HealthRecord.Observation;
import org.mitre.synthea.world.concepts.VitalSign;

public class EncounterVitalsRecorderTest {
  private static final long MINUTE = 60_000L;
  private static final long HOUR = 60 * MINUTE;
  private static final long DAY = 24 * HOUR;
  /** 2026-09-01 15:00 in Massachusetts. */
  private static final long AFTERNOON = ZonedDateTime.of(2026, 9, 1, 15, 0, 0, 0,
      ZoneId.of("America/New_York")).toInstant().toEpochMilli();
  private static final String HEART_RATE = "8867-4";
  private static final String RESPIRATION_RATE = "9279-1";
  private static final String SYSTOLIC = "8480-6";
  private static final String DIASTOLIC = "8462-4";
  private static final String TEMPERATURE = "8310-5";
  private static final String OXYGEN_SATURATION = "2708-6";
  private static final String INHALED_OXYGEN = "3150-0";
  private static final String ARDS = "67782005";
  private static final String LIFESTYLE_CARE_PLAN = "443402002";

  private static EncounterVitalsRecorder hourly(String... classes) {
    return new EncounterVitalsRecorder(HOUR, new HashSet<>(Arrays.asList(classes)),
        EnumSet.allOf(VitalSign.class));
  }

  /** The continuous generators, set up as LifecycleModule sets them up. */
  private static Map<VitalSign, ValueGenerator> generators(Person person,
      ConditionVitalSignEffects oxygenEffects) {
    Map<VitalSign, ValueGenerator> g = new EnumMap<>(VitalSign.class);
    g.put(VitalSign.HEART_RATE, new ContinuousVitalSignGenerator(person,
        VitalSign.HEART_RATE.ordinal() + 1, 60, 100,
        ContinuousVitalSignGenerator.loadConfig("cardiovascular.heart_rate.continuous")));
    g.put(VitalSign.RESPIRATION_RATE, new ContinuousVitalSignGenerator(person,
        VitalSign.RESPIRATION_RATE.ordinal() + 1, 12, 20,
        ContinuousVitalSignGenerator.loadConfig("respiratory.respiration_rate.continuous")));
    Map<String, Object> bp =
        ContinuousVitalSignGenerator.loadConfig("metabolic.blood_pressure.continuous");
    double[] systolicBounds = VitalSignEffects.loadBounds("systolic_blood_pressure");
    double[] diastolicBounds = VitalSignEffects.loadBounds("diastolic_blood_pressure");
    g.put(VitalSign.SYSTOLIC_BLOOD_PRESSURE, new ContinuousBloodPressureGenerator(person,
        SysDias.SYSTOLIC, bp, null, null, systolicBounds, diastolicBounds));
    g.put(VitalSign.DIASTOLIC_BLOOD_PRESSURE, new ContinuousBloodPressureGenerator(person,
        SysDias.DIASTOLIC, bp, null, null, systolicBounds, diastolicBounds));
    g.put(VitalSign.BODY_TEMPERATURE, new ContinuousBodyTemperatureGenerator(person,
        ContinuousVitalSignGenerator.loadConfig("cardiovascular.body_temperature.continuous"),
        null, VitalSignEffects.loadBounds("body_temperature")));
    g.put(VitalSign.OXYGEN_SATURATION, new ContinuousOxygenSaturationGenerator(person,
        ContinuousVitalSignGenerator.loadConfig("cardiovascular.oxygen_saturation.continuous"),
        oxygenEffects, VitalSignEffects.loadBounds("oxygen_saturation")));
    return g;
  }

  /** A patient on the continuous models. */
  private static Person patient(long seed, ConditionVitalSignEffects oxygenEffects) {
    Person person = ConditionVitalSignEffectsTest.patient(seed);
    generators(person, oxygenEffects).forEach((v, g) -> person.setVitalSign(v, g));
    return person;
  }

  /** A patient being recorded from now on. */
  private static Person recorded(EncounterVitalsRecorder recorder, long seed) {
    Person person = patient(seed, null);
    recorder.start(person);
    return person;
  }

  /** A 26.5 hour inpatient stay; a module took the heart rate 10 minutes in. */
  private static Encounter stay(Person person) {
    Encounter stay = person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
    Observation heartRate = person.record.observation(AFTERNOON + 10 * MINUTE, HEART_RATE, 120.0);
    heartRate.codes.add(new Code("LOINC", HEART_RATE, "Heart rate"));
    person.record.encounterEnd(AFTERNOON + 26 * HOUR + 30 * MINUTE, EncounterType.INPATIENT);
    return stay;
  }

  private static List<Long> range(long from, long to) {
    List<Long> list = new ArrayList<>();
    for (long i = from; i < to; i++) {
      list.add(i);
    }
    return list;
  }

  /** Observations with panels opened up. */
  private static List<Observation> flat(Encounter encounter) {
    List<Observation> all = new ArrayList<>();
    for (Observation o : encounter.observations) {
      all.add(o);
      all.addAll(o.observations);
    }
    return all;
  }

  /** Hours after AFTERNOON at which an encounter holds a code. */
  private static List<Long> hours(Encounter encounter, String code) {
    return flat(encounter).stream().filter(o -> o.type.equals(code))
        .map(o -> (o.start - AFTERNOON) / HOUR).collect(Collectors.toList());
  }

  private static Observation at(Encounter encounter, String code, long time) {
    return flat(encounter).stream().filter(o -> o.type.equals(code) && o.start == time)
        .findFirst().get();
  }

  /** Rounded as the recorder rounds. */
  private static double rounded(ValueGenerator generator, long time, int decimals) {
    return BigDecimal.valueOf(generator.getValue(time)).setScale(decimals, RoundingMode.HALF_UP)
        .doubleValue();
  }

  @Test
  public void recordsEveryHourThroughTheStay() {
    EncounterVitalsRecorder recorder = hourly("inpatient");
    Person person = recorded(recorder, 5L);
    Encounter stay = stay(person);
    recorder.read(person, AFTERNOON + 7 * DAY, false);
    assertEquals("held until export", 1, stay.observations.size());

    EncounterVitalsRecorder.attach(person);
    List<Long> everyHour = range(0, 27);
    assertEquals(everyHour, hours(stay, RESPIRATION_RATE));
    assertEquals(everyHour, hours(stay, SYSTOLIC));
    assertEquals(everyHour, hours(stay, DIASTOLIC));
    assertEquals(everyHour, hours(stay, TEMPERATURE));
    assertEquals(everyHour, hours(stay, OXYGEN_SATURATION));
    // the module's reading fills the first hour
    assertEquals(everyHour, hours(stay, HEART_RATE));
    assertEquals(120.0, at(stay, HEART_RATE, AFTERNOON + 10 * MINUTE).value);

    Map<VitalSign, ValueGenerator> g = person.vitalSigns;
    long t = AFTERNOON + 2 * HOUR;
    assertEquals(rounded(g.get(VitalSign.HEART_RATE), t, 0), at(stay, HEART_RATE, t).value);
    assertEquals(rounded(g.get(VitalSign.SYSTOLIC_BLOOD_PRESSURE), t, 0),
        at(stay, SYSTOLIC, t).value);
    assertEquals(rounded(g.get(VitalSign.BODY_TEMPERATURE), t, 1),
        (double) at(stay, TEMPERATURE, t).value, 1e-9);
    Observation temperature = at(stay, TEMPERATURE, t);
    assertEquals("Cel", temperature.unit);
    assertEquals("vital-signs", temperature.category);
    assertEquals("8331-1", temperature.codes.get(1).code);
    assertEquals("mm[Hg]", at(stay, SYSTOLIC, t).unit);

    // attached once
    EncounterVitalsRecorder.attach(person);
    assertEquals(27, hours(stay, RESPIRATION_RATE).size());
  }

  @Test
  public void readsOpenEncountersEachTimestep() {
    EncounterVitalsRecorder recorder = hourly("inpatient");
    Person person = recorded(recorder, 41L);
    Encounter stay = person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);

    // still open: the hour under way waits for the next timestep
    recorder.read(person, AFTERNOON + 2 * DAY + 30 * MINUTE, false);
    EncounterVitalsRecorder.attach(person);
    assertEquals(range(0, 48), hours(stay, RESPIRATION_RATE));

    person.record.encounterEnd(AFTERNOON + 3 * DAY + 30 * MINUTE, EncounterType.INPATIENT);
    recorder.read(person, AFTERNOON + 7 * DAY, false);
    EncounterVitalsRecorder.attach(person);
    assertEquals(range(0, 73), hours(stay, RESPIRATION_RATE));
    assertEquals(range(0, 73), hours(stay, SYSTOLIC));
  }

  @Test
  public void readsBloodPressureAsOfTheTime() {
    EncounterVitalsRecorder recorder = hourly("inpatient");
    Person person = recorded(recorder, 37L);
    Encounter stay = stay(person);
    ValueGenerator systolic = person.vitalSigns.get(VitalSign.SYSTOLIC_BLOOD_PRESSURE);
    ValueGenerator diastolic = person.vitalSigns.get(VitalSign.DIASTOLIC_BLOOD_PRESSURE);
    List<Double> liveSystolic = new ArrayList<>();
    List<Double> liveDiastolic = new ArrayList<>();
    for (long hour = 0; hour < 27; hour++) {
      liveSystolic.add(rounded(systolic, AFTERNOON + hour * HOUR, 0));
      liveDiastolic.add(rounded(diastolic, AFTERNOON + hour * HOUR, 0));
    }

    // after the stay: diagnosed, given a drug and a lifestyle care plan
    long later = AFTERNOON + 3 * DAY;
    person.attributes.put("hypertension", true);
    person.encounterVitals.attributeSet("hypertension", true, later);
    person.record.encounterStart(later, EncounterType.OUTPATIENT);
    String drug = BloodPressureValueGenerator.drugCodes().iterator().next();
    person.record.medicationStart(later, drug, true);
    person.record.careplanStart(later, LIFESTYLE_CARE_PLAN);
    person.record.encounterEnd(later + HOUR, EncounterType.OUTPATIENT);
    assertNotEquals("read now, the stay looks different", liveSystolic.get(2),
        rounded(systolic, AFTERNOON + 2 * HOUR, 0), 0);

    recorder.read(person, AFTERNOON + 7 * DAY, false);
    EncounterVitalsRecorder.attach(person);
    for (int hour = 0; hour < 27; hour++) {
      long t = AFTERNOON + hour * HOUR;
      assertEquals(liveSystolic.get(hour), at(stay, SYSTOLIC, t).value);
      assertEquals(liveDiastolic.get(hour), at(stay, DIASTOLIC, t).value);
    }
  }

  /** A module replaces oxygen saturation 5 hours in; LifecycleModule restores it at 20 hours. */
  private static Encounter replacedSaturation(Person person, EncounterVitalsRecorder recorder) {
    ValueGenerator model = person.vitalSigns.get(VitalSign.OXYGEN_SATURATION);
    Encounter stay = person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
    person.setVitalSign(VitalSign.OXYGEN_SATURATION,
        new RandomValueGenerator(person, 75, 89), AFTERNOON + 5 * HOUR);
    if (recorder != null) {
      recorder.read(person, AFTERNOON + 10 * HOUR, false);
    }
    person.lastUpdated = AFTERNOON + 20 * HOUR;
    person.setVitalSign(VitalSign.OXYGEN_SATURATION, model);
    person.record.encounterEnd(AFTERNOON + DAY, EncounterType.INPATIENT);
    return stay;
  }

  @Test
  public void readsAModulesReplacementWhileItIsInPlace() {
    EncounterVitalsRecorder recorder = hourly("inpatient");
    Person person = recorded(recorder, 43L);
    Person twin = recorded(recorder, 43L);
    ValueGenerator model = person.vitalSigns.get(VitalSign.OXYGEN_SATURATION);
    Encounter stay = replacedSaturation(person, recorder);
    replacedSaturation(twin, null);
    long draws = twin.getCount();

    recorder.read(person, AFTERNOON + 7 * DAY, false);
    EncounterVitalsRecorder.attach(person);
    assertEquals(range(0, 24), hours(stay, OXYGEN_SATURATION));
    for (long hour = 0; hour < 24; hour++) {
      long t = AFTERNOON + hour * HOUR;
      double value = (double) at(stay, OXYGEN_SATURATION, t).value;
      if (hour >= 5 && hour < 20) {
        assertTrue(value + " at hour " + hour, value >= 75 && value <= 89);
      } else {
        assertEquals(rounded(model, t, 2), value, 0);
      }
    }
    // the module's random values came from the recorder's own draws
    assertEquals(draws, person.getCount());
    assertEquals(twin.randLong(), person.randLong());
  }

  @Test
  public void drawsNoRandomNumbers() {
    EncounterVitalsRecorder recorder = hourly("inpatient");
    Person a = recorded(recorder, 9L);
    Person b = recorded(recorder, 9L);
    stay(a);
    stay(b);
    long before = a.getCount();
    assertEquals(before, b.getCount());
    recorder.read(a, AFTERNOON + 7 * DAY, false);
    EncounterVitalsRecorder.attach(a);
    assertEquals(before, a.getCount());
    assertEquals(b.randLong(), a.randLong());
  }

  @Test
  public void idsFollowFromTheEncounterCodeAndTime() {
    EncounterVitalsRecorder recorder = hourly("inpatient");
    List<List<UUID>> ids = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      Person person = recorded(recorder, 13L);
      Encounter stay = stay(person);
      recorder.read(person, AFTERNOON + 7 * DAY, false);
      EncounterVitalsRecorder.attach(person);
      ids.add(flat(stay).stream().map(o -> o.uuid).collect(Collectors.toList()));
    }
    assertEquals(ids.get(0), ids.get(1));
    assertEquals(ids.get(0).size(), new HashSet<>(ids.get(0)).size());
  }

  @Test
  public void stopsAtDeath() {
    EncounterVitalsRecorder recorder = hourly("inpatient");
    Person person = recorded(recorder, 17L);
    Encounter stay = stay(person);
    person.attributes.put(Person.DEATHDATE, AFTERNOON + 2 * HOUR + 30 * MINUTE);
    recorder.read(person, AFTERNOON + 7 * DAY, false);
    EncounterVitalsRecorder.attach(person);
    assertEquals(Arrays.asList(0L, 1L, 2L), hours(stay, RESPIRATION_RATE));
  }

  @Test
  public void readsOnlyTheChosenClasses() {
    EncounterVitalsRecorder recorder = hourly("inpatient");
    Person person = recorded(recorder, 19L);
    Encounter checkup = person.record.encounterStart(AFTERNOON, EncounterType.WELLNESS);
    person.record.encounterEnd(AFTERNOON + 30 * MINUTE, EncounterType.WELLNESS);
    long admitted = AFTERNOON + DAY;
    Encounter open = person.record.encounterStart(admitted, EncounterType.INPATIENT);
    recorder.read(person, admitted + 2 * HOUR + 30 * MINUTE, false);

    // at the end of the simulation, an open encounter is read up to that time
    recorder.read(person, admitted + 3 * HOUR + 1, true);
    EncounterVitalsRecorder.attach(person);
    assertTrue(checkup.observations.isEmpty());
    assertEquals(Arrays.asList(24L, 25L, 26L, 27L), hours(open, RESPIRATION_RATE));
  }

  @Test
  public void readsEncountersOpenWhenRecordingStarts() {
    EncounterVitalsRecorder recorder = hourly("inpatient");
    Person person = patient(23L, null);
    Encounter ended = stay(person);
    long admitted = AFTERNOON + 2 * DAY;
    Encounter open = person.record.encounterStart(admitted, EncounterType.INPATIENT);
    recorder.start(person);
    person.record.encounterEnd(admitted + 30 * HOUR, EncounterType.INPATIENT);
    recorder.read(person, AFTERNOON + 7 * DAY, false);
    EncounterVitalsRecorder.attach(person);
    assertEquals(1, ended.observations.size());
    assertEquals(range(48, 78), hours(open, RESPIRATION_RATE));
  }

  @Test
  public void recordingOffDropsReadingsFromASnapshot() {
    EncounterVitalsRecorder recorder = hourly("inpatient");
    Person person = recorded(recorder, 47L);
    Encounter stay = stay(person);
    recorder.read(person, AFTERNOON + 7 * DAY, false);
    // recording is off in tests, as in a run from this snapshot with it off
    EncounterVitalsRecorder.begin(person);
    assertNull(person.encounterVitals);
    EncounterVitalsRecorder.attach(person);
    assertEquals(1, stay.observations.size());
  }

  @Test
  public void runFromASnapshotCarriesOnAnOpenStay() {
    EncounterVitalsRecorder recorder = hourly("inpatient");
    Person person = recorded(recorder, 53L);
    Encounter stay = person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
    recorder.read(person, AFTERNOON + 10 * HOUR + 30 * MINUTE, true);

    Person resumed = SerializationUtils.clone(person);
    Encounter resumedStay = resumed.record.encounters.get(0);
    recorder.start(resumed);
    resumed.record.encounterEnd(AFTERNOON + 2 * DAY, EncounterType.INPATIENT);
    recorder.read(resumed, AFTERNOON + 7 * DAY, false);
    EncounterVitalsRecorder.attach(resumed);
    assertEquals(range(0, 48), hours(resumedStay, RESPIRATION_RATE));
    assertEquals(range(0, 48), hours(resumedStay, SYSTOLIC));
    assertTrue(stay.observations.isEmpty());
  }

  @Test
  public void runFromASnapshotFollowsTheSettingsNow() {
    EncounterVitalsRecorder before = new EncounterVitalsRecorder(HOUR,
        new HashSet<>(Arrays.asList("inpatient")), EnumSet.of(VitalSign.HEART_RATE));
    Person person = recorded(before, 59L);
    Encounter stay = person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
    Encounter emergency = person.record.encounterStart(AFTERNOON, EncounterType.EMERGENCY);
    before.read(person, AFTERNOON + 10 * HOUR + 30 * MINUTE, true);

    EncounterVitalsRecorder now = hourly("emergency");
    now.start(person);
    person.record.encounterEnd(AFTERNOON + 20 * HOUR, EncounterType.EMERGENCY);
    person.record.encounterEnd(AFTERNOON + 2 * DAY, EncounterType.INPATIENT);
    now.read(person, AFTERNOON + 7 * DAY, false);
    EncounterVitalsRecorder.attach(person);
    assertEquals(range(0, 11), hours(stay, HEART_RATE));
    assertTrue(hours(stay, RESPIRATION_RATE).isEmpty());
    // a class chosen now is read from its start, with every vital sign recorded now
    assertEquals(range(0, 20), hours(emergency, HEART_RATE));
    assertEquals(range(0, 20), hours(emergency, RESPIRATION_RATE));
  }

  @Test
  public void recordsInhaledOxygenWhileOnOxygen() {
    EncounterVitalsRecorder recorder = hourly("inpatient");
    ConditionVitalSignEffects effects = ConditionVitalSignEffects.load("oxygen_saturation");
    Person person = patient(29L, effects);
    recorder.start(person);
    Encounter stay = person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
    person.record.conditionStart(AFTERNOON - DAY, ARDS);
    person.record.encounterEnd(AFTERNOON + DAY, EncounterType.INPATIENT);
    recorder.read(person, AFTERNOON + 7 * DAY, false);
    EncounterVitalsRecorder.attach(person);

    ContinuousOxygenSaturationGenerator saturation = (ContinuousOxygenSaturationGenerator)
        person.vitalSigns.get(VitalSign.OXYGEN_SATURATION);
    assertEquals(24, hours(stay, OXYGEN_SATURATION).size());
    List<Long> onOxygen = hours(stay, INHALED_OXYGEN);
    assertFalse("ARDS should need oxygen", onOxygen.isEmpty());
    for (long hour = 0; hour < 24; hour++) {
      long t = AFTERNOON + hour * HOUR;
      double fio2 = Math.round(saturation.inhaledOxygen(t));
      assertEquals(fio2 > 21, onOxygen.contains(hour));
      if (fio2 > 21) {
        assertEquals(fio2, (double) at(stay, INHALED_OXYGEN, t).value, 0);
      }
    }
  }

  @Test
  public void stayIsFoundAfterLaterEncounters() {
    Person person = ConditionVitalSignEffectsTest.patient(31L);
    person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
    person.record.encounterEnd(AFTERNOON + 2 * DAY, EncounterType.INPATIENT);
    for (int i = 0; i < 25; i++) {
      long start = AFTERNOON + 3 * DAY + i * DAY;
      person.record.encounterStart(start, EncounterType.WELLNESS);
      person.record.encounterEnd(start + 30 * MINUTE, EncounterType.WELLNESS);
    }
    assertTrue(VitalSignTime.inStay(person, AFTERNOON + HOUR));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsAnUnknownClass() {
    hourly("inpatient", "hospital");
  }
}
