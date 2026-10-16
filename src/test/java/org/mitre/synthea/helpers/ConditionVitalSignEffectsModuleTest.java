package org.mitre.synthea.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.withSettings;

import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mitre.synthea.TestHelper;
import org.mitre.synthea.engine.Module;
import org.mitre.synthea.world.agents.PayerManager;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.agents.Provider;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.HealthRecord.Entry;
import org.mitre.synthea.world.concepts.HealthRecord.Observation;
import org.mitre.synthea.world.concepts.VitalSign;
import org.mitre.synthea.world.geography.Location;
import org.mockito.Mockito;

/**
 * Runs a module through the engine: the heart rate its Observation states record while the
 * person has sepsis carries the sepsis offset.
 */
public class ConditionVitalSignEffectsModuleTest {
  private static final long HOUR = 3_600_000L;
  private static final String SEPSIS = "91302008";

  private Person person;
  private long time;

  /** Same setup as StateTest. */
  @Before
  public void setup() throws Exception {
    person = new Person(0L);
    person.attributes.put(Person.GENDER, "F");
    person.attributes.put(Person.FIRST_LANGUAGE, "spanish");
    person.attributes.put(Person.RACE, "other");
    person.attributes.put(Person.ETHNICITY, "hispanic");
    person.attributes.put(Person.INCOME, Integer.parseInt(Config
        .get("generate.demographics.socioeconomic.income.poverty")) * 2);
    person.attributes.put(Person.OCCUPATION_LEVEL, 1.0);
    Provider mock = Mockito.mock(Provider.class, withSettings().serializable());
    Mockito.when(mock.getResourceID()).thenReturn("Mock-UUID");
    person.setProvider(EncounterType.AMBULATORY, mock);
    person.setProvider(EncounterType.WELLNESS, mock);
    person.setProvider(EncounterType.EMERGENCY, mock);
    person.setProvider(EncounterType.INPATIENT, mock);
    time = TestHelper.timestamp(2026, 9, 1, 0, 0, 0);
    person.attributes.put(Person.BIRTHDATE, time - Utilities.convertTime("years", 55));
    String testStateDefault = Config.get("test_state.default", "Massachusetts");
    PayerManager.loadPayers(new Location(testStateDefault, null));
    person.coverage.setPlanToNoInsurance((long) person.attributes.get(Person.BIRTHDATE));
    person.coverage.setPlanToNoInsurance(time + Utilities.convertTime("years", 20));
  }

  @Test
  public void heartRateRecordedDuringSepsisCarriesTheOffset() throws Exception {
    Map<String, Object> config =
        ContinuousVitalSignGenerator.loadConfig("cardiovascular.heart_rate.continuous");
    ConditionVitalSignEffects effects = ConditionVitalSignEffects.load("heart_rate");
    ContinuousVitalSignGenerator healthy =
        new ContinuousVitalSignGenerator(person, 1, 60, 100, config);
    person.setVitalSign(VitalSign.HEART_RATE,
        new ContinuousVitalSignGenerator(person, 1, 60, 100, config, effects));

    Module module = TestHelper.getFixture("sepsis_hourly_vitals.json");
    long t = time;
    while (!module.process(person, t)) {
      t += HOUR;
      assertTrue("module did not finish", t < time + 48 * HOUR);
    }

    assertEquals(1, person.record.encounters.size());
    Encounter stay = person.record.encounters.get(0);
    assertTrue(stay.ended);
    Entry sepsis = stay.conditions.get(0);
    assertEquals(SEPSIS, sepsis.type);
    assertEquals(0, sepsis.stop);  // never ended, as in sepsis.json
    double offset = effects.offset(person, sepsis.start);
    assertTrue(offset > 0);

    assertEquals(24, stay.observations.size());
    double meanShift = 0;
    for (Observation reading : stay.observations) {
      double recorded = ((Number) reading.value).doubleValue();
      double expected = healthy.getValue(reading.start) + offset;
      assertEquals("reading at " + reading.start, expected, recorded, 0.5);  // rounded to 1/min
      meanShift += (recorded - healthy.getValue(reading.start)) / stay.observations.size();
    }
    assertEquals(offset, meanShift, 0.5);

    // sepsis ends with the stay: three days after discharge the heart rate is back to healthy
    long later = stay.stop + 72 * HOUR;
    assertEquals(healthy.getValue(later),
        person.vitalSigns.get(VitalSign.HEART_RATE).getValue(later), 0.1);
  }
}
