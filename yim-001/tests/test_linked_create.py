import json
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4

from yim001 import EventLedger, MemoryStore, save_store, write_json_atomic
from yim001.linked_create import linked_create
from yim001.provenance import LINKED_COMPLETION_SCHEMA
from yim001.storage import read_json_strict


class LinkedCreatePersistenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.store_path = root / "memory.json"
        self.ledger_path = root / "ledger.json"
        save_store(MemoryStore(), self.store_path)
        write_json_atomic(self.ledger_path, EventLedger().to_snapshot())
        self.operation_id = str(uuid4())
        self.content = {"message": "linked"}
        self.metadata = {"source": "test"}

    def run_linked(self, fault=None, operation_id=None, actor="operator-a"):
        return linked_create(
            str(self.store_path), str(self.ledger_path), self.content,
            self.metadata, actor, operation_id or self.operation_id, _fault=fault,
        )

    def test_linked_create_persists_intent_record_and_completion(self):
        result = self.run_linked()
        self.assertEqual(result.outcome, "CONSISTENT")
        store = MemoryStore.from_snapshot(read_json_strict(self.store_path))
        ledger = EventLedger.from_snapshot(read_json_strict(self.ledger_path))
        version = store.get_version(result.version_id)
        self.assertEqual(version.record_id, result.record_id)
        self.assertEqual([e.event_type for e in ledger.events], [
            "record.create_intent", "record.created"
        ])
        intent, completion = ledger.events
        self.assertEqual(intent.actor_id, "operator-a")
        self.assertEqual(completion.actor_id, "operator-a")
        self.assertEqual(completion.details["schema"], LINKED_COMPLETION_SCHEMA)
        self.assertEqual(completion.details["intent_event_id"], intent.event_id)
        self.assertEqual(completion.details["operation_id"], self.operation_id)

    def test_retry_of_completed_operation_is_idempotent(self):
        first = self.run_linked()
        second = self.run_linked()
        self.assertEqual(first.outcome, "CONSISTENT")
        self.assertEqual(second.outcome, "CONSISTENT")
        self.assertEqual(first.record_id, second.record_id)
        self.assertEqual(first.version_id, second.version_id)
        ledger = EventLedger.from_snapshot(read_json_strict(self.ledger_path))
        self.assertEqual(len(ledger.events), 2)

    def test_intent_only_state_resumes_exact_candidate(self):
        def stop(point):
            if point == "after_intent_write":
                raise RuntimeError("simulated stop")
        with self.assertRaises(RuntimeError):
            self.run_linked(fault=stop)
        ledger_before = EventLedger.from_snapshot(read_json_strict(self.ledger_path))
        self.assertEqual(len(ledger_before.events), 1)
        resumed = self.run_linked()
        self.assertEqual(resumed.outcome, "CONSISTENT")
        ledger_after = EventLedger.from_snapshot(read_json_strict(self.ledger_path))
        self.assertEqual(len(ledger_after.events), 2)

    def test_record_without_completion_is_uncertain_and_not_repaired(self):
        def stop(point):
            if point == "after_memory_write":
                raise RuntimeError("simulated stop")
        with self.assertRaises(RuntimeError):
            self.run_linked(fault=stop)
        before = self.ledger_path.read_bytes()
        result = self.run_linked()
        self.assertEqual(result.outcome, "UNCERTAIN")
        self.assertEqual(self.ledger_path.read_bytes(), before)
        self.assertEqual(len(EventLedger.from_snapshot(read_json_strict(self.ledger_path)).events), 1)

    def test_operation_id_reuse_with_different_actor_conflicts(self):
        self.run_linked()
        result = self.run_linked(actor="different-actor")
        self.assertEqual(result.outcome, "CONFLICT")


if __name__ == "__main__":
    unittest.main()
