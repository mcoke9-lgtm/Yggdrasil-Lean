const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

async function main() {

const html = fs.readFileSync("yim-001/phone-intake/index.html", "utf8");
const match = html.match(/<script>([\s\S]*?)<\/script>/i);
assert.ok(match, "phone intake page must contain its local application script");

class Element {
  constructor(id = "") {
    this.id = id; this.value = ""; this.textContent = ""; this.disabled = false;
    this.files = []; this.listeners = {}; this.children = []; this.download = "";
  }
  addEventListener(name, callback) { this.listeners[name] = callback; }
  append(...items) { this.children.push(...items); }
  replaceChildren(...items) { this.children = [...items]; }
  remove() {}
  click() {
    if (this.download) downloads.push({ name: this.download, blob: lastBlob });
    if (this.listeners.click) this.listeners.click({ target: this });
  }
}
const ids = ["queueId","newQueue","openQueue","queueFile","queueStatus","sourceKind",
  "sourceReference","language","content","metadata","addPacket","actionStatus",
  "packets","exportQueue"];
const elements = Object.fromEntries(ids.map(id => [id, new Element(id)]));
elements.queueId.value = "test-phone";
elements.sourceKind.value = "chat_exchange";
elements.language.value = "English";
elements.metadata.value = "{}";
const downloads = [];
let lastBlob = null;
const document = {
  body: new Element("body"),
  getElementById(id) { return elements[id] ||= new Element(id); },
  createElement(tag) { return new Element(tag); }
};
class TestBlob {
  constructor(parts, options) { this.parts = parts; this.type = options.type; lastBlob = this; }
  async text() { return this.parts.join(""); }
}
const URLApi = {
  createObjectURL(blob) { lastBlob = blob; return "blob:test"; },
  revokeObjectURL() {}
};
const context = {
  document, Blob: TestBlob, URL: URLApi,
  crypto: { randomUUID: () => "packet-test-001" },
  console, Date, JSON, Math, Number, Set, Array, Error
};
vm.runInNewContext(match[1], context, { filename: "phone-intake.js" });

elements.newQueue.click();
assert.match(elements.queueStatus.textContent, /New empty queue ready/);
elements.content.value = JSON.stringify({ text: "Original source wording", nested: [1, true, null] });
elements.addPacket.click();
assert.match(elements.actionStatus.textContent, /Preserved packet/);
assert.equal(elements.exportQueue.disabled, false);
elements.exportQueue.click();
assert.equal(downloads.length, 1, "export must initiate one download");
assert.equal(downloads[0].name, "test-phone-intake.json");
const exported = JSON.parse(await downloads[0].blob.text());
assert.equal(exported.format, "YIM-001-Work-Intake-Queue");
assert.equal(exported.format_version, 2);
assert.equal(exported.queue_id, "test-phone");
assert.equal(exported.packets.length, 1);
const packet = exported.packets[0];
assert.equal(packet.packet_id, "packet-test-001");
assert.deepEqual(packet.content, { text: "Original source wording", nested: [1, true, null] });
assert.equal(packet.placement_status, "awaiting_placement");
assert.equal(packet.source_content_preserved_as_submitted, true);
assert.equal(packet.primary_logical_location, null);
assert.deepEqual(packet.contextual_logical_locations, []);

// Reopening an exported queue must preserve its packet and source content.
elements.queueFile.files = [{ name: "roundtrip.json", text: async () => JSON.stringify(exported) }];
await elements.queueFile.listeners.change({ target: elements.queueFile });
assert.match(elements.queueStatus.textContent, /Loaded 1 packet/);
assert.equal(elements.queueId.value, "test-phone");

// Reject malformed imported placement data rather than silently accepting it.
const malformed = structuredClone(exported);
Object.assign(malformed.packets[0], {
  placement_status: "imported", primary_logical_location: "roots.archive",
  contextual_logical_locations: ["branches.project", "branches.project"],
  imported_record_id: "record-1", imported_version_id: "version-1"
});
elements.queueFile.files = [{ name: "bad-placement.json", text: async () => JSON.stringify(malformed) }];
await elements.queueFile.listeners.change({ target: elements.queueFile });
assert.match(elements.queueStatus.textContent, /invalid or duplicate Tree placements/);

console.log("Phone intake browser-logic tests passed: create, preserve, export, reopen, reject invalid placement.");
}

main().catch(error => { console.error(error); process.exitCode = 1; });
