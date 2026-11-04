package org.mitre.synthea.helpers;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.geography.Location;

/**
 * Which encounter a person is in at a time, and the local hour, for the continuous vital signs.
 * Reads only the person's record, so a reading stays a pure function of the time and the record.
 */
final class VitalSignTime {
  private static final long MS_PER_HOUR = 3_600_000L;
  private static final long MS_PER_DAY = 24 * MS_PER_HOUR;
  /** How many of the person's latest encounters are searched. */
  private static final int ENCOUNTER_SEARCH = 20;
  /** Encounters spent sleeping in a bed. */
  private static final String[] STAY_CLASSES = {EncounterType.INPATIENT.toString(),
      EncounterType.SNF.toString(), EncounterType.HOSPICE.toString()};
  /** Stays plus the emergency department: encounters where staff can start oxygen. */
  private static final String[] OXYGEN_CLASSES = {EncounterType.INPATIENT.toString(),
      EncounterType.SNF.toString(), EncounterType.HOSPICE.toString(),
      EncounterType.EMERGENCY.toString()};
  /** Time zone of each zone name in geography/timezones.csv. */
  private static final Map<String, String> ZONES = new HashMap<>();
  private static final String DEFAULT_ZONE = "America/New_York";

  static {
    ZONES.put("Eastern Standard Time", "America/New_York");
    ZONES.put("Central Standard Time", "America/Chicago");
    ZONES.put("Mountain Standard Time", "America/Denver");
    ZONES.put("Pacific Standard Time", "America/Los_Angeles");
    ZONES.put("Alaska Standard Time", "America/Anchorage");
    ZONES.put("Hawaii-Aleutian Standard Time", "Pacific/Honolulu");
  }

  private VitalSignTime() {
  }

  /** Whether the time falls in an inpatient, nursing facility or hospice encounter. */
  static boolean inStay(Person person, long time) {
    return ongoing(person, time, STAY_CLASSES) != null;
  }

  /** The inpatient, nursing facility, hospice or emergency encounter at a time, or null. */
  static Encounter oxygenEncounter(Person person, long time) {
    return ongoing(person, time, OXYGEN_CLASSES);
  }

  /** The latest encounter of these classes under way at a time, or null. */
  private static Encounter ongoing(Person person, long time, String[] classes) {
    List<Encounter> encounters = person.record.encounters;
    for (int i = encounters.size() - 1, n = 0; i >= 0 && n < ENCOUNTER_SEARCH; i--, n++) {
      Encounter e = encounters.get(i);
      // an encounter's stop is provisional until it has ended
      if (e.start <= time && (!e.ended || time < e.stop) && isOneOf(e.type, classes)) {
        return e;
      }
    }
    return null;
  }

  private static boolean isOneOf(String type, String[] classes) {
    for (String c : classes) {
      if (c.equalsIgnoreCase(type)) {
        return true;
      }
    }
    return false;
  }

  /** Hour of the day (0 to 24) in a time zone. */
  static double localHour(ZoneId zone, long time) {
    long offsetMs = 1000L * zone.getRules().getOffset(Instant.ofEpochMilli(time)).getTotalSeconds();
    return Math.floorMod(time + offsetMs, MS_PER_DAY) / (double) MS_PER_HOUR;
  }

  /** Hour of the day (0 to 24) in the time zone of the person's state. */
  static double localHour(Person person, long time) {
    return localHour(zoneOf((String) person.attributes.get(Person.STATE)), time);
  }

  /** The time zone of a state, America/New_York if it is unknown. */
  static ZoneId zoneOf(String state) {
    String name = state == null ? null : Location.getTimezoneByState(state);
    return ZoneId.of(ZONES.getOrDefault(name, DEFAULT_ZONE));
  }

  /** A raised cosine in the hour of the day: 1 at the trough hour, 0 from halfWidthHours away. */
  static double raisedCosine(double hour, double troughHour, double halfWidthHours) {
    double distance = Math.abs(hour - troughHour) % 24;
    distance = Math.min(distance, 24 - distance);
    if (distance >= halfWidthHours) {
      return 0;
    }
    return 0.5 * (1 + Math.cos(Math.PI * distance / halfWidthHours));
  }
}
