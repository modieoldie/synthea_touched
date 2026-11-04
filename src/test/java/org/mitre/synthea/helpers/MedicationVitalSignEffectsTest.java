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
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.BiometricsConfig;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;

public class MedicationVitalSignEffectsTest {
  private static final long HOUR = 3_600_000L;
  private static final long DAY = 24 * HOUR;
  private static final long T0 = 1_788_000_000_000L;  // Sep 2026
  private static final double EPS = 1e-9;

  private static final String METOPROLOL = "866514";     // metoprolol tartrate 50 MG Oral Tablet
  private static final String METOPROLOL_IV = "866508";  // 5 ML metoprolol tartrate injection
  private static final String DIGOXIN = "197604";

  /** Test parameters: everyone takes the drugs and responds fully. */
  private static Map<String, Object> config() {
    Map<String, Object> classes = new LinkedHashMap<>();
    classes.put("beta_blocker", drug(-10, 0.5, 1, 1, 1, 24, 6));
    classes.put("digoxin", drug(-4, null, 1, 1, 1, 0, null));
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("combination_weight", 0.5);
    config.put("classes", classes);
    return config;
  }

  private static Map<String, Object> drug(double shift, Double blunting, double adherence,
      double responseLow, double responseHigh, double washoutHours, Integer administrationHours) {
    Map<String, Object> heartRate = new LinkedHashMap<>();
    heartRate.put("shift", shift);
    if (blunting != null) {
      heartRate.put("blunting", blunting);
    }
    Map<String, Object> drug = new LinkedHashMap<>();
    drug.put("heart_rate", heartRate);
    drug.put("adherence", adherence);
    drug.put("response", List.of(responseLow, responseHigh));
    drug.put("washout_hours", washoutHours);
    if (administrationHours != null) {
      drug.put("administration_hours", administrationHours);
    }
    return drug;
  }

  private static MedicationVitalSignEffects heartRate(Map<String, Object> config) {
    return new MedicationVitalSignEffects(config, "heart_rate",
        MedicationVitalSignEffects.drugClasses());
  }

  private static Person prescribed(long seed, long time, String code) {
    Person person = ConditionVitalSignEffectsTest.patient(seed);
    person.record.encounterStart(time, EncounterType.WELLNESS);
    person.record.medicationStart(time, code, true);
    return person;
  }

  @Test
  public void noEffectWithoutListedDrugs() {
    MedicationVitalSignEffects effects = heartRate(config());
    Person person = ConditionVitalSignEffectsTest.patient(1L);
    assertEquals(80, effects.apply(person, T0, 80, 0), 0);
    person.record.encounterStart(T0, EncounterType.WELLNESS);
    person.record.medicationStart(T0, "1665021", false);  // an antibiotic: no heart rate effect
    assertEquals(80, effects.apply(person, T0 + HOUR, 80, 0), 0);
  }

  @Test
  public void shiftsTheRestingValueWhileTaken() {
    MedicationVitalSignEffects effects = heartRate(config());
    Person person = prescribed(1L, T0, METOPROLOL);
    assertEquals(80, effects.apply(person, T0 - 1, 80, 0), 0);
    assertEquals(70, effects.apply(person, T0, 80, 0), EPS);
    assertEquals(70, effects.apply(person, T0 + 300 * DAY, 80, 0), EPS);
  }

  @Test
  public void wearsOffAfterItStops() {
    MedicationVitalSignEffects effects = heartRate(config());
    Person person = prescribed(1L, T0, METOPROLOL);
    long stop = T0 + 10 * DAY;
    person.record.medicationEnd(stop, METOPROLOL, null);
    assertEquals(70, effects.apply(person, stop - 1, 80, 0), EPS);
    assertEquals(80 - 10 * Math.exp(-1), effects.apply(person, stop + DAY, 80, 0), EPS);
  }

  @Test
  public void bluntsTheRiseFromConditions() {
    MedicationVitalSignEffects effects = heartRate(config());
    Person person = prescribed(1L, T0, METOPROLOL);
    // 80 - 10, plus half of a rise of 20
    assertEquals(80, effects.apply(person, T0 + HOUR, 80, 20), EPS);
    // a fall from conditions is not blunted
    assertEquals(60, effects.apply(person, T0 + HOUR, 80, -10), EPS);
  }

  @Test
  public void classesCombine() {
    MedicationVitalSignEffects effects = heartRate(config());
    Person person = prescribed(1L, T0, METOPROLOL);
    person.record.medicationStart(T0, DIGOXIN, true);
    // shifts sub-additively (-10, then -4 at half weight); digoxin does not blunt here
    assertEquals(80 - 10 - 2 + 0.5 * 20, effects.apply(person, T0 + HOUR, 80, 20), EPS);
  }

  @Test
  public void oneOffDoseActsForAdministrationHours() {
    MedicationVitalSignEffects effects = heartRate(config());
    Person person = ConditionVitalSignEffectsTest.patient(1L);
    person.record.encounterStart(T0, EncounterType.EMERGENCY);
    person.record.medicationAdministration(T0, METOPROLOL_IV);
    person.record.medicationAdministration(T0, DIGOXIN);  // digoxin: one-off doses ignored
    assertEquals(70, effects.apply(person, T0 + 3 * HOUR, 80, 0), EPS);
    assertEquals(80 - 10 * Math.exp(-1), effects.apply(person, T0 + 30 * HOUR, 80, 0), EPS);
  }

  @Test
  public void futurePrescriptionsDoNotChangeEarlierValues() {
    MedicationVitalSignEffects effects = heartRate(config());
    Person person = prescribed(1L, T0, METOPROLOL);
    double before = effects.apply(person, T0 + DAY, 80, 15);
    person.record.medicationStart(T0 + 2 * DAY, DIGOXIN, true);
    person.record.medicationEnd(T0 + 3 * DAY, METOPROLOL, null);
    assertEquals(before, effects.apply(person, T0 + DAY, 80, 15), 0);
  }

  @Test
  public void adherenceAndResponseAreFixedPerPerson() {
    Map<String, Object> config = config();
    @SuppressWarnings("unchecked")
    Map<String, Object> classes = (Map<String, Object>) config.get("classes");
    classes.put("beta_blocker", drug(-10, 0.5, 0.6, 0.5, 1.5, 24, null));
    MedicationVitalSignEffects effects = heartRate(config);
    int n = 20000;
    int takers = 0;
    double sumFactor = 0;
    for (int i = 0; i < n; i++) {
      double factor = effects.personalFactor(new Person(i), "beta_blocker");
      if (factor > 0) {
        takers++;
        sumFactor += factor;
        assertTrue(factor >= 0.5 && factor <= 1.5);
      }
    }
    assertEquals(0.6, takers / (double) n, 0.015);
    assertEquals(1.0, sumFactor / takers, 0.015);

    // the same person gets the same factor at every reading
    Person person = prescribed(3L, T0, METOPROLOL);
    double factor = effects.personalFactor(person, "beta_blocker");
    for (int h = 1; h < 48; h++) {
      assertEquals(80 - 10 * factor, effects.apply(person, T0 + h * HOUR, 80, 0), EPS);
    }
  }

  @Test
  public void doesNotConsumeThePersonsRandomNumbers() {
    MedicationVitalSignEffects effects = heartRate(config());
    Person person = prescribed(7L, T0, METOPROLOL);
    long before = person.getCount();
    for (int h = 0; h < 100; h++) {
      effects.apply(person, T0 + h * HOUR, 80, 10);
    }
    assertEquals(before, person.getCount());
  }

  @Test
  public void invalidParametersAreRejected() {
    assertRejected("beta_blocker", drug(-10, 1.5, 1, 1, 1, 24, null));   // blunting > 1
    assertRejected("beta_blocker", drug(-10, 0.5, 2, 1, 1, 24, null));   // adherence > 1
    assertRejected("beta_blocker", drug(-10, 0.5, 1, 1.5, 0.5, 24, null));  // response reversed
    assertRejected("beta_blocker", drug(-10, 0.5, 1, 1, 1, -1, null));   // negative washout
    assertRejected("beta_blockers", drug(-10, 0.5, 1, 1, 1, 24, null));  // no such class
  }

  private static void assertRejected(String name, Map<String, Object> drug) {
    Map<String, Object> config = config();
    @SuppressWarnings("unchecked")
    Map<String, Object> classes = (Map<String, Object>) config.get("classes");
    classes.put(name, drug);
    try {
      heartRate(config);
      fail("expected the parameters to be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void biometricsParametersLoad() {
    MedicationVitalSignEffects heartRate = MedicationVitalSignEffects.load("heart_rate");
    assertTrue(!heartRate.isEmpty());
    // no listed drug class changes respiration rate
    assertTrue(MedicationVitalSignEffects.load("respiration_rate").isEmpty());
    // a beta blocker lowers a typical resting heart rate for a person who takes it
    Person person = prescribed(1L, T0, METOPROLOL);
    double factor = heartRate.personalFactor(person, "beta_blocker");
    assertEquals(80 - 12 * factor, heartRate.apply(person, T0 + HOUR, 80, 0), EPS);
  }

  /** Drug classes by their ingredients, first match wins, as vital_sign_drug_classes.csv. */
  private static final Map<String, Pattern> CLASS_PATTERNS = new LinkedHashMap<>();

  static {
    CLASS_PATTERNS.put("ophthalmic_beta_blocker", pattern("olol.*ophthalmic|ophthalmic.*olol"));
    CLASS_PATTERNS.put("beta_blocker", pattern("olol|carvedilol|labetalol|nebivolol"));
    CLASS_PATTERNS.put("non_dhp_calcium_channel_blocker", pattern("diltiazem|verapamil"));
    CLASS_PATTERNS.put("digoxin", pattern("digoxin"));
    CLASS_PATTERNS.put("ivabradine", pattern("ivabradine"));
    CLASS_PATTERNS.put("antibiotic", pattern("cillin|\\bcef|cephalexin|mycin\\b|floxacin"
        + "|vancomycin|penem\\b|tazobactam|doxycycline|sulfamethoxazole|clavulanate"
        + "|metronidazole|linezolid|fosfomycin"));
    CLASS_PATTERNS.put("anticoagulant", pattern("heparin|enoxaparin|warfarin|apixaban"
        + "|rivaroxaban|dabigatran|fondaparinux|edoxaban"));
    CLASS_PATTERNS.put("loop_diuretic", pattern("furosemide|bumetanide|torsemide"));
    CLASS_PATTERNS.put("antipyretic", pattern("acetaminophen|ibuprofen"));
  }

  private static Pattern pattern(String regex) {
    return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
  }

  /** Primary code and display of every MedicationOrder state in the modules. */
  private static Map<String, String> moduleMedications() throws Exception {
    Map<String, String> medications = new TreeMap<>();
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
          if ("MedicationOrder".equals(s.get("type").getAsString())) {
            JsonObject code = s.getAsJsonArray("codes").get(0).getAsJsonObject();
            medications.putIfAbsent(code.get("code").getAsString(),
                code.has("display") ? code.get("display").getAsString() : "");
          }
        }
      }
    }
    return medications;
  }

  /** vital_sign_drug_classes.csv lists exactly the drugs of its classes that modules prescribe. */
  @Test
  public void drugClassesMatchTheModules() throws Exception {
    Map<String, String> expected = new TreeMap<>();
    for (Map.Entry<String, String> medication : moduleMedications().entrySet()) {
      for (Map.Entry<String, Pattern> drugClass : CLASS_PATTERNS.entrySet()) {
        if (drugClass.getValue().matcher(medication.getValue()).find()) {
          expected.put(medication.getKey(), drugClass.getKey());
          break;
        }
      }
    }
    Map<String, String> actual = new TreeMap<>(MedicationVitalSignEffects.drugClasses());
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, String> e : expected.entrySet()) {
      if (!e.getValue().equals(actual.get(e.getKey()))) {
        problems.add("module drug " + e.getKey() + " should be " + e.getValue() + ", is "
            + actual.get(e.getKey()));
      }
    }
    for (String code : actual.keySet()) {
      if (!expected.containsKey(code)) {
        problems.add("listed drug " + code + " is not prescribed by any module as its class");
      }
    }
    assertTrue(String.join("\n", problems), problems.isEmpty());
  }

  /** Every drug class the parameters name (effects and treated_by) has codes. */
  @Test
  @SuppressWarnings("unchecked")
  public void parameterClassesHaveCodes() {
    Set<String> classes = new HashSet<>(MedicationVitalSignEffects.drugClasses().values());
    Map<String, Object> medicationClasses = (Map<String, Object>)
        BiometricsConfig.get("vital_sign_medication_effects.classes");
    for (String name : medicationClasses.keySet()) {
      assertTrue(name, classes.contains(name));
    }
    Map<String, Object> effects = (Map<String, Object>)
        BiometricsConfig.get("vital_sign_condition_effects.effects");
    for (Object effect : effects.values()) {
      Object treatedBy = ((Map<String, Object>) effect).get("treated_by");
      if (treatedBy != null) {
        for (Object name : (List<Object>) treatedBy) {
          assertTrue(String.valueOf(name), classes.contains(String.valueOf(name)));
        }
      }
    }
  }
}
