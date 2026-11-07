const ID_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}$/;

function clone(value) {
  return structuredClone(value);
}

/**
 * Convert an explicitly classified capacity rejection into an immutable
 * handoff request. This function does not transmit, persist, accept, or
 * activate a task; the caller must submit the returned request to a
 * configured HandoffInbox and inspect its persistence receipt.
 */
export function createCapacityHandoffRequest({
  rejection,
  handoffId,
  sourceScheduler,
  taskDefinition,
  taskDefinitionVersion = null,
  authorization
}) {
  if (!rejection || typeof rejection !== "object" || Array.isArray(rejection)) {
    throw new TypeError("A structured scheduler rejection is required");
  }
  if (rejection.code !== "CAPACITY_REACHED") {
    return { eligible: false, reason: "rejection_is_not_capacity_related" };
  }
  if (typeof handoffId !== "string" || !ID_PATTERN.test(handoffId)) {
    throw new TypeError("A stable handoffId is required");
  }
  if (typeof sourceScheduler !== "string" || !ID_PATTERN.test(sourceScheduler)) {
    throw new TypeError("A stable sourceScheduler ID is required");
  }
  if (!taskDefinition || typeof taskDefinition !== "object" || Array.isArray(taskDefinition)) {
    throw new TypeError("The complete original taskDefinition is required");
  }
  if (authorization === undefined || authorization === null) {
    throw new TypeError("Explicit authorization evidence is required");
  }

  return {
    eligible: true,
    request: {
      handoffId,
      sourceScheduler,
      taskDefinition: clone(taskDefinition),
      taskDefinitionVersion,
      authorization: clone(authorization),
      sourceRejectionEvidence: clone(rejection)
    }
  };
}
