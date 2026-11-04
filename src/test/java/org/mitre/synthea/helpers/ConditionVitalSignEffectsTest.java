package org.mitre.synthea.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;
import org.mitre.synthea.world.agents.PayerManager;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.BiometricsConfig;
import org.mitre.synthea.world.concepts.HealthRecord;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.geography.Location;

public class ConditionVitalSignEffectsTest {
  private static final long HOUR = 3_600_000L;
  private static final long DAY = 24 * HOUR;
  private static final long T0 = 1_788_000_000_000L;  // Sep 2026
  private static final double EPS = 1e-9;

  private static final String SEPSIS = "91302008";
  private static final String SEPSIS_VIRUS = "770349000";
  private static final String PNEUMONIA = "233604007";
  private static final String CHF = "88805009";
  private static final String AF = "49436004";
  private static final String SLOWING = "999000001";  // made-up code with a negative effect
  private static final String DIABETES = "44054006";  // no effect

  /** Test parameters, independent of the placeholder values in biometrics.yml. */
  private static Map<String, Object> config() {
    Map<String, Object> effects = new LinkedHashMap<>();
    effects.put("sepsis", effect(List.of(SEPSIS, SEPSIS_VIRUS), 25, 7, 14));
    effects.put("pneumonia", effect(List.of(PNEUMONIA), 15, 6, 14));
    effects.put("chronic_heart_failure", effect(List.of(CHF), 5, 2, null));
    effects.put("atrial_fibrillation", effect(List.of(AF), 12, null, null));
    effects.put("slowing", effect(List.of(SLOWING), -20, null, null));
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("recovery_hours", 12);
    config.put("combination_weight", 0.5);
    config.put("effects", effects);
    return config;
  }

  private static final String ANTIBIOTIC = "1665021";   // ceftriaxone 1000 MG Injection
  private static final String BETA_BLOCKER = "866514";  // metoprolol tartrate 50 MG Oral Tablet

  /** config() with a course for pneumonia (treated by antibiotics) and sepsis (from onset). */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> courseConfig() {
    Map<String, Object> config = config();
    config.put("untreated_factor", 3);
    config.put("treatment_window_hours", 24);
    Map<String, Object> effects = (Map<String, Object>) config.get("effects");
    Map<String, Object> pneumonia = (Map<String, Object>) effects.get("pneumonia");
    pneumonia.put("resolution_hours", Map.of("heart_rate", 24, "respiration_rate", 48));
    pneumonia.put("residual", 0.2);
    pneumonia.put("treated_by", List.of("antibiotic"));
    ((Map<String, Object>) effects.get("sepsis")).put("resolution_hours", 10);
    return config;
  }

  private static ConditionVitalSignEffects withCourse(String vitalSign) {
    return new ConditionVitalSignEffects(courseConfig(), vitalSign);
  }

  private static Map<String, Object> effect(List<String> codes, Integer heartRate,
      Integer respirationRate, Integer maxDays) {
    Map<String, Object> effect = new LinkedHashMap<>();
    effect.put("codes", codes);
    if (heartRate != null) {
      effect.put("heart_rate", heartRate);
    }
    if (respirationRate != null) {
      effect.put("respiration_rate", respirationRate);
    }
    if (maxDays != null) {
      effect.put("max_days", maxDays);
    }
    return effect;
  }

  private static boolean payersLoaded = false;

  /** A person whose record can hold encounters (their claims need a birth date and a plan). */
  static synchronized Person patient(long seed) {
    if (!payersLoaded) {
      PayerManager.loadPayers(new Location("Massachusetts", null));
      payersLoaded = true;
    }
    Person person = new Person(seed);
    person.attributes.put(Person.BIRTHDATE, 0L);
    person.coverage.setPlanToNoInsurance(0L);
    person.coverage.setPlanToNoInsurance(Utilities.convertTime("years", 100));  // covered till then
    return person;
  }

  private static ConditionVitalSignEffects heartRate() {
    return new ConditionVitalSignEffects(config(), "heart_rate");
  }

  private static Encounter admit(Person person, long time, String... codes) {
    Encounter encounter = person.record.encounterStart(time, EncounterType.EMERGENCY);
    for (String code : codes) {
      person.record.conditionStart(time, code);
    }
    return encounter;
  }

  private static double decayed(double offset, double hours) {
    return offset * Math.exp(-hours / 12);
  }

  /** A share of episodes act with the reversal offset, per vital sign, fixed for the episode. */
  @SuppressWarnings("unchecked")
  @Test
  public void reversalActsOnAShareOfEpisodes() {
    Map<String, Object> config = new LinkedHashMap<>(config());
    Map<String, Object> effects = new LinkedHashMap<>((Map<String, Object>) config.get("effects"));
    Map<String, Object> sepsis = new LinkedHashMap<>((Map<String, Object>) effects.get("sepsis"));
    sepsis.put("reversal", Map.of("heart_rate", Map.of("share", 0.25, "offset", -5)));
    effects.put("sepsis", sepsis);
    config.put("effects", effects);
    ConditionVitalSignEffects heart = new ConditionVitalSignEffects(config, "heart_rate", false,
        false, MedicationVitalSignEffects.drugClasses());
    ConditionVitalSignEffects respiration = new ConditionVitalSignEffects(config,
        "respiration_rate", false, false, MedicationVitalSignEffects.drugClasses());
    double listed = ((Number) sepsis.get("heart_rate")).doubleValue();
    double listedRespiration = ((Number) sepsis.get("respiration_rate")).doubleValue();
    int reversed = 0;
    int people = 2000;
    long t0 = 1_000_000_000_000L;
    for (int i = 0; i < people; i++) {
      Person person = patient(i);
      person.record.encounterStart(t0, EncounterType.EMERGENCY);
      person.record.conditionStart(t0, "91302008");
      double offset = heart.offset(person, t0);
      boolean isReversed = heart.reversed(person, "sepsis", t0);
      assertEquals(isReversed ? -5 : listed, offset, 1e-9);
      assertEquals(offset, heart.offset(person, t0 + 3_600_000L), 1e-9);
      // a vital sign without a reversal is not affected
      assertEquals(listedRespiration, respiration.offset(person, t0), 1e-9);
      reversed += isReversed ? 1 : 0;
    }
    assertEquals(0.25, (double) reversed / people, 0.03);
  }

  @Test
  public void noOffsetWithoutListedConditions() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = heartRate();
    assertEquals(0, effects.offset(person, T0), 0);
    admit(person, T0, DIABETES);
    assertEquals(0, effects.offset(person, T0 + DAY), 0);
  }

  @Test
  public void activeConditionAddsItsOffsetFromOnset() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = heartRate();
    admit(person, T0, SEPSIS);
    assertEquals(0, effects.offset(person, T0 - 1), 0);
    assertEquals(25, effects.offset(person, T0), EPS);
    assertEquals(25, effects.offset(person, T0 + 3 * DAY), EPS);  // encounter still open
  }

  @Test
  public void offsetDecaysAfterTheConditionEnds() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = heartRate();
    admit(person, T0, SEPSIS);
    long end = T0 + 2 * DAY;
    person.record.conditionEnd(end, SEPSIS);
    assertEquals(25, effects.offset(person, end - 1), EPS);
    assertEquals(25, effects.offset(person, end), EPS * 25);
    assertEquals(decayed(25, 12), effects.offset(person, end + 12 * HOUR), EPS);
    assertEquals(0, effects.offset(person, end + 10 * DAY), 1e-6);
  }

  /** Synthea simulates from birth, so many conditions start and end before 1970 (time < 0). */
  @Test
  public void conditionThatEndedBefore1970HasEnded() {
    Person person = patient(1L);
    person.attributes.put(Person.BIRTHDATE, -30 * 365 * DAY);
    person.coverage.setPlanToNoInsurance(-30 * 365 * DAY);
    ConditionVitalSignEffects effects = heartRate();
    long onset = -10 * 365 * DAY;  // 1960
    long end = onset + 200 * DAY;
    admit(person, onset, CHF).end(onset + HOUR);
    person.record.conditionEnd(end, CHF);
    assertEquals(5, effects.offset(person, end - 1), EPS);
    assertEquals(decayed(5, 12), effects.offset(person, end + 12 * HOUR), EPS);
    assertEquals(0, effects.offset(person, T0), 0);
  }

  @Test
  public void acuteConditionEndsWithItsEncounter() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = heartRate();
    Encounter stay = admit(person, T0, SEPSIS);  // sepsis is never ended, as in sepsis.json
    long discharge = T0 + 4 * DAY;
    stay.end(discharge);
    assertEquals(25, effects.offset(person, discharge - HOUR), EPS);
    assertEquals(decayed(25, 12), effects.offset(person, discharge + 12 * HOUR), EPS);
  }

  @Test
  public void encounterThatEndedBeforeOnsetDoesNotEndTheCondition() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = heartRate();
    Encounter checkup = person.record.encounterStart(T0 - 30 * DAY, EncounterType.WELLNESS);
    checkup.end(T0 - 30 * DAY + HOUR);
    person.record.conditionStart(T0, SEPSIS);  // recorded in the old checkup
    assertEquals(1, checkup.conditions.size());
    assertEquals(25, effects.offset(person, T0 + 5 * DAY), EPS);
    // but an acute effect lasts at most max_days (14)
    assertEquals(25, effects.offset(person, T0 + 14 * DAY - 1), EPS);
    assertEquals(decayed(25, 12), effects.offset(person, T0 + 14 * DAY + 12 * HOUR), EPS);
  }

  @Test
  public void acuteConditionLastsAtMostMaxDays() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = heartRate();
    admit(person, T0, SEPSIS);  // neither the condition nor the encounter ever ends
    assertEquals(decayed(25, 24), effects.offset(person, T0 + 15 * DAY), EPS);
  }

  @Test
  public void chronicConditionIsNotCapped() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = heartRate();
    Encounter visit = admit(person, T0, CHF);
    visit.end(T0 + HOUR);
    assertEquals(5, effects.offset(person, T0 + 5 * 365 * DAY), EPS);
  }

  @Test
  public void effectsCombineSubadditively() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = heartRate();
    admit(person, T0, PNEUMONIA, SEPSIS);
    assertEquals(25 + 15 / 2.0, effects.offset(person, T0 + HOUR), EPS);
    person.record.conditionStart(T0 + 2 * HOUR, CHF);
    assertEquals(25 + 15 / 2.0 + 5 / 4.0, effects.offset(person, T0 + 3 * HOUR), EPS);
  }

  @Test
  public void codesOfOneEffectDoNotAddUp() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = heartRate();
    admit(person, T0, SEPSIS, SEPSIS_VIRUS);
    assertEquals(25, effects.offset(person, T0 + HOUR), EPS);
    // an ended sepsis decaying under an active one counts as the stronger of the two
    person.record.conditionEnd(T0 + 2 * HOUR, SEPSIS);
    assertEquals(25, effects.offset(person, T0 + 5 * HOUR), EPS);
  }

  @Test
  public void largestEffectCountsFullyWhateverItsSign() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = heartRate();
    admit(person, T0, AF, SLOWING);
    assertEquals(-20 + 12 / 2.0, effects.offset(person, T0 + HOUR), EPS);
  }

  @Test
  public void eachVitalSignUsesItsOwnColumn() {
    Person person = patient(1L);
    ConditionVitalSignEffects respiration = new ConditionVitalSignEffects(config(),
        "respiration_rate");
    admit(person, T0, AF);
    assertEquals(0, respiration.offset(person, T0 + HOUR), 0);  // no respiration_rate entry
    person.record.conditionStart(T0 + HOUR, SEPSIS);
    assertEquals(7, respiration.offset(person, T0 + 2 * HOUR), EPS);
  }

  @Test
  public void valueIsBounded() {
    Person person = patient(1L);
    VitalSignEffects effects = new VitalSignEffects(heartRate(), null, 30, 220);
    assertEquals(80, effects.apply(person, T0, 80), 0);
    admit(person, T0, SEPSIS);
    assertEquals(105, effects.apply(person, T0, 80), EPS);
    assertEquals(220, effects.apply(person, T0, 210), 0);
    Person slow = patient(2L);
    admit(slow, T0, SLOWING);
    assertEquals(30, effects.apply(slow, T0, 40), 0);
  }

  @Test
  public void untreatedEffectResolvesSlowly() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = withCourse("heart_rate");
    admit(person, T0, PNEUMONIA);
    assertEquals(15, effects.offset(person, T0), EPS);
    // no antibiotic recorded: time constant 3 * 24 hours, towards a residual of 0.2
    double remaining = Math.exp(-48.0 / 72);
    assertEquals(15 * (0.2 + 0.8 * remaining), effects.offset(person, T0 + 48 * HOUR), EPS);
  }

  @Test
  public void treatmentSpeedsUpResolutionWithoutAJump() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = withCourse("heart_rate");
    admit(person, T0, PNEUMONIA);
    long treated = T0 + 6 * HOUR;
    person.record.medicationStart(treated, ANTIBIOTIC, false);
    double untreatedPart = 6.0 / 72;
    assertEquals(15 * (0.2 + 0.8 * Math.exp(-untreatedPart)), effects.offset(person, treated),
        EPS);
    assertEquals(effects.offset(person, treated - 1), effects.offset(person, treated), 1e-4);
    double remaining = Math.exp(-untreatedPart - 30.0 / 24);
    assertEquals(15 * (0.2 + 0.8 * remaining), effects.offset(person, T0 + 36 * HOUR), EPS);
  }

  @Test
  public void treatmentStartedBeforeDiagnosisCountsFromOnset() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = withCourse("heart_rate");
    person.record.encounterStart(T0 - 12 * HOUR, EncounterType.EMERGENCY);
    person.record.medicationStart(T0 - 12 * HOUR, ANTIBIOTIC, false);
    person.record.conditionStart(T0, PNEUMONIA);
    assertEquals(15 * (0.2 + 0.8 * Math.exp(-1)), effects.offset(person, T0 + 24 * HOUR), EPS);
  }

  @Test
  public void otherDrugsOrOldTreatmentDoNotCount() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = withCourse("heart_rate");
    person.record.encounterStart(T0 - 30 * DAY, EncounterType.EMERGENCY);
    person.record.medicationStart(T0 - 30 * DAY, ANTIBIOTIC, false);  // a month before onset
    admit(person, T0, PNEUMONIA);
    person.record.medicationStart(T0, BETA_BLOCKER, false);         // not an antibiotic
    double untreated = 15 * (0.2 + 0.8 * Math.exp(-48.0 / 72));
    assertEquals(untreated, effects.offset(person, T0 + 48 * HOUR), EPS);
  }

  @Test
  public void effectWithoutTreatedByResolvesFromOnset() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = withCourse("heart_rate");
    admit(person, T0, SEPSIS);
    assertEquals(25 * Math.exp(-2), effects.offset(person, T0 + 20 * HOUR), EPS);
  }

  @Test
  public void eachVitalSignResolvesAtItsOwnPace() {
    Person person = patient(1L);
    ConditionVitalSignEffects heart = withCourse("heart_rate");
    ConditionVitalSignEffects respiration = withCourse("respiration_rate");
    admit(person, T0, PNEUMONIA);
    person.record.medicationStart(T0, ANTIBIOTIC, false);
    assertEquals(15 * (0.2 + 0.8 * Math.exp(-2)), heart.offset(person, T0 + 48 * HOUR), EPS);
    assertEquals(6 * (0.2 + 0.8 * Math.exp(-1)), respiration.offset(person, T0 + 48 * HOUR), EPS);
  }

  @Test
  public void recoveryAfterTheEndContinuesFromTheResolvedLevel() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = withCourse("heart_rate");
    Encounter stay = admit(person, T0, PNEUMONIA);
    person.record.medicationStart(T0, ANTIBIOTIC, false);
    long discharge = T0 + 48 * HOUR;
    stay.end(discharge);
    double atDischarge = 15 * (0.2 + 0.8 * Math.exp(-2));
    assertEquals(atDischarge, effects.offset(person, discharge - 1), 1e-4);
    assertEquals(atDischarge, effects.offset(person, discharge), EPS);
    assertEquals(decayed(atDischarge, 12), effects.offset(person, discharge + 12 * HOUR), EPS);
  }

  @Test
  public void courseOffKeepsTheFullOffset() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = new ConditionVitalSignEffects(courseConfig(), "heart_rate",
        false, false, MedicationVitalSignEffects.drugClasses());
    admit(person, T0, PNEUMONIA);
    assertEquals(15, effects.offset(person, T0 + 72 * HOUR), EPS);
  }

  /** The course is timed from onset and treatment, never from the end of the stay. */
  @Test
  public void dischargeTimeDoesNotChangeReadingsBeforeIt() {
    ConditionVitalSignEffects effects = withCourse("heart_rate");
    Person early = patient(1L);
    Person late = patient(1L);
    Encounter earlyStay = admit(early, T0, PNEUMONIA, SEPSIS);
    Encounter lateStay = admit(late, T0, PNEUMONIA, SEPSIS);
    early.record.medicationStart(T0 + 2 * HOUR, ANTIBIOTIC, false);
    late.record.medicationStart(T0 + 2 * HOUR, ANTIBIOTIC, false);
    earlyStay.end(T0 + 2 * DAY);
    lateStay.end(T0 + 5 * DAY);
    for (long t = T0 - HOUR; t < T0 + 2 * DAY; t += HOUR / 4) {
      assertEquals(effects.offset(early, t), effects.offset(late, t), 0);
    }
    assertTrue(effects.offset(early, T0 + 3 * DAY) < effects.offset(late, T0 + 3 * DAY));
  }

  @Test
  public void severityScalesEachEpisodeAndIsSharedByVitalSigns() {
    Map<String, Object> config = config();
    config.put("severity_sd", 0.4);
    ConditionVitalSignEffects heart = new ConditionVitalSignEffects(config, "heart_rate");
    ConditionVitalSignEffects respiration =
        new ConditionVitalSignEffects(config, "respiration_rate");
    Person person = patient(5L);
    admit(person, T0, SEPSIS);
    double factor = heart.severity(person, "sepsis", T0);
    assertEquals(factor, respiration.severity(person, "sepsis", T0), 0);
    assertEquals(25 * factor, heart.offset(person, T0 + HOUR), EPS);
    assertEquals(7 * factor, respiration.offset(person, T0 + HOUR), EPS);
    // another episode of the same effect draws again
    assertNotEquals(factor, heart.severity(person, "sepsis", T0 + 400 * DAY), 1e-9);

    // log-normal with mean 1 and log-SD 0.4 across episodes
    int n = 20000;
    double sum = 0;
    double sumLog2 = 0;
    for (int i = 0; i < n; i++) {
      double f = heart.severity(new Person(i), "sepsis", T0);
      sum += f;
      sumLog2 += Math.log(f) * Math.log(f);
    }
    assertEquals(1, sum / n, 0.02);
    assertEquals(0.4, Math.sqrt(sumLog2 / n - Math.pow(-0.08, 2)), 0.02);
  }

  @Test
  public void severityOffGivesTheListedOffset() {
    Map<String, Object> config = config();
    config.put("severity_sd", 0.4);
    ConditionVitalSignEffects effects = new ConditionVitalSignEffects(config, "heart_rate", true,
        false, MedicationVitalSignEffects.drugClasses());
    Person person = patient(5L);
    admit(person, T0, SEPSIS);
    assertEquals(25, effects.offset(person, T0 + HOUR), EPS);
  }

  @Test
  public void entriesAddedToAnOlderEncounterAreSeen() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = withCourse("heart_rate");
    VitalSignRecordIndex index = new VitalSignRecordIndex(effects.conditionCodes(),
        effects.treatmentCodes());
    Encounter stay = person.record.encounterStart(T0, EncounterType.INPATIENT);
    person.record.encounterStart(T0 + DAY, EncounterType.AMBULATORY);
    index.update(person);
    assertEquals(0, effects.offset(person, index, T0 + 2 * DAY), 0);
    // a module that rewound time records into the stay, not into the newest encounter
    person.record.conditionStart(T0 + HOUR, PNEUMONIA);
    person.record.medicationStart(T0 + 2 * HOUR, ANTIBIOTIC, false);
    assertEquals(1, stay.conditions.size());
    index.update(person);
    assertEquals(effects.offset(person, T0 + 2 * DAY), effects.offset(person, index, T0 + 2 * DAY),
        0);
    assertTrue(effects.offset(person, index, T0 + 2 * DAY) > 0);
  }

  @Test
  public void offsetDependsOnlyOnTheRecordUpToThatTime() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = heartRate();
    Encounter stay = admit(person, T0, SEPSIS);
    long[] times = {T0 - HOUR, T0, T0 + 6 * HOUR, T0 + DAY, T0 + 2 * DAY};
    double[] before = new double[times.length];
    for (int i = 0; i < times.length; i++) {
      before[i] = effects.offset(person, times[i]);
    }
    // later events: a new condition, the end of sepsis and of the stay
    person.record.conditionStart(T0 + 3 * DAY, PNEUMONIA);
    person.record.conditionEnd(T0 + 4 * DAY, SEPSIS);
    stay.end(T0 + 5 * DAY);
    for (int i = times.length - 1; i >= 0; i--) {
      assertEquals(before[i], effects.offset(person, times[i]), 0);
    }
  }

  @Test
  public void conditionsInEveryHealthRecordCount() {
    Person person = patient(1L);
    ConditionVitalSignEffects effects = heartRate();
    person.lossOfCareRecord = new HealthRecord(person);
    person.lossOfCareRecord.encounterStart(T0, EncounterType.EMERGENCY);
    person.lossOfCareRecord.conditionStart(T0, SEPSIS);
    assertEquals(25, effects.offset(person, T0 + HOUR), EPS);
  }

  @Test
  public void invalidParametersAreRejected() {
    Map<String, Object> duplicate = config();
    @SuppressWarnings("unchecked")
    Map<String, Object> effects = (Map<String, Object>) duplicate.get("effects");
    effects.put("pneumonia_again", effect(List.of(PNEUMONIA), 10, 5, 14));
    assertRejected(duplicate);

    Map<String, Object> weight = config();
    weight.put("combination_weight", 1.5);
    assertRejected(weight);

    Map<String, Object> maxDays = config();
    @SuppressWarnings("unchecked")
    Map<String, Object> effects2 = (Map<String, Object>) maxDays.get("effects");
    effects2.put("instant", effect(List.of("999000002"), 10, null, 0));
    assertRejected(maxDays);

    // only acute effects (with max_days) resolve
    Map<String, Object> chronicCourse = config();
    effectOf(chronicCourse, "chronic_heart_failure").put("resolution_hours", 24);
    assertRejected(chronicCourse);

    Map<String, Object> unknownClass = courseConfig();
    effectOf(unknownClass, "pneumonia").put("treated_by", List.of("antibiotics"));
    assertRejected(unknownClass);

    Map<String, Object> treatedWithoutCourse = config();
    effectOf(treatedWithoutCourse, "pneumonia").put("treated_by", List.of("antibiotic"));
    assertRejected(treatedWithoutCourse);

    Map<String, Object> residual = courseConfig();
    effectOf(residual, "pneumonia").put("residual", 1);
    assertRejected(residual);

    Map<String, Object> untreated = courseConfig();
    untreated.put("untreated_factor", 0.5);
    assertRejected(untreated);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> effectOf(Map<String, Object> config, String name) {
    return (Map<String, Object>) ((Map<String, Object>) config.get("effects")).get(name);
  }

  private static void assertRejected(Map<String, Object> config) {
    try {
      new ConditionVitalSignEffects(config, "heart_rate");
      fail("expected the parameters to be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void biometricsParametersLoad() {
    ConditionVitalSignEffects.load("heart_rate");
    ConditionVitalSignEffects.load("respiration_rate");
    // the bounds with effects contain the normal ranges
    double[] heartBounds = VitalSignEffects.loadBounds("heart_rate");
    double[] respirationBounds = VitalSignEffects.loadBounds("respiration_rate");
    double[] heartNormal = BiometricsConfig.doubles("cardiovascular.heart_rate.normal");
    double[] respirationNormal = BiometricsConfig.doubles("respiratory.respiration_rate.normal");
    assertTrue(heartBounds[0] <= heartNormal[0] && heartBounds[1] >= heartNormal[1]);
    assertTrue(respirationBounds[0] <= respirationNormal[0]
        && respirationBounds[1] >= respirationNormal[1]);
    // sepsis of typical severity takes a typical patient past the SIRS cut-offs at onset
    ConditionVitalSignEffects heartRate = ConditionVitalSignEffects.load("heart_rate", true, false);
    ConditionVitalSignEffects respirationRate =
        ConditionVitalSignEffects.load("respiration_rate", true, false);
    Person person = patient(1L);
    admit(person, T0, SEPSIS);
    assertTrue(80 + heartRate.offset(person, T0) > 90);
    assertTrue(14 + respirationRate.offset(person, T0) > 20);
  }

  /** Every code in biometrics.yml must be the primary code of a ConditionOnset state. */
  @Test
  @SuppressWarnings("unchecked")
  public void biometricsCodesAreConditionsInModules() throws Exception {
    Map<String, Object> effects = (Map<String, Object>)
        BiometricsConfig.get("vital_sign_condition_effects.effects");
    Set<String> onsetCodes = new HashSet<>();
    Path modules = Paths.get(ClassLoader.getSystemResource("modules").toURI());
    List<Path> files;
    try (Stream<Path> walk = Files.walk(modules)) {
      files = walk.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList());
    }
    for (Path file : files) {
      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        JsonObject states = JsonParser.parseReader(reader).getAsJsonObject()
            .getAsJsonObject("states");
        if (states == null) {
          continue;
        }
        for (Map.Entry<String, JsonElement> state : states.entrySet()) {
          JsonObject s = state.getValue().getAsJsonObject();
          if ("ConditionOnset".equals(s.get("type").getAsString())) {
            onsetCodes.add(s.getAsJsonArray("codes").get(0).getAsJsonObject()
                .get("code").getAsString());
          }
        }
      }
    }
    List<String> unknown = new ArrayList<>();
    for (Map.Entry<String, Object> effect : effects.entrySet()) {
      for (Object code : (List<Object>) ((Map<String, Object>) effect.getValue()).get("codes")) {
        if (!onsetCodes.contains(String.valueOf(code))) {
          unknown.add(effect.getKey() + ": " + code);
        }
      }
    }
    assertTrue("codes no module onsets: " + unknown, unknown.isEmpty());
  }
}
