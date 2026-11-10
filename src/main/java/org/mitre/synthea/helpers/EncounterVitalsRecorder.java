package org.mitre.synthea.helpers;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.mitre.synthea.modules.BloodPressureValueGenerator;
import org.mitre.synthea.modules.LifecycleModule;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord;
import org.mitre.synthea.world.concepts.HealthRecord.CarePlan;
import org.mitre.synthea.world.concepts.HealthRecord.Code;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.HealthRecord.Entry;
import org.mitre.synthea.world.concepts.HealthRecord.Observation;
import org.mitre.synthea.world.concepts.VitalSign;

/**
 * Records vital signs at a fixed interval through each encounter of chosen classes
 * (lifecycle.vitals.encounter_recording in synthea.properties).
 *
 * <p>Each timestep it reads the time that has passed in those encounters, open or ended, from the
 * generator in place at each reading's time, a module's replacement included. Blood pressure's
 * flags, drugs and care plans are taken as of that time. Reads draw from a private random source
 * and the readings join the record at export with IDs that draw nothing, so the rest of the record
 * is the same as without them.</p>
 */
public final class EncounterVitalsRecorder {
  private static final String SETTING = "lifecycle.vitals.encounter_recording.";
  private static final String CATEGORY = "vital-signs";
  private static final String NAME = "Encounter vital signs";
  private static final double ROOM_AIR_PERCENT = 21;
  private static final long RANDOM_SALT = 0x65766974L;
  private static final Code BLOOD_PRESSURE_PANEL = loinc("85354-9",
      "Blood pressure panel with all children optional");
  private static final Code INHALED_OXYGEN = loinc("3150-0", "Inhaled oxygen concentration");
  /** Vital signs that can be recorded, each only on a continuous model. */
  private static final VitalSign[] RECORDABLE = {VitalSign.HEART_RATE,
      VitalSign.RESPIRATION_RATE, VitalSign.SYSTOLIC_BLOOD_PRESSURE,
      VitalSign.DIASTOLIC_BLOOD_PRESSURE, VitalSign.BODY_TEMPERATURE,
      VitalSign.OXYGEN_SATURATION};
  /** Attributes blood pressure reads that change over time, kept with their times. */
  private static final String[] TRACKED_ATTRIBUTES = {"hypertension", "hypertension_severe"};

  /** What is read at each time, coded as modules code it; decimals as Person.getVitalSign. */
  private enum Reading {
    HEART_RATE("/min", 0, VitalSign.HEART_RATE, loinc("8867-4", "Heart rate")),
    RESPIRATION_RATE("/min", 0, VitalSign.RESPIRATION_RATE, loinc("9279-1", "Respiratory rate")),
    BLOOD_PRESSURE("mm[Hg]", 0, VitalSign.SYSTOLIC_BLOOD_PRESSURE,
        loinc("8480-6", "Systolic Blood Pressure"), loinc("8462-4", "Diastolic Blood Pressure")),
    BODY_TEMPERATURE("Cel", 1, VitalSign.BODY_TEMPERATURE, loinc("8310-5", "Body temperature"),
        loinc("8331-1", "Oral temperature")),
    OXYGEN_SATURATION("%", 2, VitalSign.OXYGEN_SATURATION,
        loinc("2708-6", "Oxygen saturation in Arterial blood"),
        loinc("59408-5", "Oxygen saturation in Arterial blood by Pulse oximetry"));

    final String unit;
    final int decimals;
    final VitalSign vitalSign;
    /** For blood pressure, systolic then diastolic; otherwise the codes of one observation. */
    final Code[] codes;

    Reading(String unit, int decimals, VitalSign vitalSign, Code... codes) {
      this.unit = unit;
      this.decimals = decimals;
      this.vitalSign = vitalSign;
      this.codes = codes;
    }
  }

  private static final Reading[] READINGS = Reading.values();
  /** The reading each module code counts as, to skip times a module already recorded. */
  private static final Map<String, Reading> READING_OF_CODE = new HashMap<>();

  static {
    for (Reading reading : READINGS) {
      for (Code code : reading.codes) {
        READING_OF_CODE.put(code.code, reading);
      }
    }
    READING_OF_CODE.put(BLOOD_PRESSURE_PANEL.code, Reading.BLOOD_PRESSURE);
  }

  /** Null when recording is off. Read on first use, after the command line is applied. */
  private static final class Configured {
    static final EncounterVitalsRecorder INSTANCE = fromConfig();
  }

  /**
   * What a generator reads while the recorder reads a past time; set on the person for that read.
   */
  public interface PastRead {
    /** Draws for this read, apart from the person's own. */
    RandomNumberGenerator random();

    /** An attribute's value at the time read, or the default if it was unset. */
    Object attribute(String name, Object defaultValue);

    /** Whether a medication with this code was active at the time read. */
    boolean medicationActive(String code);

    /** The latest care plan with this code active at the time read, or null. */
    CarePlan carePlan(String code);
  }

  private final long intervalMs;
  /** Encounter classes, lower case. */
  private final Set<String> classes;
  private final Set<VitalSign> vitalSigns;

  /** One person's recorder state; kept on the Person so it survives a snapshot. */
  public static final class Readings implements Serializable {
    private static final long serialVersionUID = 1L;
    /** How many encounters of each record have been looked at. */
    private final IdentityHashMap<HealthRecord, Integer> scanned = new IdentityHashMap<>();
    /** Encounters of the chosen classes with readings left. */
    private final List<Waiting> waiting = new ArrayList<>();
    /** Readings not yet added to their encounters. */
    private final List<Held> pending = new ArrayList<>();
    /** Each recorded vital sign's generators, with the times they took over. */
    private final EnumMap<VitalSign, List<Change>> generators = new EnumMap<>(VitalSign.class);
    /** Values of the tracked attributes (null when unset), with the times they were set. */
    private final Map<String, List<Change>> attributes = new HashMap<>();
    /** Blood pressure drugs and care plans. */
    private transient VitalSignRecordIndex index;

    /** Note a vital sign's new generator. */
    public void vitalSignSet(VitalSign vitalSign, ValueGenerator generator, long time) {
      List<Change> changes = generators.get(vitalSign);
      if (changes != null) {
        changes.add(new Change(time, generator));
      }
    }

    /** Note an attribute's new value, null when removed. */
    public void attributeSet(String name, Object value, long time) {
      List<Change> changes = attributes.get(name);
      if (changes != null) {
        changes.add(new Change(time, value));
      }
    }

    VitalSignRecordIndex index() {
      if (index == null) {
        index = new VitalSignRecordIndex(Collections.emptySet(),
            BloodPressureValueGenerator.drugCodes(), BloodPressureValueGenerator.carePlanCodes());
      }
      return index;
    }

    /** Readings not yet added to their encounters, for tests. */
    int pendingObservations() {
      int n = 0;
      for (Held held : pending) {
        n += held.observations.size();
      }
      return n;
    }
  }

  /** A value and the time it took over. */
  private static final class Change implements Serializable {
    private static final long serialVersionUID = 1L;
    final long time;
    final Object value;

    Change(long time, Object value) {
      this.time = time;
      this.value = value;
    }
  }

  /** An encounter being read and the time of its next reading. */
  private static final class Waiting implements Serializable {
    private static final long serialVersionUID = 1L;
    final HealthRecord record;
    final Encounter encounter;
    long next;

    Waiting(HealthRecord record, Encounter encounter) {
      this.record = record;
      this.encounter = encounter;
      this.next = encounter.start;
    }
  }

  /** Readings for an encounter. */
  private static final class Held implements Serializable {
    private static final long serialVersionUID = 1L;
    final Encounter encounter;
    final List<Observation> observations;

    Held(Encounter encounter, List<Observation> observations) {
      this.encounter = encounter;
      this.observations = observations;
    }
  }

  /** Inputs as of one reading's time. */
  private static final class Past implements PastRead {
    private final Person person;
    private final Readings readings;
    private final long time;
    private final long seed;
    private RandomNumberGenerator random;

    Past(Person person, Readings readings, long time, long seed) {
      this.person = person;
      this.readings = readings;
      this.time = time;
      this.seed = seed;
    }

    @Override
    public RandomNumberGenerator random() {
      if (random == null) {
        random = new DefaultRandomNumberGenerator(seed);
      }
      return random;
    }

    @Override
    public Object attribute(String name, Object defaultValue) {
      List<Change> changes = readings.attributes.get(name);
      Change change = changes == null ? null : at(changes, time);
      Object value = change != null ? change.value : person.attributes.get(name);
      return value != null ? value : defaultValue;
    }

    @Override
    public boolean medicationActive(String code) {
      for (VitalSignRecordIndex.Item item : readings.index().medications()) {
        if (item.entry.type.equals(code) && activeAt(item.entry)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public CarePlan carePlan(String code) {
      CarePlan latest = null;
      for (VitalSignRecordIndex.Item item : readings.index().carePlans()) {
        if (item.entry.type.equals(code) && activeAt(item.entry)
            && (latest == null || item.entry.start >= latest.start)) {
          latest = (CarePlan) item.entry;
        }
      }
      return latest;
    }

    /** Started by the time and not stopped by then; a stop of 0 means not stopped. */
    private boolean activeAt(Entry entry) {
      return entry.start <= time && (entry.stop == 0L || entry.stop > time);
    }
  }

  /**
   * A recorder reading every intervalMs through encounters of the given classes.
   *
   * @param intervalMs time between readings, from the encounter's start
   * @param classes encounter classes, e.g. "inpatient"
   * @param vitalSigns the vital signs to record
   */
  EncounterVitalsRecorder(long intervalMs, Set<String> classes, Set<VitalSign> vitalSigns) {
    if (intervalMs <= 0) {
      throw new IllegalArgumentException(SETTING + "interval_minutes must be positive");
    }
    Set<String> lower = new HashSet<>();
    for (String c : classes) {
      String name = c.trim().toLowerCase(Locale.ROOT);
      if (name.isEmpty()) {
        continue;
      }
      boolean known = false;
      for (EncounterType type : EncounterType.values()) {
        known |= type.toString().equals(name);
      }
      if (!known) {
        throw new IllegalArgumentException(SETTING + "encounter_classes: unknown class " + c);
      }
      lower.add(name);
    }
    this.intervalMs = intervalMs;
    this.classes = Collections.unmodifiableSet(lower);
    Set<VitalSign> recorded = EnumSet.noneOf(VitalSign.class);
    recorded.addAll(vitalSigns);
    recorded.retainAll(Arrays.asList(RECORDABLE));
    this.vitalSigns = Collections.unmodifiableSet(recorded);
  }

  private static EncounterVitalsRecorder fromConfig() {
    if (!Config.getAsBoolean(SETTING + "enabled", false)) {
      return null;
    }
    long minutes = Config.getAsLong(SETTING + "interval_minutes", 60L);
    String classes = Config.get(SETTING + "encounter_classes", "inpatient");
    Set<VitalSign> vitalSigns = EnumSet.noneOf(VitalSign.class);
    for (VitalSign vitalSign : RECORDABLE) {
      if (LifecycleModule.hasContinuousModel(vitalSign)) {
        vitalSigns.add(vitalSign);
      } else {
        System.out.println("Encounter vitals recording: " + vitalSign
            + " is not on a continuous model, so it is not recorded.");
      }
    }
    return new EncounterVitalsRecorder(minutes * 60_000L,
        new HashSet<>(Arrays.asList(classes.split(","))), vitalSigns);
  }

  /** Start recording a person; encounters already open are read from their start. */
  public static void begin(Person person) {
    EncounterVitalsRecorder recorder = Configured.INSTANCE;
    if (recorder != null) {
      recorder.start(person);
    }
  }

  /** Read the time that has passed in the chosen encounters, after a timestep. */
  public static void collect(Person person, long time) {
    EncounterVitalsRecorder recorder = Configured.INSTANCE;
    if (recorder != null) {
      recorder.read(person, time, false);
    }
  }

  /** Read every encounter left, up to the time, once the simulation is over. */
  public static void finish(Person person, long time) {
    EncounterVitalsRecorder recorder = Configured.INSTANCE;
    if (recorder != null) {
      recorder.read(person, time, true);
    }
  }

  /** Add the readings to their encounters, before export. */
  public static void attach(Person person) {
    Readings readings = person.encounterVitals;
    if (readings == null) {
      return;
    }
    for (Held held : readings.pending) {
      held.encounter.observations.addAll(held.observations);
    }
    readings.pending.clear();
  }

  void start(Person person) {
    if (person.encounterVitals != null) {
      return;
    }
    Readings readings = new Readings();
    for (HealthRecord record : VitalSignRecordIndex.records(person)) {
      readings.scanned.put(record, record.encounters.size());
      for (Encounter encounter : record.encounters) {
        if (!encounter.ended && chosen(encounter)) {
          readings.waiting.add(new Waiting(record, encounter));
        }
      }
    }
    // earlier generators and flags are unknown, so these stand for them
    for (VitalSign vitalSign : vitalSigns) {
      List<Change> changes = new ArrayList<>();
      ValueGenerator generator = person.vitalSigns.get(vitalSign);
      if (generator == null || generator instanceof TrendingValueGenerator
          || generator.getClass() == BloodPressureValueGenerator.class) {
        // what LifecycleModule gives it at the next timestep
        generator = LifecycleModule.newContinuousGenerator(person, vitalSign);
      }
      if (generator != null) {
        changes.add(new Change(Long.MIN_VALUE, generator));
      }
      readings.generators.put(vitalSign, changes);
    }
    for (String name : TRACKED_ATTRIBUTES) {
      List<Change> changes = new ArrayList<>();
      changes.add(new Change(Long.MIN_VALUE, person.attributes.get(name)));
      readings.attributes.put(name, changes);
    }
    person.encounterVitals = readings;
  }

  void read(Person person, long time, boolean last) {
    start(person);
    Readings readings = person.encounterVitals;
    for (HealthRecord record : VitalSignRecordIndex.records(person)) {
      List<Encounter> encounters = record.encounters;
      int from = Math.min(readings.scanned.getOrDefault(record, 0), encounters.size());
      for (int i = from; i < encounters.size(); i++) {
        Encounter encounter = encounters.get(i);
        if (chosen(encounter)) {
          readings.waiting.add(new Waiting(record, encounter));
        }
      }
      readings.scanned.put(record, encounters.size());
    }
    Object death = person.attributes.get(Person.DEATHDATE);
    long horizon = time;
    for (Iterator<Waiting> it = readings.waiting.iterator(); it.hasNext();) {
      Waiting waiting = it.next();
      Encounter encounter = waiting.encounter;
      long end = encounter.ended ? Math.max(encounter.stop, encounter.start + 1) : Long.MAX_VALUE;
      if (death instanceof Long) {
        end = Math.min(end, (Long) death);
      }
      // an open encounter's current interval waits, as a module may still record in it
      long bound = Math.min(end, last || encounter.ended ? time : time - intervalMs + 1);
      if (waiting.next < bound) {
        List<Observation> observations = readEncounter(person, readings, waiting, bound);
        if (!observations.isEmpty()) {
          readings.pending.add(new Held(encounter, observations));
        }
      }
      if (last || waiting.next >= end) {
        it.remove();
      } else {
        horizon = Math.min(horizon, waiting.next);
      }
    }
    for (List<Change> changes : readings.generators.values()) {
      prune(changes, horizon);
    }
    for (List<Change> changes : readings.attributes.values()) {
      prune(changes, horizon);
    }
  }

  private boolean chosen(Encounter encounter) {
    return classes.contains(encounter.type.toLowerCase(Locale.ROOT));
  }

  /** Readings every interval from the next one until the bound; moves the next one on. */
  private List<Observation> readEncounter(Person person, Readings readings, Waiting waiting,
      long bound) {
    List<Observation> out = new ArrayList<>();
    Encounter encounter = waiting.encounter;
    HealthRecord record = waiting.record;
    readings.index().update(person);
    Set<Long> recorded = moduleReadings(encounter);
    long slot = (waiting.next - encounter.start) / intervalMs;
    long time = waiting.next;
    for (; time < bound; time += intervalMs, slot++) {
      for (Reading reading : READINGS) {
        if (recorded.contains(slot * READINGS.length + reading.ordinal())) {
          continue;
        }
        if (reading == Reading.BLOOD_PRESSURE) {
          Double systolic = value(person, readings, VitalSign.SYSTOLIC_BLOOD_PRESSURE, time);
          Double diastolic = value(person, readings, VitalSign.DIASTOLIC_BLOOD_PRESSURE, time);
          if (systolic == null || diastolic == null) {
            continue;
          }
          Observation panel = observation(record, encounter, time, null,
              BLOOD_PRESSURE_PANEL);
          // child order as a MultiObservation state leaves it
          panel.observations.add(observation(record, encounter, time,
              round(diastolic, reading.decimals), reading.codes[1]));
          panel.observations.add(observation(record, encounter, time,
              round(systolic, reading.decimals), reading.codes[0]));
          for (Observation child : panel.observations) {
            child.unit = reading.unit;
          }
          out.add(panel);
          continue;
        }
        Double value = value(person, readings, reading.vitalSign, time);
        if (value == null) {
          continue;
        }
        Observation o = observation(record, encounter, time, round(value, reading.decimals),
            reading.codes);
        o.unit = reading.unit;
        out.add(o);
        ValueGenerator generator = generatorAt(readings, reading.vitalSign, time);
        if (generator instanceof ContinuousOxygenSaturationGenerator) {
          long at = time;
          double fio2 = round(asOf(person, readings, reading.vitalSign, time,
              () -> ((ContinuousOxygenSaturationGenerator) generator).inhaledOxygen(at)), 0);
          if (fio2 > ROOM_AIR_PERCENT) {
            Observation oxygen = observation(record, encounter, time, fio2, INHALED_OXYGEN);
            oxygen.unit = "%";
            out.add(oxygen);
          }
        }
      }
    }
    waiting.next = time;
    return out;
  }

  /** The vital sign from the generator in place at the time, or null if there is none to read. */
  private Double value(Person person, Readings readings, VitalSign vitalSign, long time) {
    ValueGenerator generator = generatorAt(readings, vitalSign, time);
    if (generator == null) {
      return null;
    }
    return asOf(person, readings, vitalSign, time, () -> generator.getValue(time));
  }

  /** The generator in place at the time, if it is one that reading leaves unchanged. */
  private ValueGenerator generatorAt(Readings readings, VitalSign vitalSign, long time) {
    List<Change> changes = readings.generators.get(vitalSign);
    Change change = changes == null ? null : at(changes, time);
    Object generator = change == null ? null : change.value;
    // stock blood pressure keeps draws in attributes and caches values; physiology simulates
    boolean stateless = generator instanceof ContinuousVitalSignGenerator
        || generator instanceof ContinuousBloodPressureGenerator
        || generator instanceof ContinuousBodyTemperatureGenerator
        || generator instanceof ContinuousOxygenSaturationGenerator
        || generator instanceof ConstantValueGenerator
        || generator instanceof RandomValueGenerator
        || generator instanceof TrendingValueGenerator;
    return stateless ? (ValueGenerator) generator : null;
  }

  /** Runs a read with the person's inputs as of the time and draws apart from the person's. */
  private static <T> T asOf(Person person, Readings readings, VitalSign vitalSign, long time,
      Supplier<T> read) {
    person.pastRead = new Past(person, readings, time, seed(person, vitalSign, time));
    try {
      return read.get();
    } finally {
      person.pastRead = null;
    }
  }

  /** A seed for one vital sign at one time, unrelated to the person's own draws. */
  private static long seed(Person person, VitalSign vitalSign, long time) {
    long z = person.getSeed() ^ RANDOM_SALT ^ (vitalSign.ordinal() + 1) * 0x9E3779B97F4A7C15L
        ^ time * 0xC2B2AE3D27D4EB4FL;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** The change in effect at a time: the latest by then (the later noted on a tie), else the
   * earliest. */
  private static Change at(List<Change> changes, long time) {
    Change best = null;
    Change earliest = null;
    for (Change change : changes) {
      if (change.time <= time && (best == null || change.time >= best.time)) {
        best = change;
      }
      if (earliest == null || change.time < earliest.time) {
        earliest = change;
      }
    }
    return best != null ? best : earliest;
  }

  /** Drop the changes no read at or after the time needs. */
  private static void prune(List<Change> changes, long time) {
    Change kept = null;
    for (Change change : changes) {
      if (change.time <= time && (kept == null || change.time >= kept.time)) {
        kept = change;
      }
    }
    if (kept != null) {
      Change keep = kept;
      changes.removeIf(change -> change != keep && change.time <= time);
    }
  }

  /** Interval slots (slot * readings + reading) in which a module already recorded the reading. */
  private Set<Long> moduleReadings(Encounter encounter) {
    Set<Long> recorded = new HashSet<>();
    List<Observation> all = new ArrayList<>(encounter.observations);
    for (Observation o : encounter.observations) {
      if (o.observations != null) {
        all.addAll(o.observations);
      }
    }
    for (Observation o : all) {
      long slot = Math.floorDiv(o.start - encounter.start, intervalMs);
      if (slot < 0) {
        continue;
      }
      Set<Reading> readings = new HashSet<>();
      Reading byType = READING_OF_CODE.get(o.type);
      if (byType != null) {
        readings.add(byType);
      }
      for (Code code : o.codes) {
        Reading byCode = READING_OF_CODE.get(code.code);
        if (byCode != null) {
          readings.add(byCode);
        }
      }
      for (Reading reading : readings) {
        recorded.add(slot * READINGS.length + reading.ordinal());
      }
    }
    return recorded;
  }

  /** An observation whose ID follows from the encounter, code and time, so nothing is drawn. */
  private static Observation observation(HealthRecord record, Encounter encounter, long time,
      Object value, Code... codes) {
    String type = codes[0].code;
    UUID uuid = UUID.nameUUIDFromBytes((encounter.uuid + "/" + type + "/" + time)
        .getBytes(StandardCharsets.UTF_8));
    Observation o = record.new Observation(time, type, value, uuid);
    o.name = NAME;
    o.category = CATEGORY;
    Collections.addAll(o.codes, codes);
    return o;
  }

  /** Rounded as Person.getVitalSign rounds. */
  private static double round(double value, int decimals) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return value;
    }
    return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
  }

  private static Code loinc(String code, String display) {
    return new Code("LOINC", code, display);
  }
}
