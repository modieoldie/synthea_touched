package org.mitre.synthea.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.mitre.synthea.modules.BloodPressureValueGenerator;
import org.mitre.synthea.modules.BloodPressureValueGenerator.SysDias;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.VitalSign;

public class ContinuousBloodPressureGeneratorTest {
  private static final long HOUR = 3_600_000L;
  private static final long DAY = 24 * HOUR;
  private static final ZoneId EASTERN = ZoneId.of("America/New_York");
  /** 2026-09-01 15:00 in Massachusetts (19:00 UTC). */
  private static final long AFTERNOON = local(2026, 9, 1, 15);
  private static final String SEPTIC_SHOCK = "76571007";
  private static final String SEPSIS = "91302008";
  private static final String LISINOPRIL = "314076";
  private static final String PIPERACILLIN_TAZOBACTAM = "1659131";
  private static final String HYPERTENSION_CAREPLAN = "443402002";

  private static long local(int year, int month, int day, int hour) {
    return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, EASTERN).toInstant().toEpochMilli();
  }

  private static Map<String, Object> config() {
    return ContinuousVitalSignGenerator.loadConfig("metabolic.blood_pressure.continuous");
  }

  private static ContinuousBloodPressureGenerator generator(Person person, SysDias sysDias,
      ConditionVitalSignEffects systolic, ConditionVitalSignEffects diastolic) {
    return new ContinuousBloodPressureGenerator(person, sysDias, config(), systolic, diastolic,
        VitalSignEffects.loadBounds("systolic_blood_pressure"),
        VitalSignEffects.loadBounds("diastolic_blood_pressure"));
  }

  /** The person's systolic and diastolic generators without condition effects. */
  private static ContinuousBloodPressureGenerator[] plain(Person person) {
    return new ContinuousBloodPressureGenerator[] {
        generator(person, SysDias.SYSTOLIC, null, null),
        generator(person, SysDias.DIASTOLIC, null, null)};
  }

  /** With the condition effects of biometrics.yml, as listed (no course or severity) or not. */
  private static ContinuousBloodPressureGenerator[] withEffects(Person person, boolean flat) {
    ConditionVitalSignEffects s = ConditionVitalSignEffects.load("systolic_blood_pressure",
        !flat, !flat);
    ConditionVitalSignEffects d = ConditionVitalSignEffects.load("diastolic_blood_pressure",
        !flat, !flat);
    return new ContinuousBloodPressureGenerator[] {
        generator(person, SysDias.SYSTOLIC, s, d), generator(person, SysDias.DIASTOLIC, s, d)};
  }

  private static Person patient(long seed) {
    return ConditionVitalSignEffectsTest.patient(seed);
  }

  private static double mean(double[] x) {
    return Arrays.stream(x).average().orElse(Double.NaN);
  }

  private static double sd(double[] x) {
    double m = mean(x);
    return Math.sqrt(Arrays.stream(x).map(v -> (v - m) * (v - m)).sum() / (x.length - 1));
  }

  private static double correlation(double[] x, double[] y) {
    double mx = mean(x);
    double my = mean(y);
    double sxy = 0;
    for (int i = 0; i < x.length; i++) {
      sxy += (x[i] - mx) * (y[i] - my);
    }
    return sxy / (x.length - 1) / (sd(x) * sd(y));
  }

  @Test
  public void valueDependsOnlyOnTime() {
    ContinuousBloodPressureGenerator[] a = plain(patient(42L));
    ContinuousBloodPressureGenerator[] b = plain(patient(42L));
    double[][] forward = new double[2][72];
    for (int i = 0; i < 72; i++) {
      forward[0][i] = a[0].getValue(AFTERNOON + i * HOUR);
      forward[1][i] = a[1].getValue(AFTERNOON + i * HOUR);
    }
    // a second pair queried backwards, diastolic first, with extra queries in between
    for (int i = 71; i >= 0; i--) {
      b[1].getValue(AFTERNOON + i * HOUR + 17 * 60_000L);
      assertEquals(forward[1][i], b[1].getValue(AFTERNOON + i * HOUR), 0.0);
      assertEquals(forward[0][i], b[0].getValue(AFTERNOON + i * HOUR), 0.0);
    }
  }

  @Test
  public void drawsNothingFromThePersonAndKeepsNothing() {
    Person person = patient(7L);
    person.attributes.put("hypertension", true);
    person.record.encounterStart(AFTERNOON, EncounterType.WELLNESS);
    person.record.medicationStart(AFTERNOON, LISINOPRIL, true);
    person.record.careplanStart(AFTERNOON, HYPERTENSION_CAREPLAN);
    person.record.encounterStart(AFTERNOON + DAY, EncounterType.EMERGENCY);
    person.record.conditionStart(AFTERNOON + DAY, SEPTIC_SHOCK);
    ContinuousBloodPressureGenerator[] g = withEffects(person, false);
    long before = person.getCount();
    for (int i = 0; i < 100; i++) {
      g[0].getValue(AFTERNOON + i * HOUR);
      g[1].getValue(AFTERNOON + i * HOUR);
    }
    assertEquals(before, person.getCount());
    for (String key : person.attributes.keySet()) {
      assertFalse(key, key.startsWith("bp_baseline") || key.startsWith("htn_"));
    }
  }

  /** A snapshot made with the stock generator keeps its baseline and drug responses. */
  @Test
  public void reusesWhatTheStockGeneratorKept() {
    Person person = patient(3L);
    person.record.encounterStart(AFTERNOON, EncounterType.WELLNESS);
    person.record.medicationStart(AFTERNOON, LISINOPRIL, true);
    ContinuousBloodPressureGenerator[] before = plain(person);
    double statelessResting = before[0].restingLevel(SysDias.SYSTOLIC, AFTERNOON);
    new BloodPressureValueGenerator(person, SysDias.SYSTOLIC).getValue(AFTERNOON);
    double baseline = (Double) person.attributes.get("bp_baseline_false_SYSTOLIC");
    @SuppressWarnings("unchecked")
    double impact = ((com.google.common.collect.Table<String, SysDias, Double>)
        person.attributes.get("htn_drug_impacts")).get(LISINOPRIL, SysDias.SYSTOLIC);
    ContinuousBloodPressureGenerator[] after = plain(person);
    assertEquals(baseline + impact, after[0].restingLevel(SysDias.SYSTOLIC, AFTERNOON), 1e-9);
    assertNotEquals(statelessResting, baseline + impact, 1e-6);
  }

  /**
   * Readings at one time of day are centered on the resting level, with the spread of the stock
   * variation over a day (SD 8 systolic, 5 diastolic). The stock generator's sign depends only on
   * the UTC time of day, so all its readings at one time of day are off the same way (a person's
   * timestep events, such as wellness visits, all fall at one time of day).
   */
  @Test
  public void readingsAtOneTimeOfDayAreCenteredOnTheRestingLevel() {
    int people = 40;
    int readings = 300;
    double[] sysSd = new double[people];
    double[] diaSd = new double[people];
    double[] sysOffset = new double[people];
    double[] diaOffset = new double[people];
    double[] correlations = new double[people];
    double[] stockOffset = new double[people];
    int uncapped = 0;
    for (int p = 0; p < people; p++) {
      Person person = patient(1000L + p);
      ContinuousBloodPressureGenerator[] g = plain(person);
      double restingSys = g[0].restingLevel(SysDias.SYSTOLIC, AFTERNOON);
      double restingDia = g[1].restingLevel(SysDias.DIASTOLIC, AFTERNOON);
      double[] s = new double[readings];
      double[] d = new double[readings];
      // the stock generator keeps its baseline in the attributes: a person of its own
      Person stockPerson = patient(1000L + p);
      BloodPressureValueGenerator stock = new BloodPressureValueGenerator(stockPerson,
          SysDias.SYSTOLIC);
      double[] st = new double[readings];
      for (int i = 0; i < readings; i++) {
        long time = AFTERNOON + 5 * i * DAY;  // 15:00 in Massachusetts, a few DST changes
        s[i] = g[0].getValue(time);
        d[i] = g[1].getValue(time);
        st[i] = stock.getValue(time);
      }
      sysSd[p] = sd(s);
      diaSd[p] = sd(d);
      correlations[p] = correlation(s, d);
      sysOffset[p] = mean(s) - restingSys;
      stockOffset[p] = mean(st)
          - (Double) stockPerson.attributes.get("bp_baseline_false_SYSTOLIC");
      // the pulse pressure cap lowers the diastolic level of the others
      if (restingDia < 0.7 * restingSys - 6) {
        diaOffset[uncapped] = mean(d) - restingDia;
        uncapped++;
      }
    }
    assertTrue("people below the diastolic cap: " + uncapped, uncapped >= 10);
    assertEquals(8.0, mean(sysSd), 0.5);
    assertEquals(5.0, mean(diaSd), 0.4);
    assertEquals(0.0, mean(sysOffset), 1.0);
    assertEquals(0.0, mean(Arrays.copyOf(diaOffset, uncapped)), 0.8);
    assertEquals(0.7, mean(correlations), 0.08);
    // stock: 15:00 EDT/16:00 EST is in a negative 12-hour block, so every reading is
    // baseline - |N(0, 8)|: on average 8 * sqrt(2 / pi) = 6.4 below the baseline
    assertEquals(-6.4, mean(stockOffset), 0.6);
  }

  @Test
  public void diastolicStaysBelowSystolicWithARealisticPulsePressure() {
    int readings = 0;
    int narrow = 0;
    int stockReadings = 0;
    int stockNarrow = 0;
    for (int p = 0; p < 300; p++) {
      Person person = patient(5000L + p);
      person.attributes.put("hypertension", p % 2 == 0);
      long admission = AFTERNOON + p * HOUR;
      person.record.encounterStart(admission, EncounterType.INPATIENT);
      if (p % 3 == 0) {
        person.record.conditionStart(admission, SEPTIC_SHOCK);
      }
      ContinuousBloodPressureGenerator[] g = withEffects(person, false);
      // the stock generator keeps its baseline in the attributes: a person of its own
      Person stockPerson = patient(5000L + p);
      stockPerson.attributes.put("hypertension", p % 2 == 0);
      BloodPressureValueGenerator stockSys = new BloodPressureValueGenerator(stockPerson,
          SysDias.SYSTOLIC);
      BloodPressureValueGenerator stockDia = new BloodPressureValueGenerator(stockPerson,
          SysDias.DIASTOLIC);
      for (int i = 0; i < 72; i++) {
        long time = admission + i * HOUR;
        double s = g[0].getValue(time);
        double d = g[1].getValue(time);
        assertTrue(s + "/" + d, s >= 50 && s <= 250 && d >= 25 && d <= 150);
        assertTrue(s + "/" + d, s - d >= 10);
        readings++;
        narrow += d > 0.75 * s ? 1 : 0;
        if (p % 3 != 0) {  // the stock generator ignores septic shock
          double stockS = stockSys.getValue(time);
          double stockD = stockDia.getValue(time);
          stockReadings++;
          stockNarrow += stockD > 0.75 * stockS ? 1 : 0;
        }
      }
    }
    double share = narrow / (double) readings;
    double stockShare = stockNarrow / (double) stockReadings;
    // pulse pressure under 25% of systolic: a few percent of adults' readings
    assertTrue("narrow pulse pressure: " + share + " vs stock " + stockShare,
        share < 0.06 && stockShare > 0.3);
  }

  @Test
  public void dipsAtNightOnlyDuringAStay() {
    Person inStay = patient(21L);
    Person atHome = patient(21L);
    long admission = local(2026, 9, 1, 12);
    inStay.record.encounterStart(admission, EncounterType.INPATIENT);
    ContinuousBloodPressureGenerator[] a = plain(inStay);
    ContinuousBloodPressureGenerator[] b = plain(atHome);
    double dip = a[0].getNocturnalDip();
    assertTrue("dip " + dip, dip > 0);
    assertEquals(1.0, a[0].nightShare(local(2026, 9, 2, 3)), 1e-9);
    assertEquals(0.5, a[0].nightShare(local(2026, 9, 2, 0)), 1e-9);
    assertEquals(0.0, a[0].nightShare(local(2026, 9, 2, 15)), 1e-9);
    assertEquals(0.0, a[0].nightShare(local(2026, 9, 1, 21)), 1e-9);
    assertEquals(0.0, b[0].nightShare(local(2026, 9, 2, 3)), 0.0);
    for (int i = 0; i < 72; i++) {
      long time = admission + i * HOUR;
      double resting = a[0].restingLevel(SysDias.SYSTOLIC, time);
      assertEquals(b[0].getValue(time) - resting * dip * a[0].nightShare(time),
          a[0].getValue(time), 1e-9);
      assertTrue(a[1].getValue(time) <= b[1].getValue(time) + 1e-9);
    }
    // discharged: no more dip
    inStay.record.encounterEnd(admission + 3 * DAY, EncounterType.INPATIENT);
    long night = local(2026, 9, 5, 3);
    assertEquals(0.0, a[0].nightShare(night), 0.0);
    assertEquals(b[0].getValue(night), a[0].getValue(night), 0.0);
  }

  @Test
  public void septicShockLowersBloodPressureByItsShare() {
    Person shock = patient(31L);
    Person well = patient(31L);
    long onset = AFTERNOON;
    shock.record.encounterStart(onset, EncounterType.EMERGENCY);
    shock.record.conditionStart(onset, SEPTIC_SHOCK);
    well.record.encounterStart(onset, EncounterType.EMERGENCY);
    ContinuousBloodPressureGenerator[] a = withEffects(shock, true);
    ContinuousBloodPressureGenerator[] b = withEffects(well, true);
    double restingSys = a[0].restingLevel(SysDias.SYSTOLIC, onset);
    double drop = 1 - Math.exp(-0.32);  // about a quarter
    for (int i = 0; i < 48; i++) {
      long time = onset + i * HOUR;
      assertEquals(b[0].getValue(time) - drop * restingSys, a[0].getValue(time), 1e-9);
      assertTrue(a[1].getValue(time) < b[1].getValue(time) - 15);
    }
    // before onset and once the effect has worn off (max_days 5, recovery 12 h) they agree
    assertEquals(b[0].getValue(onset - HOUR), a[0].getValue(onset - HOUR), 0.0);
    assertEquals(b[0].getValue(onset + 8 * DAY), a[0].getValue(onset + 8 * DAY), 0.5);
  }

  /**
   * With course and severity: a typical normotensive patient in septic shock is below a mean
   * arterial pressure of 65 at onset with a shock index above 1, and recovers once treated.
   */
  @Test
  public void septicShockIsRealistic() {
    int people = 200;
    double[] mapAtOnset = new double[people];
    double[] shockIndex = new double[people];
    double[] mapTwoDaysLater = new double[people];
    VitalSignEffects heartRateEffects = VitalSignEffects.load("heart_rate", true, true, true,
        true);
    for (int p = 0; p < people; p++) {
      Person person = patient(9000L + p);
      long onset = AFTERNOON + p * HOUR;
      person.record.encounterStart(onset, EncounterType.EMERGENCY);
      person.record.conditionStart(onset, SEPSIS);
      person.record.medicationStart(onset, PIPERACILLIN_TAZOBACTAM, false);
      person.record.conditionStart(onset, SEPTIC_SHOCK);
      ContinuousBloodPressureGenerator[] g = withEffects(person, false);
      ContinuousVitalSignGenerator heartRate = new ContinuousVitalSignGenerator(person,
          VitalSign.HEART_RATE.ordinal() + 1, 60, 100,
          ContinuousVitalSignGenerator.loadConfig("cardiovascular.heart_rate.continuous"),
          heartRateEffects);
      double s = g[0].getValue(onset);
      mapAtOnset[p] = (s + 2 * g[1].getValue(onset)) / 3;
      shockIndex[p] = heartRate.getValue(onset) / s;
      long later = onset + 2 * DAY;
      mapTwoDaysLater[p] = (g[0].getValue(later) + 2 * g[1].getValue(later)) / 3;
    }
    Arrays.sort(mapAtOnset);
    Arrays.sort(shockIndex);
    Arrays.sort(mapTwoDaysLater);
    assertTrue("median MAP at onset " + mapAtOnset[people / 2], mapAtOnset[people / 2] < 65);
    assertTrue("median shock index " + shockIndex[people / 2], shockIndex[people / 2] > 1);
    assertTrue("median MAP after 2 days " + mapTwoDaysLater[people / 2],
        mapTwoDaysLater[people / 2] > 75);
    // the most severe 5% of episodes are profound shock, not peri-arrest
    assertTrue("5th percentile MAP " + mapAtOnset[people / 20], mapAtOnset[people / 20] > 40);
    assertTrue("95th percentile shock index " + shockIndex[19 * people / 20],
        shockIndex[19 * people / 20] < 3);
  }

  @Test
  public void survivesSerialization() throws Exception {
    Person person = patient(5L);
    person.record.encounterStart(AFTERNOON, EncounterType.INPATIENT);
    person.record.conditionStart(AFTERNOON, SEPSIS);
    ContinuousBloodPressureGenerator g = withEffects(person, false)[1];
    double before = g.getValue(AFTERNOON + 10 * HOUR);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(g);
    }
    ContinuousBloodPressureGenerator copy;
    try (ObjectInputStream in = new ObjectInputStream(
        new ByteArrayInputStream(bytes.toByteArray()))) {
      copy = (ContinuousBloodPressureGenerator) in.readObject();
    }
    assertEquals(before, copy.getValue(AFTERNOON + 10 * HOUR), 0.0);
  }

  @Test
  public void personsDiffer() {
    assertNotEquals(plain(patient(1L))[0].getValue(AFTERNOON),
        plain(patient(2L))[0].getValue(AFTERNOON), 1e-6);
  }

  @Test
  public void timeZoneComesFromTheState() {
    assertEquals(ZoneId.of("America/New_York"),
        ContinuousBloodPressureGenerator.zoneOf("Massachusetts"));
    assertEquals(ZoneId.of("America/Los_Angeles"),
        ContinuousBloodPressureGenerator.zoneOf("California"));
    assertEquals(ZoneId.of("America/New_York"), ContinuousBloodPressureGenerator.zoneOf(null));
    assertEquals(ZoneId.of("America/New_York"),
        ContinuousBloodPressureGenerator.zoneOf("Nowhere"));
  }

  @Test
  public void softMinIsBelowBothAndSmooth() {
    assertEquals(10 - 3 * Math.log(2), ContinuousBloodPressureGenerator.softMin(10, 10, 3), 1e-12);
    assertEquals(50, ContinuousBloodPressureGenerator.softMin(50, 90, 3), 1e-5);
    assertTrue(ContinuousBloodPressureGenerator.softMin(80, 81, 3) < 80);
  }
}
