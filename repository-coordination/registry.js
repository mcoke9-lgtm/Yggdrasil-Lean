const ID = /^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,119}$/;
const SHA = /^[a-f0-9]{40}$/i;

function clone(value) {
  return structuredClone(value);
}

function requireId(value, label) {
  if (typeof value !== "string" || !ID.test(value)) {
    throw new TypeError(label + " must be a non-empty safe identifier");
  }
}

function requireSha(value, label) {
  if (typeof value !== "string" || !SHA.test(value)) {
    throw new TypeError(label + " must be a full 40-character Git commit SHA");
  }
}

function requireObject(value, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError(label + " must be an object");
  }
}

export class RepositoryCoordinationRegistry {
  constructor({ clock = () => new Date() } = {}) {
    this.clock = clock;
    this.sessions = new Map();
    this.tasks = new Map();
    this.events = [];
    this.sequence = 0;
    this.queue = Promise.resolve();
  }

  withLock(operation) {
    const result = this.queue.then(operation);
    this.queue = result.catch(() => {});
    return result;
  }

  timestamp() {
    const value = this.clock();
    if (!(value instanceof Date) || !Number.isFinite(value.getTime())) {
      throw new TypeError("clock must return a valid Date");
    }
    return value.toISOString();
  }

  append(type, payload) {
    const event = {
      sequence: ++this.sequence,
      type,
      recordedAt: this.timestamp(),
      payload: clone(payload)
    };
    this.events.push(event);
    return event;
  }

  async registerSession({ sessionId, chatLabel, builderRoles = [] }) {
    return this.withLock(async () => {
      requireId(sessionId, "sessionId");
      if (typeof chatLabel !== "string" || !chatLabel.trim()) {
        throw new TypeError("chatLabel is required");
      }
      if (!Array.isArray(builderRoles) || builderRoles.some(role => typeof role !== "string" || !role.trim())) {
        throw new TypeError("builderRoles must be an array of non-empty strings");
      }
      if (this.sessions.has(sessionId)) throw new Error("Session already registered: " + sessionId);
      const session = {
        sessionId,
        chatLabel: chatLabel.trim(),
        builderRoles: [...new Set(builderRoles)],
        status: "active",
        registeredAt: this.timestamp()
      };
      this.sessions.set(sessionId, session);
      this.append("session.registered", session);
      return clone(session);
    });
  }

  async requestRead({ sessionId, taskId, repository, ref, baseCommitSha, paths = [] }) {
    return this.withLock(async () => {
      requireId(sessionId, "sessionId");
      requireId(taskId, "taskId");
      requireSha(baseCommitSha, "baseCommitSha");
      if (!this.sessions.has(sessionId)) throw new Error("Unknown session: " + sessionId);
      if (typeof repository !== "string" || !/^[^/\s]+\/[^/\s]+$/.test(repository)) {
        throw new TypeError("repository must use owner/name form");
      }
      if (typeof ref !== "string" || !ref.trim()) throw new TypeError("ref is required");
      if (!Array.isArray(paths) || paths.some(path => typeof path !== "string" || !path.trim())) {
        throw new TypeError("paths must be an array of non-empty repository paths");
      }
      if (this.tasks.has(taskId)) throw new Error("Task already registered: " + taskId);
      const task = {
        taskId,
        sessionId,
        repository,
        ref,
        baseCommitSha: baseCommitSha.toLowerCase(),
        paths: [...new Set(paths)],
        access: "read_only",
        status: "reading",
        revision: 1,
        checkpoint: null,
        createdAt: this.timestamp(),
        updatedAt: this.timestamp()
      };
      this.tasks.set(taskId, task);
      this.append("task.read_requested", task);
      return clone(task);
    });
  }

  async recordCheckpoint({ sessionId, taskId, expectedRevision, checkpoint, observedCommitSha }) {
    return this.withLock(async () => {
      requireId(sessionId, "sessionId");
      requireId(taskId, "taskId");
      requireObject(checkpoint, "checkpoint");
      if (observedCommitSha !== undefined) requireSha(observedCommitSha, "observedCommitSha");
      if (!Number.isInteger(expectedRevision) || expectedRevision < 1) {
        throw new TypeError("expectedRevision must be a positive integer");
      }
      const task = this.tasks.get(taskId);
      if (!task) throw new Error("Unknown task: " + taskId);
      if (task.sessionId !== sessionId) throw new Error("Session does not own this task");
      if (task.revision !== expectedRevision) {
        const error = new Error("Checkpoint revision conflict; refresh before retrying");
        error.code = "REVISION_CONFLICT";
        error.expectedRevision = expectedRevision;
        error.currentRevision = task.revision;
        throw error;
      }
      task.checkpoint = clone(checkpoint);
      if (observedCommitSha) task.observedCommitSha = observedCommitSha.toLowerCase();
      task.revision += 1;
      task.updatedAt = this.timestamp();
      this.append("task.checkpoint_recorded", {
        taskId,
        sessionId,
        revision: task.revision,
        checkpoint: task.checkpoint,
        observedCommitSha: task.observedCommitSha ?? null
      });
      return clone(task);
    });
  }

  async listActiveSessions() {
    return this.withLock(async () => {
      return [...this.sessions.values()]
        .filter(session => session.status === "active")
        .map(session => ({
          ...clone(session),
          tasks: [...this.tasks.values()]
            .filter(task => task.sessionId === session.sessionId)
            .map(clone)
        }));
    });
  }

  async exportAuditLog() {
    return this.withLock(async () => clone(this.events));
  }
}
