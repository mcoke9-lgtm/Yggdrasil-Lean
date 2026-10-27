"""Evidence provenance policy for recovery decisions.

This module does not verify evidence itself. It classifies evidence according to
an explicit verifier result supplied by a trusted caller and fails closed when
provenance is absent, invalid, or inconsistent with the recovery claim.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from .recovery import RecoveryDecision, RecoveryFacts, decide_recovery

EvidenceStatus = Literal["ABSENT", "CALLER_ASSERTED", "VERIFIED", "INVALID"]
EvidenceClaim = Literal["NONE", "OUTCOME", "RECONCILIATION"]


@dataclass(frozen=True)
class EvidenceProvenance:
    """Provenance for one evidence reference."""
    claim: EvidenceClaim
    status: EvidenceStatus
    evidence_reference: str | None = None
    verifier: str | None = None
    verification_reference: str | None = None


@dataclass(frozen=True)
class ProvenanceRecoveryDecision:
    outcome: str
    reason: str
    underlying_decision: RecoveryDecision


def decide_recovery_with_provenance(
    facts: RecoveryFacts, provenance: EvidenceProvenance
) -> ProvenanceRecoveryDecision:
    """Require explicit verified provenance before trusting completion claims.

    This validates the shape and consistency of the verifier's assertion. It
    does not authenticate the verifier or independently fetch the source.
    """
    if not isinstance(facts, RecoveryFacts) or not isinstance(provenance, EvidenceProvenance):
        decision = RecoveryDecision("STOP_CONFLICT", "facts and provenance must use their declared types")
        return ProvenanceRecoveryDecision(decision.outcome, decision.reason, decision)

    decision = decide_recovery(facts)
    if provenance.status not in ("ABSENT", "CALLER_ASSERTED", "VERIFIED", "INVALID"):
        bad = RecoveryDecision("STOP_CONFLICT", "unknown evidence provenance status")
        return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
    if provenance.claim not in ("NONE", "OUTCOME", "RECONCILIATION"):
        bad = RecoveryDecision("STOP_CONFLICT", "unknown evidence claim type")
        return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)

    if provenance.status == "INVALID":
        bad = RecoveryDecision("STOP_CONFLICT", "evidence was marked invalid")
        return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
    if provenance.status == "ABSENT":
        if provenance.evidence_reference or provenance.verifier or provenance.verification_reference:
            bad = RecoveryDecision("STOP_CONFLICT", "absent evidence cannot carry provenance references")
            return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
        if decision.outcome == "CONFIRMED_COMPLETE":
            bad = RecoveryDecision("STOP_CONFLICT", "completion claim lacks verified evidence provenance")
            return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
        return ProvenanceRecoveryDecision(decision.outcome, decision.reason, decision)

    if not isinstance(provenance.evidence_reference, str) or not provenance.evidence_reference.strip():
        bad = RecoveryDecision("STOP_CONFLICT", "evidence_reference must be non-empty text")
        return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)

    if provenance.status == "CALLER_ASSERTED":
        if provenance.verifier or provenance.verification_reference:
            bad = RecoveryDecision("STOP_CONFLICT", "caller-asserted evidence cannot claim independent verification")
            return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
        if decision.outcome == "CONFIRMED_COMPLETE":
            bad = RecoveryDecision("STOP_CONFLICT", "caller assertion alone cannot confirm completion")
            return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
        return ProvenanceRecoveryDecision(decision.outcome, decision.reason, decision)

    if not isinstance(provenance.verifier, str) or not provenance.verifier.strip():
        bad = RecoveryDecision("STOP_CONFLICT", "verified evidence requires a verifier identity")
        return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
    if not isinstance(provenance.verification_reference, str) or not provenance.verification_reference.strip():
        bad = RecoveryDecision("STOP_CONFLICT", "verified evidence requires a verification record reference")
        return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
    if provenance.claim == "OUTCOME":
        if not facts.outcome_evidence or provenance.evidence_reference != facts.outcome_evidence:
            bad = RecoveryDecision("STOP_CONFLICT", "verified outcome provenance must match the exact outcome evidence reference")
            return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
    if provenance.claim == "RECONCILIATION" and facts.explicit_reconciliation_result is None:
        bad = RecoveryDecision("STOP_CONFLICT", "verified reconciliation provenance has no matching reconciliation claim")
        return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
    if provenance.claim == "NONE":
        bad = RecoveryDecision("STOP_CONFLICT", "evidence reference must identify its claim type")
        return ProvenanceRecoveryDecision(bad.outcome, bad.reason, bad)
    return ProvenanceRecoveryDecision(decision.outcome, decision.reason, decision)
