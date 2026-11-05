# Compact Builder Model Candidate Watchlist — Phase A

**Research date:** 2026-11-05  
**Status:** Research shortlist only. No model is selected, downloaded, licensed for a particular deployment, or tested against the Nine Builder contracts.

The purpose of this watchlist is to identify useful comparison points for the planned compact-seed study. The study's formal `source_candidates` array records five research-stage model families. Qwen3.5-0.8B, Qwen3.5-2B, and LFM3-1.7B now have pinned repository revisions. LFM3 also has a pinned Q4_K_M GGUF variant and displayed size, but its file SHA-256, deployment-specific license review, and baseline evidence remain pending.

## Candidate comparison

| Candidate | Publisher-reported size / context | License indication | Why include it | Important limitation |
|---|---|---|---|---|
| Qwen3.5-0.8B | 0.8B language model; 262K context; 1.75 GB BF16 weight file; pinned revision 2fc0636 | Apache 2.0 | Very small lower-bound baseline; official card identifies prototyping, task-specific fine-tuning, and research/development as intended use cases; published MMLU-Pro scores are modest (29.7 non-thinking, 42.3 thinking) | Do not assume it can meet the full Builder suite; the publisher's own intended-use statement is a caution |
| Qwen3.5-2B | 2B language model; 262K context; 4.55 GB BF16 weight file; pinned revision 15852e8 | Apache 2.0 | Useful small general-purpose comparison; supports thinking and non-thinking modes and an OpenAI-compatible serving path | Actual quantized artifact, runtime memory, latency, and tool reliability must be measured; BF16 file size is not runtime RAM |
| LFM3-1.7B | 1.73B; 256K context; official GGUF repository lists Q4_K_M at about 1.1 GB; publisher reports about 1 GB memory at 4-bit | LFM Open License v1.0 | Strongly relevant to local agents, tool calling, and structured outputs; publisher documents multi-domain distillation from six specialist RL teachers back into one model | Custom license has a $10M annual-revenue commercial-use threshold; despite its agentic-training claims, publisher cautions against complex multi-step reasoning and agentic coding |
| SmolLM3-3B | 3B; trained at 64K context, up to 128K with YaRN | Apache 2.0 | Useful reconstruction-recipe comparison because the publisher describes public training-data mixture and training configurations | Text-only, six native languages; published recipe/data mixture is not equivalent to possessing every original training datum or being able to recreate the exact checkpoint |
| Gemma 4 E2B | 2.3B effective parameters, 5.1B total including embeddings; 128K context; text, image, audio; Google lists an approximately 1.1 GB mobile inference memory figure | Apache 2.0 | Useful multimodal edge comparison and a reminder that effective parameters are not the same as total stored weights | Total parameter count is materially larger than the E2B effective figure; memory fit must be measured, not inferred from the name |

The inventory records exact file byte counts and SHA-256 values from the publishers' Git LFS pointer files. Those hashes are publisher-reported only: the files have not been downloaded and locally hashed. File size is not runtime RAM. The separate safetensors tensor counts and publisher-reported language-model parameter counts are retained distinctly in the machine-readable inventory.

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

- [Qwen3.5-0.8B model card](https://huggingface.co/Qwen/Qwen3.5-0.8B) — Apache 2.0; intended prototype/research scope and publisher benchmark table. [Model API](https://huggingface.co/api/models/Qwen/Qwen3.5-0.8B) — pinned revision and safetensors parameter counts.
- [Qwen3.5-2B model card and files](https://huggingface.co/Qwen/Qwen3.5-2B) — Apache 2.0; current card and checkpoint files. [Model API](https://huggingface.co/api/models/Qwen/Qwen3.5-2B) — pinned revision and safetensors parameter counts.
- [LFM3-1.7B model card](https://huggingface.co/LiquidAI/LFM3-1.7B) — 1.73B, 256K context, approximately 1 GB at 4-bit, six-teacher distillation stage, agent/tool-use claims and limitations. [Official GGUF repository](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/tree/main) — Q4_K_M variant displayed at 1.1 GB and revision cd0c74c.
- [LFM Open License v1.0](https://www.liquid.ai/lfm-license) — commercial-use revenue threshold and redistribution/attribution conditions.
- [SmolLM3-3B model card](https://huggingface.co/HuggingFaceTB/SmolLM3-3B) — Apache 2.0, model size, context, and public training-data mixture/configuration details.
- [Gemma 4 model card](https://gemma.ai/models/gemma-4/model-card/) — Apache 2.0, E2B effective/total parameter distinction, context and modalities. [Inference memory table](https://ai.google.dev/gemma/docs/core.md) — approximate memory by precision and mobile deployment.

## Current conclusion

There is no defensible single "smallest Builder model" yet. Qwen3.5-0.8B is a lower-bound baseline, LFM3-1.7B is an agent-oriented candidate with a multi-teacher distillation precedent and a non-Apache license condition, Qwen3.5-2B offers an Apache-licensed small general-purpose comparison, SmolLM3-3B offers stronger training-process transparency, and Gemma 4 E2B adds multimodal coverage at a larger total weight count.

The compact seed should be selected by measured capability and recovery, not by the smallest parameter count. The new local-first runner generates 63 Builder-assigned calls and retains raw evidence; it has not yet been run against a model. Qwen3.5-0.8B's publisher-reported benchmark results reinforce why it is a lower-bound test, not an assumed final Builder model. The model's job is to provide cognition; the non-model runtime carries identity, memory, communication, coordination, authority, and recovery.
