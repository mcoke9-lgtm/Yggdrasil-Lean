const PREFIX = "BUILDER_SECRET_";

function envName(secretRef) {
  if (typeof secretRef !== "string" || !/^[a-z0-9][a-z0-9/_-]{0,127}$/i.test(secretRef)) {
    throw new TypeError("Secret reference contains unsupported characters");
  }
  return PREFIX + secretRef.toUpperCase().replace(/[^A-Z0-9]/g, "_");
}

/** Resolve only explicitly named operator-provided environment variables. */
export async function readSecret(secretRef) {
  const name = envName(secretRef);
  const value = process.env[name];
  if (typeof value !== "string" || !value.trim()) {
    throw new Error("No operator credential is configured for secret reference " + secretRef);
  }
  return value;
}
