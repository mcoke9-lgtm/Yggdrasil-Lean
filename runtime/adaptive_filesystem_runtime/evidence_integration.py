"""Connect outcome and reconciliation verification to recovery decisions (AF-RECOVERY-012).

The adapter is a trust boundary: this function validates its response shape and
then applies provenance policy. It does not authenticate the adapter itself.
"""
from __future__ import annotations

from .evidence_adapter import (
    AuthoritativeEvidenceAdapter,
    EvidenceVerificationResult,
    validate_adapter_result,
)
from .evidence_provenance import (
    EvidenceProvenance,
    ProvenanceRecoveryDecision,
    decide_recovery_with_provenance,
)
from .recovery import RecoveryDecision, RecoveryFacts


def decide_recovery_with_adapter(
    facts: RecoveryFacts, adapter: AuthoritativeEvidenceAdapter
) -> ProvenanceRecoveryDecision:
    """Verify the exact evidence reference for an outcome or reconciliation claim.

    Explicit reconciliation takes precedence and must have its own reference.
    No outcome reference means there is nothing to verify; ordinary recovery
    policy is applied with absent provenance. Adapter exceptions and UNAVAILABLE
    responses cannot confirm completion. INVALID or malformed responses stop
    with a conflict. The adapter remains an unauthenticated trust boundary.
    """
    if not isinstance(facts, RecoveryFacts):
        bad = RecoveryDecision("STOP_CONFLICT", "facts must be RecoveryFacts")
        return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
    if facts.explicit_reconciliation_result is not None:
        reference = facts.reconciliation_evidence_reference
        if not isinstance(reference, str) or not reference.strip():
            bad = RecoveryDecision(
                "STOP_CONFLICT",
                "explicit reconciliation result requires an exact reconciliation evidence reference",
            )
            return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
        try:
            raw = adapter.verify_evidence(reference, "RECONCILIATION")
        except Exception:
            bad = RecoveryDecision(
                "RECONCILE_REQUIRED",
                "evidence adapter raised an exception; reconciliation remains unverified",
            )
            return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
        result = validate_adapter_result(
            raw, expected_reference=reference, expected_claim="RECONCILIATION"
        )
        if result.status == "UNAVAILABLE":
            bad = RecoveryDecision(
                "RECONCILE_REQUIRED",
                result.reason or "authoritative reconciliation evidence source is unavailable",
            )
            return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
        if result.status == "INVALID":
            bad = RecoveryDecision(
                "STOP_CONFLICT", result.reason or "evidence adapter rejected the reconciliation"
            )
            return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
        provenance = EvidenceProvenance(
            claim="RECONCILIATION",
            status="VERIFIED",
            evidence_reference=result.evidence_reference,
            verifier=result.verifier,
            verification_reference=result.verification_reference,
        )
        return decide_recovery_with_provenance(facts, provenance)

    if facts.outcome_evidence is None:
        return decide_recovery_with_provenance(
            facts, EvidenceProvenance(claim="NONE", status="ABSENT")
        )
    reference = facts.outcome_evidence
    try:
        raw = adapter.verify_evidence(reference, "OUTCOME")
    except Exception:
        bad = RecoveryDecision(
            "RECONCILE_REQUIRED",
            "evidence adapter raised an exception; outcome remains unverified",
        )
        return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)

    result = validate_adapter_result(
        raw, expected_reference=reference, expected_claim="OUTCOME"
    )
    if result.status == "UNAVAILABLE":
        bad = RecoveryDecision(
            "RECONCILE_REQUIRED",
            result.reason or "authoritative evidence source is unavailable",
        )
        return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
    if result.status == "INVALID":
        bad = RecoveryDecision(
            "STOP_CONFLICT", result.reason or "evidence adapter rejected the outcome"
        )
        return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)

    provenance = EvidenceProvenance(
        claim="OUTCOME",
        status="VERIFIED",
        evidence_reference=result.evidence_reference,
        verifier=result.verifier,
        verification_reference=result.verification_reference,
    )
    return decide_recovery_with_provenance(facts, provenance)
