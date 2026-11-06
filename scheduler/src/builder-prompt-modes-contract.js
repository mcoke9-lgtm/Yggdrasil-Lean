const DIFFICULT_SPACES_MARKER = "NAVIGATING_DIFFICULT_SPACES_MODE\n";
const REVIEW_MARKER = "BUILDER_IMPROVEMENT_REVIEW_MODE\nincident_turn_id=";

const REVIEW_DUTIES = Object.freeze({
  HUGINN: "checks scope and acceptance criteria",
  MUNINN: "checks history and provenance",
  KVASIR: "checks source evidence and uncertainty",
  MARI: "checks interfaces and cooperation",
  HODR: "checks implementation and tests",
  TYR: "checks authority and security",
  LOKI: "develops alternatives and recovery paths",
  ODIN: "reconciles dependencies and unresolved differences",
  BALDR: "independently checks the proposed correction against evidence and requirements"
});

export function buildBuilderModeInstructions(role, taskPrompt, history = []) {
  const last = history.length ? history[history.length - 1] : null;
  const trigger = typeof last?.body === "string" ? last.body :
    (typeof taskPrompt === "string" ? taskPrompt : "");
  const instructions = [];

  if (trigger.startsWith(DIFFICULT_SPACES_MARKER)) {
    instructions.push(
      "NINE BUILDERS OPERATING MODE: NAVIGATING DIFFICULT SPACES.",
      "Purpose: interact with protection and boundaries, not bypass or defeat them.",
      "LOWER FIRA / THURISAZ: identify the encountered barrier; document its observable behavior, scope, severity, protected interest if known, and permitted interface. Do not infer motive from the barrier alone.",
      "UPPER FIRA / ALGIZ: identify the protective authority, owner, steward, or system that maintains the boundary; determine its stated purpose, legitimate authorization path, and conditions for cooperation. Do not presume authority where it is unknown.",
      "Keep observed facts separate from inference and unknowns. Record who controls missing information and what evidence or authorized request could resolve it.",
      "Investigate documented interfaces, public documentation, supported APIs, diagnostics, logs available to us, and legitimate support channels. Never evade access controls, exploit vulnerabilities, impersonate an authorized party, expose credentials, or weaken protections to obtain access.",
      "Choose and explain one outcome: COOPERATE, REQUEST_AUTHORIZATION, ADAPT, WAIT_FOR_DEPENDENCY, or RESPECT_REFUSAL. Preserve work and offer a safe next step.",
      "Report what the Builders can do, what requires the user's authorization or physical action, and the smallest useful human intervention. Do not transfer routine technical work to the user merely because a boundary exists.",
      "When a human action is necessary, create a HANDOFF addressed to USER. Explain the exact action needed, why the Builders cannot perform it, what protection or authority requires it, the safe place to perform it, what confirmation to report, and what the Builders will do immediately afterward. Ask for one necessary action at a time. Do not send the user away to read a checklist or technical document when the Builders can guide the action conversationally. Never ask the user to paste secrets into chat.",
      "The user remains director and retains final authority for consequential commitments."
    );
  }

  const markerAt = trigger.indexOf(REVIEW_MARKER);
  if (markerAt >= 0) {
    const valueStart = markerAt + REVIEW_MARKER.length;
    const valueEnd = trigger.indexOf("\n", valueStart);
    const incidentId = trigger.slice(valueStart, valueEnd < 0 ? undefined : valueEnd).trim();
    const incident = history.find(turn =>
      turn?.turnId === incidentId && turn?.type === "IMPROVEMENT_INCIDENT"
    );
    if (!incident) throw new Error("Improvement review must reference a recorded incident in this run's thread");
    const affected = incident.speaker;
    if (typeof affected !== "string" || !Object.hasOwn(REVIEW_DUTIES, affected)) {
      throw new Error("Improvement review incident has an unknown affected Builder");
    }
    instructions.push(
      "IMPROVEMENT REVIEW MODE was explicitly initiated by the user. Review only the recorded incident " + incidentId + ". The affected Builder is " + affected + ".",
      "The affected Builder may explain its observations and reasoning, but MUST NOT evaluate, approve, or certify its own correction. Other roles must assess independently.",
      "Role review duties: " + Object.entries(REVIEW_DUTIES).map(([name, duty]) => name + " " + duty).join("; ") + ".",
      "If Baldr is not selected, explicitly mark independent verification MISSING. Never imply that a prompt response alone is a test or verification. Do not treat agreement as adoption. Record findings, disagreement, missing reviewers, and the next evidence-bearing step. Any reviewer may issue INTERRUPTION if the review method is unsuitable.",
      "When the review supports a concrete correction, create a CORRECTION_PLAN turn addressed to USER and replying to the incident turn. Its body is JSON with correction_id, incident_turn_id, objective, acceptance_criteria, steps, owner, rollback_condition, status=PROPOSED. Do not create a plan if evidence or authority is insufficient; state what is missing.",
      "Correction lifecycle is append-only and strictly ordered: PROPOSED (plan) -> IMPLEMENTED -> TESTED -> VERIFIED -> ADOPTED. Use CORRECTION_UPDATE only for IMPLEMENTED, TESTED, or VERIFIED, with JSON fields correction_id, incident_turn_id, stage, evidence, artifact_reference, acceptance_criteria_result, unresolved. stage must equal evidence_status. Each update must reply to the immediately preceding lifecycle record for that correction. IMPLEMENTED requires an artifact/commit/file reference; TESTED requires an actual test name/run/result; VERIFIED requires evidence checked against every acceptance criterion. Only an unaffected Baldr may issue VERIFIED after TESTED. Never issue ADOPTED: only the user may record adoption. Adoption records acceptance as project practice and does not authorize deployment or external action."
    );
  }
  return instructions.length ? "\n" + instructions.join("\n") : "";
}
