// BufferSynk phone simulator — E2E test client for the PC server.
// Run: node phone-sim.mjs [host] [port]
import { readFileSync, existsSync, writeFileSync, unlinkSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const host = process.argv[2] || "127.0.0.1";
const port = parseInt(process.argv[3] || "8765", 10);
const base = `http://${host}:${port}`;
const settingsPath = join(process.env.APPDATA || join(homedir(), "AppData", "Roaming"), "BufferSynk", "settings.json");

let pass = 0, fail = 0;
const ok = (name, cond, extra = "") => {
  if (cond) { pass++; console.log(`  PASS  ${name}`); }
  else { fail++; console.log(`  FAIL  ${name} ${extra}`); }
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  console.log(`== BufferSynk E2E against ${base} ==`);

  // 0. ping
  const ping = await (await fetch(`${base}/api/ping`)).json();
  ok("ping", ping.ok === true, JSON.stringify(ping));
  console.log(`       pc=${ping.pc} proto=${ping.proto}`);

  // 1. unauthorized upload rejected
  const unauth = await fetch(`${base}/api/upload?name=x.txt`, { method: "POST", body: "evil" });
  ok("upload without token rejected", unauth.status === 401, `status=${unauth.status}`);

  // 2. pair with wrong PIN
  const badPair = await (await fetch(`${base}/api/pair`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ pin: "000000" }),
  })).json();
  ok("pair wrong PIN rejected", badPair.ok === false, JSON.stringify(badPair));

  // 3. pair with correct PIN (read from PC settings)
  const token = readFileSync(settingsPath, "utf8") ? JSON.parse(readFileSync(settingsPath, "utf8")).token : null;
  ok("settings token readable", typeof token === "string" && token.length >= 32);

  // 4. WS connect with bad token -> should fail
  let badWs = false;
  try {
    const wsBad = new WebSocket(`ws://${host}:${port}/ws?token=0000`);
    await new Promise((res, rej) => { wsBad.onopen = res; wsBad.onerror = () => rej(new Error("err")); wsBad.onclose = (e) => rej(new Error("closed " + e.code)); });
    wsBad.close();
  } catch { badWs = true; }
  ok("ws bad token rejected", badWs);

  // 5. WS connect with good token
  const ws = new WebSocket(`ws://${host}:${port}/ws?token=${token}`);
  const inbox = [];
  ws.onmessage = (ev) => { try { inbox.push(JSON.parse(ev.data)); } catch { inbox.push({ raw: ev.data }); } };
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error("ws error")); });
  ok("ws open with token", ws.readyState === 1);

  // 6. hello -> welcome
  ws.send(JSON.stringify({ type: "hello", deviceName: "SimPhone", deviceId: "sim-001", token }));
  await sleep(500);
  const welcome = inbox.find((m) => m.type === "welcome");
  ok("hello->welcome", welcome && welcome.ok === true, JSON.stringify(inbox));

  // 7. phone -> PC clipboard (text)
  const clipText = "Привет с телефона " + Date.now();
  ws.send(JSON.stringify({ type: "clipboard", kind: "text", text: clipText, hash: "simhash1" }));
  await sleep(700);
  const histPath = join(process.env.APPDATA, "BufferSynk", "history.json");
  const hist = existsSync(histPath) ? JSON.parse(readFileSync(histPath, "utf8")) : { entries: [] };
  ok("clipboard text landed in PC history",
    hist.entries.some((e) => e.preview && clipText.startsWith(e.preview.slice(0, 20)) && e.direction === "fromPhone"),
    `entries=${hist.entries.length}`);

  // 8. phone -> PC file upload
  const tmpFile = join(process.env.TEMP || process.env.TMP || ".", "phone-sim-test.txt");
  const content = "test file from phone " + Date.now();
  writeFileSync(tmpFile, content);
  const up = await (await fetch(`${base}/api/upload?token=${token}&name=sim-upload.txt`, {
    method: "POST", body: content,
  })).json();
  ok("file upload accepted", up.ok === true && up.size === content.length, JSON.stringify(up));

  // 9. PC stores file in SaveFolder
  const saveFolder = JSON.parse(readFileSync(settingsPath, "utf8")).saveFolder;
  const savedPath = join(saveFolder, up.name);
  ok("file exists in save folder", existsSync(savedPath));
  if (existsSync(savedPath)) ok("file content intact", readFileSync(savedPath, "utf8") === content);
  try { unlinkSync(tmpFile); } catch {}

  // 10. ping/pong keepalive
  ws.send(JSON.stringify({ type: "ping", ts: Date.now() }));
  await sleep(400);
  ok("ping->pong", inbox.some((m) => m.type === "pong"));

  // 10b. mediaControl from phone is accepted (no media session headless -> silent no-op)
  inbox.length = 0;
  ws.send(JSON.stringify({ type: "mediaControl", action: "toggle", ts: Date.now() }));
  ws.send(JSON.stringify({ type: "ping", ts: Date.now() }));
  await sleep(400);
  ok("mediaControl accepted (socket alive)", inbox.some((m) => m.type === "pong"));

  // 10c. notif from phone is accepted (should not crash server, socket stays alive)
  inbox.length = 0;
  ws.send(JSON.stringify({
    type: "notif",
    key: "sim:notif:1",
    pkg: "com.example.messenger",
    appName: "Messenger",
    title: "Test",
    text: "Hello from sim",
    replyable: false,
    origin: "phone",
    ts: Date.now(),
  }));
  ws.send(JSON.stringify({ type: "ping", ts: Date.now() }));
  await sleep(400);
  ok("notif accepted (socket alive)", inbox.some((m) => m.type === "pong"));

  // 10d. notifRemoved accepted
  inbox.length = 0;
  ws.send(JSON.stringify({ type: "notifRemoved", key: "sim:notif:1", ts: Date.now() }));
  ws.send(JSON.stringify({ type: "ping", ts: Date.now() }));
  await sleep(400);
  ok("notifRemoved accepted (socket alive)", inbox.some((m) => m.type === "pong"));

  // 10e. notifReply accepted
  inbox.length = 0;
  ws.send(JSON.stringify({ type: "notifReply", key: "sim:notif:1", text: "reply", ts: Date.now() }));
  ws.send(JSON.stringify({ type: "ping", ts: Date.now() }));
  await sleep(400);
  ok("notifReply accepted (socket alive)", inbox.some((m) => m.type === "pong"));

  // 11. PC -> phone clipboard arrives on ws (we can't force PC clipboard headlessly here,
  //     covered by manual step; instead verify file-offer + download roundtrip:)
  // stage an offer from PC side by asking server via outbox — simulate by direct GET of unknown id
  const missing = await fetch(`${base}/api/file/nonexistent?token=${token}`);
  ok("file download unknown id -> 404", missing.status === 404, `status=${missing.status}`);

  // 12. wrong token download rejected
  const badDl = await fetch(`${base}/api/file/nonexistent?token=bad`);
  ok("file download bad token -> 401", badDl.status === 401, `status=${badDl.status}`);

  // 13. file-offer roundtrip: spawn second instance with --send, expect offer + download
  const exePath = process.argv[4];
  if (exePath) {
    const { spawn } = await import("node:child_process");
    const offerFile = join(process.env.TEMP || ".", "offer-src.txt");
    const offerContent = "offer payload " + Date.now();
    writeFileSync(offerFile, offerContent);

    const before = inbox.length;
    spawn(exePath, ["--send", offerFile], { detached: false, stdio: "ignore" }).on("error", (e) => console.log("spawn error:", e.message));
    await sleep(1500);
    const offer = inbox.slice(before).find((m) => m.type === "fileOffer");
    ok("fileOffer received over ws", !!offer, JSON.stringify(inbox.slice(before)));

    if (offer) {
      const dl = await fetch(`${base}/api/file/${offer.fileId}?token=${token}`);
      const dlText = await dl.text();
      ok("offer download ok", dl.status === 200 && dlText === offerContent, `status=${dl.status}`);
      ok("offer metadata matches", offer.fileName === "offer-src.txt" && offer.fileSize === offerContent.length,
        JSON.stringify(offer));
      try { unlinkSync(offerFile); } catch {}
    }
  } else {
    console.log("  SKIP  file-offer (pass exe path as 4th arg to enable)");
  }

  ws.close();
  console.log(`\n== Result: ${pass} passed, ${fail} failed ==`);
  process.exit(fail > 0 ? 1 : 0);
}

main().catch((e) => { console.error("E2E crashed:", e); process.exit(2); });
