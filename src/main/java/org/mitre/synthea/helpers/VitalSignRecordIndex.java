package org.mitre.synthea.helpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.Entry;

/**
 * The conditions and medications with listed codes in a person's health records, read incrementally
 * so dense vital sign readings do not rescan the whole record.
 *
 * <p>Entries can be appended to any encounter, not only the newest, so update() tracks how many it
 * has seen per encounter. It starts over if a record's encounter list is replaced or shrinks.</p>
 */
final class VitalSignRecordIndex {
  /** A listed condition or medication and the encounter it was recorded in. */
  static final class Item {
    final Encounter encounter;
    final Entry entry;

    Item(Encounter encounter, Entry entry) {
      this.encounter = encounter;
      this.entry = entry;
    }
  }

  /** How much of one health record has been read. */
  private static final class Progress {
    List<Encounter> encounters;
    int encountersSeen;
    int[] conditionsSeen = new int[16];
    int[] medicationsSeen = new int[16];
  }

  private final Set<String> conditionCodes;
  private final Set<String> medicationCodes;
  private final IdentityHashMap<HealthRecord, Progress> progress = new IdentityHashMap<>();
  private final List<Item> conditions = new ArrayList<>();
  private final List<Item> medications = new ArrayList<>();

  VitalSignRecordIndex(Set<String> conditionCodes, Set<String> medicationCodes) {
    this.conditionCodes = conditionCodes;
    this.medicationCodes = medicationCodes;
  }

  /** Listed conditions, in no particular order. */
  List<Item> conditions() {
    return conditions;
  }

  /** Listed medications, in no particular order. */
  List<Item> medications() {
    return medications;
  }

  /** Read what was added to the person's health records since the last update. */
  void update(Person person) {
    List<HealthRecord> records = records(person);
    if (progress.size() > records.size() || !records.containsAll(progress.keySet())) {
      clear();
    }
    for (HealthRecord record : records) {
      Progress p = progress.get(record);
      if (p != null && (p.encounters != record.encounters
          || p.encountersSeen > record.encounters.size())) {
        clear();
        update(person);
        return;
      }
      if (p == null) {
        p = new Progress();
        p.encounters = record.encounters;
        progress.put(record, p);
      }
      read(record, p);
    }
  }

  private void read(HealthRecord record, Progress p) {
    int n = record.encounters.size();
    if (n > p.conditionsSeen.length) {
      int size = Math.max(n, 2 * p.conditionsSeen.length);
      p.conditionsSeen = Arrays.copyOf(p.conditionsSeen, size);
      p.medicationsSeen = Arrays.copyOf(p.medicationsSeen, size);
    }
    for (int i = 0; i < n; i++) {
      Encounter encounter = record.encounters.get(i);
      if (!conditionCodes.isEmpty()) {
        int seen = p.conditionsSeen[i];
        int size = encounter.conditions.size();
        for (int j = seen; j < size; j++) {
          Entry condition = encounter.conditions.get(j);
          if (conditionCodes.contains(condition.type)) {
            conditions.add(new Item(encounter, condition));
          }
        }
        p.conditionsSeen[i] = size;
      }
      if (!medicationCodes.isEmpty()) {
        int seen = p.medicationsSeen[i];
        int size = encounter.medications.size();
        for (int j = seen; j < size; j++) {
          Entry medication = encounter.medications.get(j);
          if (medicationCodes.contains(medication.type)) {
            medications.add(new Item(encounter, medication));
          }
        }
        p.medicationsSeen[i] = size;
      }
    }
    p.encountersSeen = n;
  }

  private void clear() {
    progress.clear();
    conditions.clear();
    medications.clear();
  }

  /** The person's distinct health records (more than one with split records or loss of care). */
  static List<HealthRecord> records(Person person) {
    List<HealthRecord> records = new ArrayList<>(3);
    addRecord(records, person.defaultRecord);
    addRecord(records, person.record);
    addRecord(records, person.lossOfCareRecord);
    if (person.records != null) {
      for (HealthRecord record : person.records.values()) {
        addRecord(records, record);
      }
    }
    return records;
  }

  private static void addRecord(List<HealthRecord> records, HealthRecord record) {
    if (record != null && !records.contains(record)) {
      records.add(record);
    }
  }
}
