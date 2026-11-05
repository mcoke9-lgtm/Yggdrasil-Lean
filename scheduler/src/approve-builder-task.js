import { readFile, lstat } from "node:fs/promises";
import { createInterface } from "node:readline/promises";
import { stdin, stdout } from "node:process";
import { createEncryptedTaskVault } from "./encrypted-task-vault.js";
import { createOvernightDisclosureFingerprint } from "./openai-compatible-executor.js";

function required(value, name) {
  if (typeof value !== "string" || !value.trim()) throw new Error(name + " is required");
  return value.trim();
}

const taskReference = required(process.env.BUILDER_TASK_REFERENCE, "BUILDER_TASK_REFERENCE");
const promptPath = required(process.env.BUILDER_TASK_PROMPT_FILE, "BUILDER_TASK_PROMPT_FILE");
const configPath = required(process.env.BUILDER_HOST_CONFIG_FILE, "BUILDER_HOST_CONFIG_FILE");
const [prompt, configText] = await Promise.all([
  readFile(promptPath, "utf8"),
  readFile(configPath, "utf8")
]);
if (Buffer.byteLength(prompt, "utf8") > 32000) throw new Error("Task prompt exceeds 32 KiB");
const config = JSON.parse(configText);
if (config.version !== 1) throw new Error("Builder host config version must be 1");
const fingerprint = createOvernightDisclosureFingerprint(config.routes, prompt);
const vault = createEncryptedTaskVault();

await vault.initializeTask(taskReference, prompt);
stdout.write("\nNINE BUILDERS — SCHEDULED DISCLOSURE APPROVAL\n");
stdout.write("Task reference: " + taskReference + "\n");
stdout.write("All nine configured Builder routes will receive:\n");
stdout.write("- The scheduled task prompt\n- Run-specific conversation history\n");
stdout.write("- Selected role instructions\n- Operational-limits snapshot\n");
stdout.write("Route endpoint, provider ID, and model ID are included in the approval fingerprint.\n");
stdout.write("No credentials are displayed. Approval does not authorize external actions or deployment.\n\n");
stdout.write("Task prompt:\n" + prompt + "\n\n");
const rl = createInterface({ input: stdin, output: stdout });
try {
  const answer = await rl.question("Type APPROVE to authorize this exact prompt and route set: ");
  if (answer !== "APPROVE") {
    stdout.write("Approval not granted. The encrypted task remains unapproved.\n");
    process.exitCode = 2;
  } else {
    await vault.approveTask(taskReference, fingerprint);
    stdout.write("User approval recorded in the encrypted task vault.\n");
  }
} finally {
  rl.close();
}
