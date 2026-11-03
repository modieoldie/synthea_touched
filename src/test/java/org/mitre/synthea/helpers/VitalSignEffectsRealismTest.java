package org.mitre.synthea.helpers;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.VitalSign;

/**
 * Population checks of the parameters in biometrics.yml against published observations, with
 * the generators as LifecycleModule builds them (all effects on).
 */
public class VitalSignEffectsRealismTest {
  private static final long HOUR = 3_600_000L;
  private static final long T0 = 1_788_000_000_000L;  // Sep 2026
  private static final String PNEUMONIA = "233604007";
  private static final String CEFTRIAXONE = "1665021";
  private static final String METOPROLOL = "866514";

  private static ContinuousVitalSignGenerator generator(Person person, VitalSign vitalSign,
      boolean effects) {
    boolean heart = vitalSign == VitalSign.HEART_RATE;
    String key = heart ? "heart_rate" : "respiration_rate";
    Map<String, Object> config = ContinuousVitalSignGenerator.loadConfig(
        heart ? "cardiovascular.heart_rate.continuous" : "respiratory.respiration_rate.continuous");
    double[] normal = heart ? new double[] {60, 100} : new double[] {12, 16};
    return new ContinuousVitalSignGenerator(person, vitalSign.ordinal() + 1, normal[0], normal[1],
        config, effects ? VitalSignEffects.load(key, true, true, true, true) : null);
  }

  /** Days from admission (day 0) to the first day whose worst hourly reading is at most limit. */
  private static int daysToStability(ContinuousVitalSignGenerator g, double limit, int days) {
    for (int day = 0; day < days; day++) {
      double worst = Double.NEGATIVE_INFINITY;
      for (int h = 0; h < 24; h++) {
        worst = Math.max(worst, Math.round(g.getValue(T0 + (day * 24 + h) * HOUR)));
      }
      if (worst <= limit) {
        return day;
      }
    }
    return days;
  }

  private static double median(List<Integer> values) {
    List<Integer> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int n = sorted.size();
    return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
  }

  /**
   * Halm et al. (JAMA 1998), community-acquired pneumonia: median 2 days to heart rate <= 100 and
   * 3 days to respiration rate <= 24. Here: patients admitted with pneumonia and given antibiotics,
   * among those above the threshold on the admission day.
   */
  @Test
  public void pneumoniaResolvesLikeHospitalizedPneumonia() {
    int people = 1000;
    List<Integer> heartDays = new ArrayList<>();
    List<Integer> respirationDays = new ArrayList<>();
    double admissionHeart = 0;
    double admissionRespiration = 0;
    double dayThreeHeart = 0;
    for (int i = 0; i < people; i++) {
      Person person = ConditionVitalSignEffectsTest.patient(1000 + i);
      ContinuousVitalSignGenerator heart = generator(person, VitalSign.HEART_RATE, true);
      ContinuousVitalSignGenerator respiration =
          generator(person, VitalSign.RESPIRATION_RATE, true);
      person.record.encounterStart(T0, EncounterType.INPATIENT);
      person.record.conditionStart(T0, PNEUMONIA);
      person.record.medicationAdministration(T0, CEFTRIAXONE);
      admissionHeart += heart.getValue(T0) / people;
      admissionRespiration += respiration.getValue(T0) / people;
      dayThreeHeart += heart.getValue(T0 + 72 * HOUR) / people;
      int h = daysToStability(heart, 100, 10);
      if (h > 0) {
        heartDays.add(h);
      }
      int r = daysToStability(respiration, 24, 10);
      if (r > 0) {
        respirationDays.add(r);
      }
    }
    double heartMedian = median(heartDays);
    double respirationMedian = median(respirationDays);
    System.out.printf("pneumonia: admission HR %.1f, RR %.1f; HR at 72 h %.1f; "
        + "unstable at admission HR %d%%, RR %d%%; median days to HR<=100 %.1f, RR<=24 %.1f%n",
        admissionHeart, admissionRespiration, dayThreeHeart, heartDays.size() * 100 / people,
        respirationDays.size() * 100 / people, heartMedian, respirationMedian);
    assertTrue("admission heart rate " + admissionHeart,
        admissionHeart > 95 && admissionHeart < 106);
    assertTrue("admission respiration rate " + admissionRespiration,
        admissionRespiration > 23 && admissionRespiration < 29);
    assertTrue("heart rate falls during the stay", dayThreeHeart < admissionHeart - 10);
    assertTrue("median days to heart rate <= 100: " + heartMedian,
        heartMedian >= 1 && heartMedian <= 3);
    assertTrue("median days to respiration rate <= 24: " + respirationMedian,
        respirationMedian >= 2 && respirationMedian <= 4);
  }

  /**
   * Beta blockers lower resting heart rate by roughly 10-15 bpm in those who take them; about 60%
   * of people prescribed a cardiovascular drug take it (Naderi et al., Am J Med 2012).
   */
  @Test
  public void betaBlockersLowerRestingHeartRate() {
    int people = 2000;
    double meanShift = 0;
    int readings = 0;
    int below60 = 0;
    int below60Untreated = 0;
    for (int i = 0; i < people; i++) {
      Person person = ConditionVitalSignEffectsTest.patient(5000 + i);
      ContinuousVitalSignGenerator plain = generator(person, VitalSign.HEART_RATE, false);
      ContinuousVitalSignGenerator treated = generator(person, VitalSign.HEART_RATE, true);
      person.record.encounterStart(T0, EncounterType.WELLNESS);
      person.record.medicationStart(T0, METOPROLOL, true);
      for (int h = 0; h < 7 * 24; h += 6) {
        long time = T0 + h * HOUR;
        double before = Math.round(plain.getValue(time));
        double after = Math.round(treated.getValue(time));
        meanShift += after - before;
        readings++;
        below60 += after < 60 ? 1 : 0;
        below60Untreated += before < 60 ? 1 : 0;
      }
    }
    meanShift /= readings;
    System.out.printf("beta blocker: mean shift %.1f bpm; readings below 60: %.1f%% (%.1f%% "
        + "without)%n", meanShift, 100.0 * below60 / readings, 100.0 * below60Untreated / readings);
    // 60% adherence times about 12 bpm
    assertTrue("mean shift " + meanShift, meanShift < -5.5 && meanShift > -9);
    assertTrue(below60 > 0 && below60Untreated == 0);
  }
}
