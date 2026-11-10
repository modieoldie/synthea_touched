package org.mitre.synthea.modules;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mitre.synthea.engine.Components.Range;
import org.mitre.synthea.helpers.EncounterVitalsRecorder;
import org.mitre.synthea.helpers.SimpleCSV;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.helpers.ValueGenerator;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.BiometricsConfig;
import org.mitre.synthea.world.concepts.HealthRecord;

/**
 * Generate realistic blood pressure vital signs,
 * with impacts from select medications.
 *
 * <p>ContinuousBloodPressureGenerator reuses the resting level through {@link Draws}.</p>
 */
public class BloodPressureValueGenerator extends ValueGenerator {
  /** Pinned to the value Java computed before, so existing snapshots still load. */
  private static final long serialVersionUID = -4463697955745176250L;

  /**
   * Enum representing systolic and diastolic blood pressure.
   */
  public enum SysDias {
    /** Systolic blood pressure. */
    SYSTOLIC,
    /** Diastolic blood pressure. */
    DIASTOLIC
  }

  /** Hypertensive systolic blood pressure range. */
  private static final int[] HYPERTENSIVE_SYS_BP_RANGE = BiometricsConfig
      .ints("metabolic.blood_pressure.hypertensive.systolic");
  /** Hypertensive diastolic blood pressure range. */
  private static final int[] HYPERTENSIVE_DIA_BP_RANGE = BiometricsConfig
      .ints("metabolic.blood_pressure.hypertensive.diastolic");
  /** Normal systolic blood pressure range. */
  private static final int[] NORMAL_SYS_BP_RANGE = BiometricsConfig
      .ints("metabolic.blood_pressure.normal.systolic");
  /** Normal diastolic blood pressure range. */
  private static final int[] NORMAL_DIA_BP_RANGE = BiometricsConfig
      .ints("metabolic.blood_pressure.normal.diastolic");


  // Notes on where these impacts come from:
  // https://www.bmj.com/content/359/bmj.j5542
  // https://pubmed.ncbi.nlm.nih.gov/31668726/
  // https://academic.oup.com/ajh/article/18/7/935/221053
  /** Table of impacts of hypertension drugs on blood pressure. */
  private static final Table<String, SysDias, Range<Double>> HTN_DRUG_IMPACTS;

  static {
    try {
      Table<String, SysDias, Range<Double>> drugImpacts = HashBasedTable.create();

      String csv = Utilities.readResource("htn_drugs.csv");

      List<LinkedHashMap<String, String>> table = SimpleCSV.parse(csv);

      for (LinkedHashMap<String,String> line : table) {
        String code = line.get("RxNorm");
        double impactMinSystolic = Double.parseDouble(line.get("Systolic Impact Minimum"));
        double impactMaxSystolic = Double.parseDouble(line.get("Systolic Impact Maximum"));

        Range<Double> impactSystolic = new Range<>();
        impactSystolic.low = impactMinSystolic;
        impactSystolic.high = impactMaxSystolic;
        drugImpacts.put(code, SysDias.SYSTOLIC, impactSystolic);

        double impactMinDiastolic = Double.parseDouble(line.get("Diastolic Impact Minimum"));
        double impactMaxDiastolic = Double.parseDouble(line.get("Diastolic Impact Maximum"));
        Range<Double> impactDiastolic = new Range<>();
        impactDiastolic.low = impactMinDiastolic;
        impactDiastolic.high = impactMaxDiastolic;
        drugImpacts.put(code, SysDias.DIASTOLIC, impactDiastolic);
      }

      HTN_DRUG_IMPACTS = drugImpacts;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** Lifestyle care plans; each is considered on its own and the first active one applies. */
  private static final String[] CARE_PLAN_CODES = {"443402002"}; // base hypertension careplan

  /** One year in milliseconds. */
  private static final long ONE_YEAR = Utilities.convertTime("years", 1);

  /** Cycle time for blood pressure variation in milliseconds. */
  private static final long CYCLE_TIME = Utilities.convertTime("hours", 12);

  /** Source of the resting level's random numbers, each drawn once per person and key. */
  public interface Draws {
    /**
     * A random number between low and high (low may be the larger one).
     *
     * @param key what the number is for, the same for every draw of the same thing
     */
    double uniform(String key, double low, double high);

    /** Whether a drawn number is kept in the person's attributes and reused from then on. */
    boolean keep();
  }

  /** Stock Synthea: the person's random number stream, kept in the person's attributes. */
  private static final class PersonDraws implements Draws {
    private final Person person;

    PersonDraws(Person person) {
      this.person = person;
    }

    @Override
    public double uniform(String key, double low, double high) {
      return person.rand(low, high);
    }

    @Override
    public boolean keep() {
      return true;
    }
  }

  // simple 1-value cache, so that we get consistent results when called with the same timestamp
  // note that consistency is not guaranteed if we go time A -> time B -> time A across modules.
  // in that case we'd need a bigger cache
  /** Cached blood pressure value for consistency. */
  private Double cachedValue;
  /** Timestamp of the cached blood pressure value. */
  private long cacheTime;

  /** Indicates whether the value is systolic or diastolic. */
  private SysDias sysDias;

  /**
   * Constructor for BloodPressureValueGenerator.
   *
   * @param person The person for whom blood pressure is being generated.
   * @param sysDias Indicates whether the value is systolic or diastolic.
   */
  public BloodPressureValueGenerator(Person person, SysDias sysDias) {
    super(person);
    this.sysDias = sysDias;
  }

  /**
   * Get the blood pressure value at a specific time.
   *
   * @param time The time for which the blood pressure value is needed.
   * @return The blood pressure value.
   */
  @Override
  public double getValue(long time) {
    if (cacheTime == time && cachedValue != null) {
      return cachedValue;
    }

    cachedValue = restingLevel(person, sysDias, time, new PersonDraws(person))
      + getVariation(person, time);

    cacheTime = time;
    return cachedValue;
  }

  /**
   * The baseline for the person's hypertension status plus the impacts of medications and
   * lifestyle changes, before daily variation.
   *
   * @param draws Where the random numbers come from.
   */
  public static double restingLevel(Person person, SysDias sysDias, long time, Draws draws) {
    double baseline = calculateBaseline(person, sysDias, draws);

    return baseline
      + getMedicationImpacts(person, sysDias, draws)
      + getLifestyleImpacts(person, sysDias, baseline, time, draws);
  }

  /**
   * Helper function to ensure a person has a consistent impact from a drug.
   *
   * @param person The person taking the drug.
   * @param drug The drug being taken.
   * @param sysDias Indicates whether the impact is on systolic or diastolic blood pressure.
   * @param impactRange The range of the drug's impact.
   * @param draws Where the random numbers come from.
   * @return The impact of the drug on blood pressure.
   */
  private static double getDrugImpact(Person person, String drug, SysDias sysDias,
      Range<Double> impactRange, Draws draws) {
    Table<String, SysDias, Double> personalDrugImpacts =
        (Table<String, SysDias, Double>)person.attributes.get("htn_drug_impacts");
    if (personalDrugImpacts == null && draws.keep()) {
      personalDrugImpacts = HashBasedTable.create();
      person.attributes.put("htn_drug_impacts", personalDrugImpacts);
    }

    if (personalDrugImpacts != null && personalDrugImpacts.contains(drug, sysDias)) {
      return personalDrugImpacts.get(drug, sysDias);
    }

    // note these are intentionally flipped. "max impact" == lower number, since these are negative
    double impact = draws.uniform("htn_drug_" + drug + "_" + sysDias,
        impactRange.high, impactRange.low);

    if (draws.keep()) {
      personalDrugImpacts.put(drug, sysDias, impact);
    }

    return impact;
  }

  /**
   * Get the "baseline" BP for a Person.
   * Baseline here means the BP values accounting for hypertension, but before
   * applying medications, lifestyle changes, and intra-daily variation.
   *
   * @param person The person for whom the baseline is being calculated.
   * @param sysDias Systolic or diastolic.
   * @param draws Where the random numbers come from.
   * @return The baseline blood pressure.
   */
  private static double calculateBaseline(Person person, SysDias sysDias, Draws draws) {
    boolean hypertension = flag(person, "hypertension");
    boolean severe = flag(person, "hypertension_severe");

    double baseline;

    String bpBaselineKey = "bp_baseline_" + hypertension + "_" + sysDias.toString();

    if (person.attributes.containsKey(bpBaselineKey)) {
      baseline = (Double)person.attributes.get(bpBaselineKey);
      return baseline;

    } else {
      if (sysDias == SysDias.SYSTOLIC) {
        if (hypertension) {
          if (severe) {
            // this leaves fewer people at the upper end of the spectrum
            baseline = draws.uniform(bpBaselineKey,
                HYPERTENSIVE_SYS_BP_RANGE[1], HYPERTENSIVE_SYS_BP_RANGE[2]);

          } else {
            // this skews the distribution to be more on the lower side of the range
            baseline = draws.uniform(bpBaselineKey,
                HYPERTENSIVE_SYS_BP_RANGE[0], HYPERTENSIVE_SYS_BP_RANGE[1]);
          }
        } else {
          baseline = draws.uniform(bpBaselineKey, NORMAL_SYS_BP_RANGE[0], NORMAL_SYS_BP_RANGE[1]);
        }
      } else {
        if (hypertension) {
          baseline = draws.uniform(bpBaselineKey,
              HYPERTENSIVE_DIA_BP_RANGE[0], HYPERTENSIVE_DIA_BP_RANGE[1]);
        } else {
          baseline = draws.uniform(bpBaselineKey, NORMAL_DIA_BP_RANGE[0], NORMAL_DIA_BP_RANGE[1]);
        }
      }

      if ("M".equals(person.attributes.get(Person.GENDER))) {
        baseline += 1;
      } else {
        baseline -= 1;
      }

      if (draws.keep()) {
        person.attributes.put(bpBaselineKey, baseline);
      }
      return baseline;
    }
  }

  /**
   * Get the amount of impact that lifestyle changes, if this Person is making any,
   * will have on their BP. Lifestyle changes are tracked as specific CarePlans.
   *
   * @param person The person making lifestyle changes.
   * @param sysDias Systolic or diastolic.
   * @param baseline The baseline blood pressure.
   * @param time The time for which the impact is being calculated.
   * @param draws Where the random numbers come from.
   * @return The impact of lifestyle changes on blood pressure.
   */
  private static double getLifestyleImpacts(Person person, SysDias sysDias, double baseline,
      long time, Draws draws) {
    // if the person has a "blood pressure care plan"
    // assume that over ~1 year their blood pressure will be reduced by ~14 mmHg
    // with most of that happening in the first 6 mos

    // https://pubmed.ncbi.nlm.nih.gov/12709466/

    double maxDrop;
    if (sysDias == SysDias.SYSTOLIC) {
      // don't allow diet/exercise alone to drop below 124
      double delta = Math.abs(baseline - 124);
      maxDrop = -Math.min(delta, 14.0);
    } else {
      // don't allow diet/exercise alone to drop below 78
      double delta = Math.abs(baseline - 78);
      maxDrop = -Math.min(delta, 8.0);
    }

    // allow for multiple codes, and consider each independently,
    // but if one is active then skip the rest
    // (assume they don't add together)
    for (String code : CARE_PLAN_CODES) {
      double adherenceRatio = 0.15;
      // estimate 3-25% of patients are adherent,
      // so for a single # pick ~15%

      HealthRecord.CarePlan careplan = activeCarePlan(person, code);

      if (careplan != null) {
        boolean carePlanAdherent;
        String key = "htn_trial_" + code + "_careplan_adherent";
        if (person.attributes.containsKey(key)) {
          carePlanAdherent = (boolean) person.attributes.get(key);
        } else {
          carePlanAdherent = draws.uniform(key, 0, 1) < adherenceRatio;
          if (draws.keep()) {
            person.attributes.put(key, carePlanAdherent);
          }
        }
        if (carePlanAdherent) {
          long start = careplan.start;

          if (time > (start + ONE_YEAR)) {
            // max value
            return maxDrop;
          } else if (time < start) {
            return 0.0;
          }

          // assume a linear relationship between time and drop
          double fractionThruYear = ((double)(time - start)) / ((double)ONE_YEAR);

          return fractionThruYear * maxDrop;
        }
      }
    }

    return 0.0;
  }

  /**
   * Get the impact of medications on blood pressure.
   *
   * @param person The person taking medications.
   * @param sysDias Systolic or diastolic.
   * @param draws Where the random numbers come from.
   * @return The impact of medications on blood pressure.
   */
  private static double getMedicationImpacts(Person person, SysDias sysDias, Draws draws) {
    double drugImpactDelta = 0.0;
    // see also LifecycleModule.calculateVitalSigns

    for (Map.Entry<String, Range<Double>> e : HTN_DRUG_IMPACTS.column(sysDias).entrySet()) {
      String medicationCode = e.getKey();
      Range<Double> impactRange = e.getValue();
      if (medicationActive(person, medicationCode)) {
        double impact = getDrugImpact(person, medicationCode, sysDias, impactRange, draws);

        // impacts are negative, so add them (ie, don't subtract them)
        drugImpactDelta += impact;
      }
    }

    return drugImpactDelta;
  }

  /** Codes of the drugs that lower blood pressure. */
  public static Set<String> drugCodes() {
    return Collections.unmodifiableSet(HTN_DRUG_IMPACTS.rowKeySet());
  }

  /** Codes of the lifestyle care plans that lower blood pressure. */
  public static Set<String> carePlanCodes() {
    return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(CARE_PLAN_CODES)));
  }

  // The lookups below read the person as they are now, or as of the time read while the
  // encounter vitals recorder reads a past time.

  private static boolean flag(Person person, String name) {
    EncounterVitalsRecorder.PastRead past = person.pastRead;
    return (boolean) (past != null ? past.attribute(name, false)
        : person.attributes.getOrDefault(name, false));
  }

  private static boolean medicationActive(Person person, String code) {
    EncounterVitalsRecorder.PastRead past = person.pastRead;
    return past != null ? past.medicationActive(code) : person.record.medicationActive(code);
  }

  /** The active care plan with this code, or null. */
  private static HealthRecord.CarePlan activeCarePlan(Person person, String code) {
    EncounterVitalsRecorder.PastRead past = person.pastRead;
    if (past != null) {
      return past.carePlan(code);
    }
    HealthRecord.CarePlan careplan = (HealthRecord.CarePlan) person.record.present.get(code);
    return careplan != null && careplan.stop == 0L ? careplan : null;
  }

  /**
   * Get the daily variation in blood pressure.
   *
   * @param person The person whose blood pressure is being calculated.
   * @param time The time for which the variation is being calculated.
   * @return The daily variation in blood pressure.
   */
  private double getVariation(Person person, long time) {
    // blood pressure can vary significantly during the day
    // https://www.health.harvard.edu/heart-health/experts-call-for-home-blood-pressure-monitoring

    // some notes for the ideal implementation:
    // - systolic and diastolic should generally move in the same direction at the same time
    // - BP should generally be lowest at night (by 10-20%)

    // for now we'll just use a normal distribution, SD of 8 for systolic and 5 for diastolic
    // the sign will be set based on time so that sys & dias have the same one

    double normalSD = this.sysDias == SysDias.SYSTOLIC ? 8 : 5;
    double magnitude = Math.abs(person.randGaussian()) * normalSD;
    int sign = (time / CYCLE_TIME) % 2 == 0 ? 1 : -1;
    // the goal here is to get sign = 1 for ~ half the time and -1 half the time
    // so (x) % 2 == 0 just checks if a number is even.
    // and by dividing time / 12 hours,
    //  we should get a number that is even for 12 hours then odd for 12 hours

    return sign * magnitude;
  }
}