# Recovery overlay export: provenance boundary

## What the current validation establishes

The merged overlay export validator checks the envelope format and version, validates the generation identifier, checks that the two recorded baseline SHA-256 fields have the expected hexadecimal shape, verifies the outer content checksum, and delegates the embedded conversation and subject snapshots to the canonical recovery-package validator.

The outer checksum covers the recorded baseline digest fields. This detects accidental or unauthorized changes to the envelope when compared with a separately trusted checksum. It does **not** by itself authenticate who created the envelope, prove that the recorded baseline digests came from the original baseline files, or establish an independently trusted chain of custody.

## What staging does not establish

Successful validation and staging establish that the embedded snapshots passed the implemented structural and integrity checks and were written/read back through the staging path. They do not establish that the source baseline is authentic or that its recorded digest matches an independently preserved baseline artifact.

In particular, the current export format carries baseline digest metadata but does not carry or independently retrieve the original baseline files for a fresh digest comparison during this validation path.

## Required wording and future verification

Until an independent provenance mechanism is implemented and tested, describe these values as **recorded baseline digests** and the operation as **snapshot integrity validation and staging**. Do not describe this check alone as authenticated baseline verification, proof of origin, or proof of custody.

A stronger provenance claim requires an independently trusted reference, such as:
- the original baseline artifacts with digests recomputed from their actual bytes; and
- a separately protected manifest, signature, or custody record that binds those digests to the intended generation and origin.

The trust source, verification key or authority, and recovery procedure must be documented and tested separately. A plain SHA-256 digest is an integrity checksum, not a digital signature.

## Status

This document records a limitation of the current implementation. It does not claim that independent baseline authentication or physical-device recovery testing has been implemented.
