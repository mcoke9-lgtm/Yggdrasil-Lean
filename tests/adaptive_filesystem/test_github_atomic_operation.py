"""Contract tests for the GitHub atomic ref + operation-marker protocol."""
import unittest

from adaptive_filesystem.github_atomic_operation import (
    ZERO_OID,
    build_update_refs_input,
    canonical_marker_record,
    classify_marker_lookup,
    operation_marker_ref,
)


OID_A = "a" * 40
OID_B = "b" * 40
OID_C = "c" * 40


class GitHubAtomicOperationTests(unittest.TestCase):
    def marker_record(self, **overrides):
        values = {
            "operation_id": "op-2026-001",
            "repository": "mcoke9-lgtm/Yggdrasil-Lean",
            "target_ref": "refs/heads/test-branch",
            "before_oid": OID_A,
            "after_oid": OID_B,
            "authorization_id": "auth-001",
            "action_fingerprint": "sha256:abc",
            "scope": ["update:branch-ref", "repository:test"],
        }
        values.update(overrides)
        return canonical_marker_record(**values)

    def test_GA01_marker_ref_is_deterministic_and_namespaced(self):
        ref = operation_marker_ref("op-2026-001")
        self.assertEqual(ref, operation_marker_ref("op-2026-001"))
        self.assertTrue(ref.startswith("refs/tags/af-operations/"))
        self.assertEqual(len(ref.rsplit("/", 1)[1]), 64)

    def test_GA02_marker_record_is_canonical_and_scope_order_independent(self):
        first = self.marker_record()
        second = self.marker_record(scope=["repository:test", "update:branch-ref"])
        self.assertEqual(first["canonical_json"], second["canonical_json"])
        self.assertEqual(first["record_sha256"], second["record_sha256"])

    def test_GA03_marker_binds_exact_before_after_and_authority(self):
        record = self.marker_record()["record"]
        self.assertEqual(record["before_oid"], OID_A)
        self.assertEqual(record["after_oid"], OID_B)
        self.assertEqual(record["authorization_id"], "auth-001")
        self.assertEqual(record["action_fingerprint"], "sha256:abc")

    def test_GA04_update_refs_contains_branch_cas_and_unique_marker_create(self):
        request = build_update_refs_input(
            repository_id="R_123",
            client_mutation_id="correlation-001",
            target_ref="refs/heads/test-branch",
            before_oid=OID_A,
            after_oid=OID_B,
            operation_id="op-2026-001",
            marker_commit_oid=OID_C,
        )
        branch_update, marker_create = request["refUpdates"]
        self.assertEqual(branch_update["beforeOid"], OID_A)
        self.assertEqual(branch_update["afterOid"], OID_B)
        self.assertFalse(branch_update["force"])
        self.assertEqual(marker_create["beforeOid"], ZERO_OID)
        self.assertEqual(marker_create["afterOid"], OID_C)
        self.assertTrue(marker_create["name"].startswith("refs/tags/af-operations/"))

    def test_GA05_marker_found_with_expected_oid_confirms_atomic_operation(self):
        self.assertEqual(
            classify_marker_lookup(
                lookup_status="FOUND",
                actual_marker_oid=OID_C,
                expected_marker_oid=OID_C,
            ),
            "OPERATION_CONFIRMED",
        )

    def test_GA06_marker_found_with_other_oid_is_conflict(self):
        self.assertEqual(
            classify_marker_lookup(
                lookup_status="FOUND",
                actual_marker_oid=OID_B,
                expected_marker_oid=OID_C,
            ),
            "MARKER_CONFLICT",
        )

    def test_GA07_marker_absence_never_claims_success(self):
        self.assertEqual(
            classify_marker_lookup(lookup_status="NOT_FOUND"),
            "MARKER_ABSENT_RETRY_EXACT_CAS_ONLY",
        )

    def test_GA08_unknown_lookup_state_remains_unresolved(self):
        self.assertEqual(
            classify_marker_lookup(lookup_status="UNAVAILABLE"),
            "RECONCILIATION_REQUIRED",
        )

    def test_GA09_invalid_marker_oid_is_not_accepted(self):
        self.assertEqual(
            classify_marker_lookup(
                lookup_status="FOUND",
                actual_marker_oid="not-an-oid",
                expected_marker_oid=OID_C,
            ),
            "MARKER_INVALID",
        )

    def test_GA10_invalid_target_ref_and_oid_are_rejected(self):
        with self.assertRaises(ValueError):
            self.marker_record(target_ref="main")
        with self.assertRaises(ValueError):
            self.marker_record(after_oid="short")

    def test_GA11_invalid_operation_id_is_rejected(self):
        with self.assertRaises(ValueError):
            operation_marker_ref("../branch")

    def test_GA12_same_before_and_after_is_rejected(self):
        with self.assertRaises(ValueError):
            build_update_refs_input(
                repository_id="R_123",
                client_mutation_id="correlation-001",
                target_ref="refs/heads/test-branch",
                before_oid=OID_A,
                after_oid=OID_A,
                operation_id="op-2026-001",
                marker_commit_oid=OID_C,
            )


if __name__ == "__main__":
    unittest.main()
