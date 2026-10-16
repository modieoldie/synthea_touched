package org.mitre.synthea.helpers;

import static org.junit.Assert.assertEquals;
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
    config.put("bounds", Map.of("heart_rate", List.of(30, 220),
        "respiration_rate", List.of(6, 60)));
    config.put("effects", effects);
    return config;
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

  /** Start an encounter and record conditions in it. */
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
    ConditionVitalSignEffects effects = heartRate();
    assertEquals(80, effects.apply(person, T0, 80), 0);
    admit(person, T0, SEPSIS);
    assertEquals(105, effects.apply(person, T0, 80), EPS);
    assertEquals(220, effects.apply(person, T0, 210), 0);
    Person slow = patient(2L);
    admit(slow, T0, SLOWING);
    assertEquals(30, effects.apply(slow, T0, 40), 0);
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

    Map<String, Object> bounds = config();
    bounds.put("bounds", Map.of("respiration_rate", List.of(6, 60)));
    assertRejected(bounds);

    Map<String, Object> maxDays = config();
    @SuppressWarnings("unchecked")
    Map<String, Object> effects2 = (Map<String, Object>) maxDays.get("effects");
    effects2.put("instant", effect(List.of("999000002"), 10, null, 0));
    assertRejected(maxDays);
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
    ConditionVitalSignEffects heartRate = ConditionVitalSignEffects.load("heart_rate");
    ConditionVitalSignEffects respirationRate = ConditionVitalSignEffects.load("respiration_rate");
    // the bounds with conditions contain the normal ranges
    double[] heartNormal = BiometricsConfig.doubles("cardiovascular.heart_rate.normal");
    double[] respirationNormal = BiometricsConfig.doubles("respiratory.respiration_rate.normal");
    assertTrue(heartRate.getMinimumValue() <= heartNormal[0]
        && heartRate.getMaximumValue() >= heartNormal[1]);
    assertTrue(respirationRate.getMinimumValue() <= respirationNormal[0]
        && respirationRate.getMaximumValue() >= respirationNormal[1]);
    // sepsis takes a typical patient past the SIRS cut-offs
    Person person = patient(1L);
    admit(person, T0, SEPSIS);
    assertTrue(80 + heartRate.offset(person, T0) > 90);
    assertTrue(14 + respirationRate.offset(person, T0) > 20);
  }

  /**
   * Every code in biometrics.yml must be the primary code of a ConditionOnset state in some
   * module; otherwise it can never match (a typo, or a module that changed its codes).
   */
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
