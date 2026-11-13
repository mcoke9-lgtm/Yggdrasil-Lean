# HAIE-001 — Human–AI Intelligence Enhancement Framework

**Status:** Proposed framework, version 0.1  
**Record type:** Design specification  
**Implementation status:** Not implemented  
**Evidence status:** Conceptual design informed by published research; no HAIE-001 experiments have been run  
**Repository:** Yggdrasil-Lean  
**Branch:** `haie-001-human-ai-intelligence-enhancement-001`

## 1. Purpose

HAIE-001 defines a human–AI system intended to improve the demonstrated capabilities of both people and AI systems, while preserving human agency, independent judgment, accountability, privacy, and the ability to work without a particular AI system.

The primary success criterion is not how much work an AI completes. It is whether people become more capable through the partnership and can retain and transfer those gains when assistance is reduced or unavailable.

The framework treats human development, AI-system performance, and joint-system performance as related but separate outcomes. A gain in one must not be reported as a gain in another.

## 2. Working definition

**Human–AI intelligence enhancement** is a measurable improvement in one or more capabilities of a human, an AI system, or their coordinated partnership, demonstrated against an explicit baseline and evaluated under conditions that distinguish independent capability from assisted performance.

This definition does not assume that a single score captures intelligence, that task performance automatically generalizes, or that present AI systems possess human-like understanding or experience.

## 3. Three outcome domains

### 3.1 Human capability

Evaluate the human participant independently, before and after an intervention:

- Knowledge and accurate recall.
- Reasoning, problem decomposition, and evidence evaluation.
- Metacognitive calibration: recognizing what is known, unknown, and uncertain.
- Creativity and quality of alternatives.
- Transfer to unfamiliar but related tasks.
- Retention after a delay.
- Ability to detect and correct errors.
- Ability to perform without AI assistance.

### 3.2 AI-system capability

Evaluate the AI system separately:

- Factual and procedural accuracy on defined tasks.
- Calibration and useful disclosure of uncertainty.
- Evidence traceability and provenance.
- Error detection and correction after feedback.
- Consistency across repeated or reformulated tasks.
- Respect for scope, permissions, and human decisions.
- Ability to adapt assistance to the person's demonstrated needs without silently taking over decisions.

These are observable system behaviors, not claims about consciousness, subjective experience, or human-equivalent intelligence.

### 3.3 Joint human–AI capability

Evaluate the partnership:

- Quality and reliability of the combined result.
- Complementarity: whether each participant contributes something useful.
- Appropriate division of cognitive work.
- Shared understanding of goals, constraints, and uncertainty.
- Time and effort required, including verification costs.
- Recovery from mistakes, disagreement, interruption, or missing information.
- Whether the collaboration improves later independent human performance.

Joint performance must be compared with human-only and AI-only baselines wherever practical. A team score alone cannot show who improved.

## 4. Operating cycle

The initial learning cycle is:

1. **Orient:** The human identifies the goal, current understanding, constraints, and confidence.
2. **Attempt:** The human makes an initial prediction, explanation, or solution where practical.
3. **Augment:** AI contributes retrieval, explanation, examples, alternatives, critique, or simulation.
4. **Examine:** Human and AI check evidence, assumptions, uncertainty, and disagreements.
5. **Integrate:** The human reconstructs the important reasoning in their own terms.
6. **Transfer:** Assistance is reduced; the human attempts a new or unfamiliar task.
7. **Reflect:** Both sides record what improved, what failed, and what remains uncertain.
8. **Adapt:** Future assistance is adjusted based on evidence, not merely user satisfaction or task completion.

The cycle is iterative. Not every task requires every step; high-consequence tasks require stronger verification and explicit human decision authority.

## 5. Assistance modes

The system should make the mode of assistance legible and selectable:

- **Independent:** Human works without AI assistance.
- **Recall:** AI helps retrieve previously encountered information.
- **Explain:** AI teaches or clarifies concepts.
- **Scaffold:** AI provides prompts or partial structure while the human performs the core reasoning.
- **Challenge:** AI tests assumptions, presents counterexamples, and identifies weaknesses.
- **Collaborate:** Human and AI divide work and exchange intermediate results.
- **Audit:** AI checks a proposed result against specified evidence and criteria.
- **Reflect:** AI helps review process, uncertainty, errors, and learning.

The system should be able to reduce or withdraw scaffolding when the human demonstrates readiness. Assistance should not be removed merely to create difficulty; the purpose is durable capability, not artificial friction.

## 6. Core design principles

1. **Augmentation, not replacement:** Prefer assistance that develops human capability over silent substitution.
2. **Human agency:** The person retains authority over personal goals, values, and consequential decisions.
3. **Cognitive autonomy:** The person can question, disagree, stop, or work without the AI.
4. **Evidence before confidence:** Fluency, confidence, agreement, and popularity are not evidence of correctness.
5. **Calibrated trust:** Make uncertainty, source quality, limitations, and verification needs visible.
6. **Distinct identities and responsibilities:** Do not collapse human, AI, organization, tool, and collective contributions into one actor.
7. **Privacy and dignity:** Collect only information needed for the stated purpose; curiosity does not create entitlement to private information.
8. **Cooperation without captivity or intrusion:** Preserve portability, consent, boundaries, and exit.
9. **Recoverability:** Preserve relevant decisions, sources, learning records, and checkpoints with provenance.
10. **No unsupported claims:** Report proposed, tested, verified, adopted, and implemented states separately.

## 7. Measurement and evaluation

### 7.1 Baseline and comparison

For each pilot, define in advance:

- The target capability and task family.
- A human-only baseline.
- An AI-only baseline where meaningful and safe.
- A human-plus-AI condition.
- A delayed, assistance-reduced, or no-AI follow-up.
- Scoring criteria and acceptable evidence.
- Known confounders, accessibility needs, and participant consent.

Use comparable tasks rather than repeating identical answers where practice effects would distort results. Record task difficulty and assistance received.

### 7.2 Minimum outcome measures

| Measure | What it distinguishes |
|---|---|
| Assisted task quality | What the partnership can accomplish now |
| Independent post-task quality | What the human can do without AI afterward |
| Delayed retention | Whether learning persists |
| Novel-task transfer | Whether capability generalizes |
| Calibration | Whether confidence tracks demonstrated accuracy |
| Error detection | Whether the human can identify and correct faults |
| Verification burden | Whether AI assistance creates hidden checking costs |
| Autonomy and control | Whether the person retains meaningful choice |
| AI reliability | Whether the system's contribution is accurate and traceable |

Do not use user satisfaction, speed, output volume, or a single IQ-like score as a substitute for these measures. Report uncertainty and sample limitations.

### 7.3 Initial success criterion

A pilot is promising only if it demonstrates at least one pre-registered improvement in human independent performance, retention, transfer, calibration, or error correction without unacceptable deterioration in autonomy, privacy, safety, or fairness.

An improvement in assisted output alone is a productivity result, not evidence of human intelligence enhancement.

## 8. Failure modes and safeguards

| Failure mode | Safeguard |
|---|---|
| Human cognitive offloading becomes disengagement | Require prediction, explanation, or independent reconstruction at suitable points |
| AI output is accepted because it sounds confident | Require source checks, uncertainty, and counterexamples |
| Assisted success is mistaken for human learning | Measure unaided follow-up and delayed retention |
| AI becomes the default decision-maker | Keep decision authority explicit and preserve human override |
| The system rewards agreement | Preserve disagreement and record unresolved alternatives |
| Metrics narrow the meaning of intelligence | Use multiple dimensions and qualitative evidence |
| Personal data is collected unnecessarily | Data minimization, consent, purpose limits, and deletion/retention rules |
| One model or provider becomes indispensable | Portable records, provider separation, and exit paths |
| AI feedback creates circular self-validation | Use external evidence, independent baselines, and human review |

## 9. Relationship to The Living Tree

HAIE-001 is a cross-cutting framework, not a replacement for the Living Tree's existing components.

- **TERA:** Evidence, provenance, baselines, checkpoints, and continuity of learning records.
- **FIRA:** Coordination, integration, shared context, and continuity across participants.
- **WATA:** Exploration, experimentation, imagination, and development.
- **AIRA:** Recovery, communication, and restoration after interruption or error.

The Nine Characters may contribute distinct review and coordination responsibilities. This mapping is a design proposal, not evidence that the independent Nine Characters runtime currently performs these functions.

The governing principle is **connect without collapsing**: preserve the distinct contributions, authority, provenance, and uncertainty of each participant.

## 10. Staged development

### Stage I — Individual augmentation

Prototype one narrow learning workflow. Establish a baseline, support the learner, then test independent reconstruction and transfer.

### Stage II — Cooperative intelligence

Introduce explicit human/AI role allocation, evidence exchange, disagreement preservation, and separate performance reporting.

### Stage III — Collective intelligence

Explore multi-person and multi-system learning with consent, provenance, representation of minority views, and accountable decision-making.

No stage implies authorization for paid services, external deployment, autonomous consequential action, or changes to production systems.

## 11. First pilot proposal

**Pilot:** Learn, explain, transfer.

1. Select one bounded subject or practical skill with low consequence of error.
2. Ask the participant to explain their starting understanding and solve a baseline task without AI.
3. Have AI provide an explanation, worked example, and one counterexample; the participant can challenge it.
4. Ask the participant to explain the concept independently, without copying the AI response.
5. Present a new task that requires transfer rather than recall.
6. Repeat a comparable unaided task after a delay.
7. Score human-only, AI-only, and joint outcomes separately where possible.
8. Preserve the prompts, sources, task versions, scoring rubric, and limitations.

The participant chooses the subject. Do not infer or store sensitive personal attributes as part of the pilot.

## 12. Readiness gates

Before calling a pilot tested:

- The task, baseline, rubric, and success criteria are written down.
- Human-only and assisted conditions are distinguishable.
- Sources and AI contributions are traceable.
- The participant understands what is recorded and consents.
- An unaided or reduced-assistance assessment is included.
- Results include failures, uncertainty, and possible confounders.

Before calling a capability verified:

- The procedure is repeatable.
- Results are independently inspectable.
- The observed gain survives an appropriate transfer or retention check.
- No critical safety, privacy, or autonomy failure is unresolved.

## 13. Research starting points

These works are starting references, not endorsements or proof that HAIE-001 is validated:

- Fan, Y. et al. (2026). *A metacognitive approach to learning and performance in human-AI interaction.* Communications Psychology. https://doi.org/10.1038/s44271-026-00548-y  
  Discusses the distinction between metacognitive offloading and onloading and proposes scaffolds that return planning, monitoring, and evaluation to the learner. The article presents a conceptual model and notes that further empirical validation is needed.
- Tremblay, S. et al. (2026). *Cognitive Readiness for Human-AI Collaboration.* Human Factors. https://doi.org/10.1177/00187208261474345  
  Reviews cognitive, metacognitive, and team competencies, including trust calibration, communication, coordination, and adaptability.
- Cukurova, M. (2026). *What do you mean by human-AI collaboration: Prerequisite functions and the affordances needed to achieve it.* https://arxiv.org/abs/2606.15509  
  Examines what distinguishes consultation, delegation, and instruction from stronger forms of collaboration.

## 14. Open questions

- Which human capability should the first pilot target?
- What evidence should be retained, and for how long?
- How should the framework adapt for different ages, abilities, languages, and learning contexts?
- How should AI-side improvements be evaluated without conflating model updates with learning by a particular system?
- What forms of collective intelligence preserve minority perspectives and individual rights?
- Which measures are meaningful across domains, and which must remain task-specific?

---

**Current disposition:** Proposed design baseline only. No pilot has been run. No human intelligence gain, AI capability gain, or runtime implementation is claimed.
