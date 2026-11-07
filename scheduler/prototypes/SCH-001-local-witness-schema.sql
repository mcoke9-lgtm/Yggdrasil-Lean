-- SCH-001 disposable local SQLite witness prototype.
-- NOT a Cloudflare D1 deployment or production schema.
PRAGMA foreign_keys = ON;

CREATE TABLE task_heads (
  task_id TEXT PRIMARY KEY,
  task_fingerprint TEXT NOT NULL,
  sequence INTEGER NOT NULL CHECK(sequence >= 0),
  generation INTEGER NOT NULL CHECK(generation >= 0),
  authority_owner TEXT NOT NULL,
  latest_event_id TEXT,
  latest_event_hash TEXT,
  updated_at TEXT NOT NULL
);

CREATE TABLE authority_events (
  event_id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL REFERENCES task_heads(task_id),
  sequence INTEGER NOT NULL,
  generation INTEGER NOT NULL,
  event_type TEXT NOT NULL CHECK(event_type IN ('REVIEW','TRANSFER')),
  task_fingerprint TEXT NOT NULL,
  expected_previous_sequence INTEGER NOT NULL,
  expected_previous_generation INTEGER NOT NULL,
  expected_previous_owner TEXT NOT NULL,
  new_owner TEXT NOT NULL,
  previous_event_hash TEXT,
  event_hash TEXT NOT NULL,
  authorization_principal TEXT NOT NULL,
  authorization_decision_id TEXT NOT NULL,
  authorization_evidence_digest TEXT NOT NULL,
  transfer_id TEXT UNIQUE,
  witness_timestamp TEXT NOT NULL,
  schema_version INTEGER NOT NULL,
  receipt_id TEXT NOT NULL UNIQUE,
  UNIQUE(task_id, sequence)
);

-- Validate all compare-and-swap expectations in the same INSERT statement.
CREATE TRIGGER authority_events_validate
BEFORE INSERT ON authority_events
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM task_heads h
    WHERE h.task_id = NEW.task_id
      AND h.task_fingerprint = NEW.task_fingerprint
      AND h.sequence = NEW.expected_previous_sequence
      AND h.generation = NEW.expected_previous_generation
      AND h.authority_owner = NEW.expected_previous_owner
      AND NEW.sequence = h.sequence + 1
      AND (
        (NEW.event_type = 'REVIEW'
          AND NEW.generation = h.generation
          AND NEW.new_owner = h.authority_owner
          AND NEW.transfer_id IS NULL)
        OR
        (NEW.event_type = 'TRANSFER'
          AND NEW.generation = h.generation + 1
          AND NEW.new_owner <> h.authority_owner
          AND NEW.transfer_id IS NOT NULL)
      )
      AND NEW.previous_event_hash IS h.latest_event_hash
  ) THEN RAISE(ABORT, 'STALE_OR_INVALID_EXPECTATION') END;
END;

-- The projection advances in the same statement transaction as the event insert.
CREATE TRIGGER authority_events_advance_head
AFTER INSERT ON authority_events
BEGIN
  UPDATE task_heads
  SET sequence = NEW.sequence,
      generation = NEW.generation,
      authority_owner = NEW.new_owner,
      latest_event_id = NEW.event_id,
      latest_event_hash = NEW.event_hash,
      updated_at = NEW.witness_timestamp
  WHERE task_id = NEW.task_id;
END;

-- Application-path guardrails only; privileged administrators can alter schema.
CREATE TRIGGER authority_events_no_update
BEFORE UPDATE ON authority_events
BEGIN
  SELECT RAISE(ABORT, 'APPEND_ONLY');
END;

CREATE TRIGGER authority_events_no_delete
BEFORE DELETE ON authority_events
BEGIN
  SELECT RAISE(ABORT, 'APPEND_ONLY');
END;
