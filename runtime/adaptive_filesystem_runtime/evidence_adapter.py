"""Replaceable authoritative evidence adapter contract (AF-RECOVERY-011).

Interface only: no network calls, source authentication, or proof of integrity.
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import Literal, Protocol, runtime_checkable
from .evidence_provenance import EvidenceClaim

VerificationStatus = Literal["VERIFIED", "INVALID", "UNAVAILABLE"]

@dataclass(frozen=True)
class EvidenceVerificationResult:
    """Explicit source-check outcome; UNAVAILABLE is never success."""
    status: VerificationStatus
    claim: EvidenceClaim
    evidence_reference: str
    source_reference: str | None = None
    verifier: str | None = None
    verification_reference: str | None = None
    reason: str | None = None
    reconciliation_result: Literal["completed", "not_started"] | None = None

@runtime_checkable
class AuthoritativeEvidenceAdapter(Protocol):
    """Port implemented by an adapter for an authorized authoritative source."""
    def verify_evidence(self, evidence_reference: str, claim: EvidenceClaim) -> EvidenceVerificationResult:
        """Return VERIFIED, INVALID, or UNAVAILABLE explicitly."""

def validate_adapter_result(result: EvidenceVerificationResult, *, expected_reference: str, expected_claim: EvidenceClaim, expected_reconciliation_result: str | None = None) -> EvidenceVerificationResult:
    """Check result/request consistency; this does not authenticate the adapter."""
    def invalid(reason: str) -> EvidenceVerificationResult:
        return EvidenceVerificationResult("INVALID", expected_claim, expected_reference, reason=reason)
    if not isinstance(result, EvidenceVerificationResult):
        return invalid("unsupported adapter result type")
    if result.status not in ("VERIFIED", "INVALID", "UNAVAILABLE"):
        return invalid("unknown verification status")
    if result.claim != expected_claim or result.evidence_reference != expected_reference:
        return invalid("result does not match requested reference and claim")
    if result.status == "VERIFIED" and any(not isinstance(v, str) or not v.strip() for v in (result.source_reference, result.verifier, result.verification_reference)):
        return invalid("verified result requires source, verifier, and verification-record references")
    if result.status == "VERIFIED" and expected_claim == "RECONCILIATION":
        if result.reconciliation_result not in ("completed", "not_started"):
            return invalid("verified reconciliation requires its exact result")
        if (expected_reconciliation_result is not None
                and result.reconciliation_result != expected_reconciliation_result):
            return invalid("verified reconciliation result does not match the recovery claim")
    return result
