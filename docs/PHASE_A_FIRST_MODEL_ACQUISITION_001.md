# Phase A — first compact-model acquisition protocol

## Selected baseline

- Family: Qwen3.5-0.8B
- Artifact: `artifact.qwen3_5_0_8b.bf16`
- Immutable repository revision: `2fc06364715b967f1860aea9cf38778875588b17`
- Weight file: `model.safetensors-00001-of-00001.safetensors`
- Expected size: 1,746,942,600 bytes
- Publisher-reported SHA-256: `04b1c301231dd422b8860db31311ab2721511346a32cb1e079c4c4e5f1fe4696`
- Publisher model card: https://huggingface.co/Qwen/Qwen3.5-0.8B
- License label on the publisher model card: Apache-2.0; deployment-specific license review is still recorded as pending in the study inventory.

The model card describes this as a research/prototyping and task-specific fine-tuning model. It is a multimodal model with a vision encoder, despite the small language-model parameter count. The publisher reports a native 262,144-token context, but that is not an appropriate starting runtime setting for a low-resource host. The first capability experiment should use text-only prompts and a modest context window once a compatible serving engine and hardware are known.

## Safe acquisition

The new script is read-only by default. It reports host platform, architecture, available memory, free disk, pinned revision, expected size, and required disk headroom. It does not create the destination directory or contact the model host unless `--download` is supplied.

From the repository root:

```bash
python scripts/prepare_phase_a_candidate.py --destination /absolute/path/to/yggdrasil-models --report /absolute/path/to/qwen-preflight.json
```

Review the report. The preflight requires the model's exact byte size plus 1 GiB of free disk. Available host memory is reported as context only; it is not an inference peak-memory prediction.

Only after reviewing the destination and confirming the transfer is acceptable:

```bash
python -m pip install --upgrade huggingface_hub
python scripts/prepare_phase_a_candidate.py --destination /absolute/path/to/yggdrasil-models --download --report /absolute/path/to/qwen-acquisition.json
```

The download is pinned to the immutable repository revision and limited to the model weight plus model/tokenizer/processor configuration files. The script then hashes the actual weight file and compares both size and SHA-256 with the publisher's Git LFS pointer. A mismatch returns failure and is not recorded as verified. The local report is kept separate from the canonical repository inventory.

## Boundaries

- No model is downloaded by CI.
- No model is downloaded by default when this script is run.
- No endpoint is started and no external model API is called.
- No credentials are needed or read.
- No phone, NUC, or other device is modified by repository changes.
- Do not change the canonical artifact inventory to `checksum_verified` merely because the publisher supplied a hash. Only a successful local hash comparison supports that status.
- After acquisition, inspect the actual host resources and choose a compatible inference runtime before launching a server.
- Begin with a small text-only smoke test; expand to the full 63-capability / 72-call suite only after the smoke test, response parsing, resource limits, and data-handling boundary are reviewed.

## Current state

This protocol is implemented and covered by mock/read-only tests. It is not evidence that the model has been downloaded, locally verified, loaded, or evaluated. The first acquisition still requires an explicit `--download` invocation on the chosen host.
