"""Connect adapter verification to recovery decisions (AF-RECOVERY-012).

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
    """Verify an outcome reference before allowing it to support completion.

    No outcome reference means there is nothing to verify; the ordinary recovery
    policy is applied with absent provenance. Adapter exceptions and UNAVAILABLE
    responses cannot confirm completion and require reconciliation when evidence
    was expected. INVALID or malformed responses stop with a conflict.
    """
    if not isinstance(facts, RecoveryFacts):
        bad = RecoveryDecision("STOP_CONFLICT", "facts must be RecoveryFacts")
        return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
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
