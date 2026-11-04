package org.mitre.synthea.modules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.regex.Pattern;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mitre.synthea.helpers.ConstantValueGenerator;
import org.mitre.synthea.helpers.ContinuousBloodPressureGenerator;
import org.mitre.synthea.helpers.ContinuousBodyTemperatureGenerator;
import org.mitre.synthea.helpers.ContinuousOxygenSaturationGenerator;
import org.mitre.synthea.helpers.ContinuousVitalSignGenerator;
import org.mitre.synthea.helpers.MedicationVitalSignEffects;
import org.mitre.synthea.helpers.PhysiologyValueGenerator;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.helpers.ValueGenerator;
import org.mitre.synthea.helpers.VitalSignEffects;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.VitalSign;

public class LifecycleModuleTest {
  public static boolean deathByNaturalCauses;

  @BeforeClass
  public static void before() {
    deathByNaturalCauses = LifecycleModule.ENABLE_DEATH_BY_NATURAL_CAUSES;
  }

  @AfterClass
  public static void after() {
    LifecycleModule.ENABLE_DEATH_BY_NATURAL_CAUSES = deathByNaturalCauses;
  }

  @Test
  public void testDeathByNaturalCauses() {
    LifecycleModule.ENABLE_DEATH_BY_NATURAL_CAUSES = true;
    Person person = new Person(0L);
    long time = System.currentTimeMillis();
    long birth = time - Utilities.convertTime("years", 100);
    person.attributes.put(Person.BIRTHDATE, birth);
    for (int i = 0; i < 100000; i++) {
      LifecycleModule.death(person, time);
    }
    assertEquals(false, person.alive(time));
  }

  /**
   * A generator loaded from a population snapshot may carry older parameters: the lifecycle
   * replaces any continuous generator, but leaves a value a module set explicitly alone.
   */
  @Test
  public void continuousVitalSignIsRebuiltWithCurrentParameters() {
    Map<String, Object> config =
        ContinuousVitalSignGenerator.loadConfig("cardiovascular.heart_rate.continuous");
    Person person = new Person(3L);
    long time = System.currentTimeMillis();
    ContinuousVitalSignGenerator stale = new ContinuousVitalSignGenerator(person,
        VitalSign.HEART_RATE.ordinal() + 1, 60, 100, config);
    person.setVitalSign(VitalSign.HEART_RATE, stale);
    VitalSignEffects effects = new VitalSignEffects(null,
        MedicationVitalSignEffects.load("heart_rate"), 30, 220);
    LifecycleModule.setContinuousVitalSign(person, VitalSign.HEART_RATE,
        new double[] {60, 100}, config, effects);
    ValueGenerator current = person.vitalSigns.get(VitalSign.HEART_RATE);
    assertNotSame(stale, current);
    assertTrue(current.toString().contains("MedicationVitalSignEffects"));
    // same parameters for the healthy path: the same values
    assertEquals(stale.getValue(time), current.getValue(time), 0);

    person.setVitalSign(VitalSign.HEART_RATE, 72.0);  // as a VitalSign state would
    LifecycleModule.setContinuousVitalSign(person, VitalSign.HEART_RATE,
        new double[] {60, 100}, config, effects);
    assertTrue(person.vitalSigns.get(VitalSign.HEART_RATE) instanceof ConstantValueGenerator);
  }

  /**
   * The continuous blood pressure model replaces the stock generators (a snapshot person's),
   * leaves a value a module set alone, adds a mean arterial pressure that matches the recorded
   * values, and marks the person for sepsis.json.
   */
  @Test
  public void continuousBloodPressureReplacesTheStockGenerators() {
    Map<String, Object> config =
        ContinuousVitalSignGenerator.loadConfig("metabolic.blood_pressure.continuous");
    double[] systolicBounds = VitalSignEffects.loadBounds("systolic_blood_pressure");
    double[] diastolicBounds = VitalSignEffects.loadBounds("diastolic_blood_pressure");
    Person person = new Person(3L);
    long time = System.currentTimeMillis();
    person.setVitalSign(VitalSign.SYSTOLIC_BLOOD_PRESSURE,
        new BloodPressureValueGenerator(person, BloodPressureValueGenerator.SysDias.SYSTOLIC));
    person.setVitalSign(VitalSign.DIASTOLIC_BLOOD_PRESSURE, 75.0);  // as a VitalSign state would
    LifecycleModule.setContinuousBloodPressure(person, config, null, null, systolicBounds,
        diastolicBounds);
    assertTrue(person.vitalSigns.get(VitalSign.SYSTOLIC_BLOOD_PRESSURE)
        instanceof ContinuousBloodPressureGenerator);
    assertTrue(person.vitalSigns.get(VitalSign.DIASTOLIC_BLOOD_PRESSURE)
        instanceof ConstantValueGenerator);
    assertEquals(true,
        person.attributes.get(LifecycleModule.CONTINUOUS_BLOOD_PRESSURE_ATTRIBUTE));

    person.vitalSigns.remove(VitalSign.DIASTOLIC_BLOOD_PRESSURE);
    LifecycleModule.setContinuousBloodPressure(person, config, null, null, systolicBounds,
        diastolicBounds);
    double systolic = person.getVitalSign(VitalSign.SYSTOLIC_BLOOD_PRESSURE, time);
    double diastolic = person.getVitalSign(VitalSign.DIASTOLIC_BLOOD_PRESSURE, time);
    assertTrue(systolic + "/" + diastolic, systolic > diastolic);
    assertEquals(Math.round((systolic + 2 * diastolic) / 3),
        person.getVitalSign(VitalSign.MEAN_ARTERIAL_PRESSURE, time), 0);
  }

  /** Marks the person for modules, rebuilds a stale generator, leaves a module's value alone. */
  @Test
  public void continuousBodyTemperatureIsSetForTheModules() {
    Map<String, Object> config =
        ContinuousVitalSignGenerator.loadConfig("cardiovascular.body_temperature.continuous");
    double[] bounds = VitalSignEffects.loadBounds("body_temperature");
    Person person = new Person(3L);
    person.attributes.put(Person.BIRTHDATE, 0L);
    long time = System.currentTimeMillis();
    LifecycleModule.setContinuousBodyTemperature(person, config, null, bounds);
    assertEquals(true,
        person.attributes.get(LifecycleModule.CONTINUOUS_BODY_TEMPERATURE_ATTRIBUTE));
    ValueGenerator first = person.vitalSigns.get(VitalSign.BODY_TEMPERATURE);
    assertTrue(first instanceof ContinuousBodyTemperatureGenerator);
    LifecycleModule.setContinuousBodyTemperature(person, config, null, bounds);
    assertNotSame(first, person.vitalSigns.get(VitalSign.BODY_TEMPERATURE));
    assertEquals(first.getValue(time),
        person.vitalSigns.get(VitalSign.BODY_TEMPERATURE).getValue(time), 0);

    person.setVitalSign(VitalSign.BODY_TEMPERATURE, 38.5);  // as a VitalSign state would
    LifecycleModule.setContinuousBodyTemperature(person, config, null, bounds);
    assertEquals(38.5, person.getVitalSign(VitalSign.BODY_TEMPERATURE, time), 0);
  }

  /**
   * Like the stock value, replaced at every timestep: what the COVID-19 modules set lasts until
   * then, whereas leaving it alone would keep it for life.
   */
  @Test
  public void continuousOxygenSaturationReplacesAModuleValueAtTheNextTimestep() {
    Map<String, Object> config =
        ContinuousVitalSignGenerator.loadConfig("cardiovascular.oxygen_saturation.continuous");
    double[] bounds = VitalSignEffects.loadBounds("oxygen_saturation");
    Person person = new Person(3L);
    person.attributes.put(Person.BIRTHDATE, 0L);
    long time = System.currentTimeMillis();
    person.setVitalSign(VitalSign.OXYGEN_SATURATION, 80.0);  // as a VitalSign state would
    LifecycleModule.setContinuousOxygenSaturation(person, config, null, bounds);
    assertTrue(person.vitalSigns.get(VitalSign.OXYGEN_SATURATION)
        instanceof ContinuousOxygenSaturationGenerator);
    assertEquals(true,
        person.attributes.get(LifecycleModule.CONTINUOUS_OXYGEN_SATURATION_ATTRIBUTE));
    assertEquals(21, person.getVitalSign(VitalSign.INHALED_OXYGEN, time), 0);
    assertTrue(person.getVitalSign(VitalSign.OXYGEN_SATURATION, time) > 85);
  }

  /** Their ordinals seed the continuous heart and respiration rate. */
  @Test
  public void newVitalSignsDoNotMoveTheOrdinalsOfOlderOnes() {
    assertEquals(26, VitalSign.HEART_RATE.ordinal());
    assertEquals(27, VitalSign.RESPIRATION_RATE.ordinal());
    assertEquals(28, VitalSign.MEAN_ARTERIAL_PRESSURE.ordinal());
  }

  @Test
  public void testLikelihoodOfDeathInputs() {
    // should handle zero to very old
    for (int age = 0; age < 100; age++) {
      double likelihood = LifecycleModule.likelihoodOfDeath(age);
      Assert.assertTrue(likelihood >= 0);
    }
  }

  @Test
  public void testAdherenceFade() {
    Person person = new Person(0L);
    long time = System.currentTimeMillis();
    person.attributes.put(Person.ADHERENCE, true);
    person.attributes.put(LifecycleModule.ADHERENCE_PROBABILITY, 1.0);
    for (int i = 0; i < 5; i++) {
      double before = (Double) person.attributes.get(LifecycleModule.ADHERENCE_PROBABILITY);
      LifecycleModule.adherence(person, time);
      double after = (Double) person.attributes.get(LifecycleModule.ADHERENCE_PROBABILITY);
      Assert.assertTrue(after < before);
    }
  }

  @Test
  public void testPercentileForBMI() {
    double percentile = LifecycleModule.percentileForBMI(18.37736191, "M", 26);
    Assert.assertEquals(0.9, percentile, 0.01);
  }

  @Test
  public void lookupGrowthChart() {
    // Uncomment to check performance.
    // long start = System.currentTimeMillis();
    // for (int i = 0; i < 1000000; i++) {
    double height = LifecycleModule.lookupGrowthChart("height", "M", 24, 0.5);
    Assert.assertEquals(86.86160934, height, 0.01);
    // }
    // long end = System.currentTimeMillis();
    // System.out.println("Time to complete: " + (end - start));
  }

  @Test
  public void lookupHeadCircumference() {
    double head = LifecycleModule.lookupGrowthChart("head", "F", 36, 0.9);
    Assert.assertEquals(50.57, head, 0.01);
  }

  @Test
  public void testPhysiologyEnabled() {
    boolean enablePhysiology = LifecycleModule.ENABLE_PHYSIOLOGY_GENERATORS;
    LifecycleModule.ENABLE_PHYSIOLOGY_GENERATORS = true;
    Person person = new Person(0L);

    // Need to set some attributes for birth to work properly
    person.attributes.put(Person.GENDER, "F");
    person.attributes.put(Person.RACE, "white");
    person.attributes.put(Person.ETHNICITY, "english");

    LifecycleModule.birth(person, 0);

    // Person should have some PhysiologyValueGenerators
    Assert.assertEquals(person.vitalSigns.get(VitalSign.SYSTOLIC_BLOOD_PRESSURE).getClass(),
        PhysiologyValueGenerator.class);
    Assert.assertEquals(person.vitalSigns.get(VitalSign.DIASTOLIC_BLOOD_PRESSURE).getClass(),
        PhysiologyValueGenerator.class);

    LifecycleModule.ENABLE_PHYSIOLOGY_GENERATORS = enablePhysiology;
  }

  @Test
  public void testPassportFormatIsAlwaysEightDigits() throws Exception {
    Pattern eightDigitPassport = Pattern.compile("^X\\d{8}X$");
    int sampleSize = 2000;
    int issued = 0;

    java.lang.reflect.Method ageMethod = LifecycleModule.class.getDeclaredMethod(
        "age", Person.class, long.class);
    ageMethod.setAccessible(true);

    for (int i = 0; i < sampleSize; i++) {
      Person person = new Person(i);
      person.attributes.put(Person.GENDER, "F");
      person.attributes.put(Person.RACE, "white");
      person.attributes.put(Person.ETHNICITY, "english");
      long birth = 0L;
      LifecycleModule.birth(person, birth);

      long candidateTime = birth + Utilities.convertTime("years", 20);
      while (person.ageInYears(candidateTime) < 20) {
        candidateTime += Utilities.convertTime("days", 1);
      }

      ageMethod.invoke(null, person, candidateTime);

      String passport = (String) person.attributes.get(Person.IDENTIFIER_PASSPORT);
      if (passport != null) {
        issued++;
        Assert.assertTrue("Passport not 8 digits: " + passport,
            eightDigitPassport.matcher(passport).matches());
      }
    }
    Assert.assertTrue("Expected at least some passports to be issued", issued > 0);
  }
}
