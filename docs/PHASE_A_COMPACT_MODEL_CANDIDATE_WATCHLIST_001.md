# Compact Builder Model Candidate Watchlist — Phase A

**Research date:** 2026-11-05  
**Status:** Research shortlist only. No model is selected, downloaded, licensed for a particular deployment, or tested against the Nine Builder contracts.

The purpose of this watchlist is to identify useful comparison points for the planned compact-seed study. The study's formal `source_candidates` array now records five research-stage model families. Their exact artifact revisions/digests, deployment-specific license review, and baseline evidence remain pending.

## Candidate comparison

| Candidate | Publisher-reported size / context | License indication | Why include it | Important limitation |
|---|---|---|---|---|
| Qwen3.5-0.8B | 0.8B; model card exposes multimodal text/image use | Apache 2.0 | Very small lower-bound baseline; official card explicitly identifies prototyping, task-specific fine-tuning, and research/development as intended use cases | Do not assume it can meet the full Builder suite; the publisher's own intended-use statement is a caution |
| Qwen3.5-2B | 2B family; 262K context; Hugging Face listing shows a 4.57 GB BF16 checkpoint file | Apache 2.0 | Useful small general-purpose comparison; supports thinking and non-thinking modes and an OpenAI-compatible serving path | Actual quantized artifact, runtime memory, latency, and tool reliability must be measured; BF16 file size is not runtime RAM |
| LFM3-1.7B | 1.73B; 256K context; publisher describes about 1 GB at 4-bit | LFM Open License v1.0 | Strongly relevant to local agents, tool calling, and structured outputs; publisher provides GGUF, ONNX, and other deployment variants | Custom license has a $10M annual-revenue commercial-use threshold; publisher cautions against complex multi-step reasoning and agentic coding |
| SmolLM3-3B | 3B; trained at 64K context, up to 128K with YaRN | Apache 2.0 | Useful reconstruction-recipe comparison because the publisher describes public training-data mixture and training configurations | Text-only, six native languages; published recipe/data mixture is not equivalent to possessing every original training datum or being able to recreate the exact checkpoint |
| Gemma 4 E2B | 2.3B effective parameters, 5.1B total including embeddings; 128K context; text, image, audio | Apache 2.0 | Useful multimodal edge comparison and a reminder that effective parameters are not the same as total stored weights | Total parameter count is materially larger than the E2B effective figure; memory fit must be measured, not inferred from the name |

## Initial testing order (proposed)

1. **Qwen3.5-0.8B** — smallest baseline, to measure how much of the shared/role contract can survive at the extreme compact end.
2. **LFM3-1.7B** — agent/tool-use candidate, subject to a license review for the intended deployment and redistribution model.
3. **Qwen3.5-2B** — Apache-licensed small general-purpose comparison.
4. **SmolLM3-3B** — transparent training-recipe comparison for the expandable/trainable-seed question.
5. **Gemma 4 E2B** — multimodal comparison if image/audio capability is required by the actual Builder workload.

This ordering is an experiment sequence, not a ranking of quality or a final recommendation. It may change after host inventory, artifact/license verification, and baseline results.

## Required evidence before adding a candidate to the formal study

- Exact publisher model card and immutable revision/commit.
- Exact weight artifact(s), tokenizer, configuration, quantization, file sizes, and SHA-256 digests.
- License text and a deployment-specific permission assessment, including redistribution and derivative-model conditions.
- Runtime and hardware compatibility, including required custom code and software versions.
- Baseline results on all 23 proposed fixtures, across all nine Builder roles.
- Peak memory, startup time, time-to-first-token, throughput, context budget, and failure rate under comparable settings.
- A clear statement of whether training data, training configuration, adapters, and source weights are actually available.
- No assumption that a smaller student can reconstruct its teacher's missing weights. Any expansion path must identify the external source artifacts, licenses, compute, and data dependencies.

## Source references

- [Qwen3.5-0.8B model card](https://huggingface.co/Qwen/Qwen3.5-0.8B) — Apache 2.0; model card describes the intended prototype/research scope.
- [Qwen3.5-2B model card and files](https://huggingface.co/Qwen/Qwen3.5-2B) — Apache 2.0; current card and checkpoint files.
- [LFM3-1.7B model card](https://huggingface.co/LiquidAI/LFM3-1.7B) — 1.73B, 256K context, deployment variants, agent/tool-use claims and limitations.
- [LFM Open License v1.0](https://www.liquid.ai/lfm-license) — commercial-use revenue threshold and redistribution/attribution conditions.
- [SmolLM3-3B model card](https://huggingface.co/HuggingFaceTB/SmolLM3-3B) — Apache 2.0, model size, context, and public training-data mixture/configuration details.
- [Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4) — Apache 2.0, E2B effective/total parameter distinction, context and modalities.

## Current conclusion

There is no defensible single "smallest Builder model" yet. Qwen3.5-0.8B is a lower-bound baseline, LFM3-1.7B is an agent-oriented candidate with a non-Apache license condition, Qwen3.5-2B offers an Apache-licensed small general-purpose comparison, SmolLM3-3B offers stronger training-process transparency, and Gemma 4 E2B adds multimodal coverage at a larger total weight count.

The compact seed should be selected by measured capability and recovery, not by the smallest parameter count. The model's job is to provide cognition; the non-model runtime carries identity, memory, communication, coordination, authority, and recovery.
