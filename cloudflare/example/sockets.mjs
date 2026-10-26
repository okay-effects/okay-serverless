// WebSockets to the room object, end to end: two members, a line broadcast to both, the history kept
const port = process.argv[2] || 8799;
const open = (who) => new Promise((ok, fail) => {
  const ws = new WebSocket(`ws://localhost:${port}/room/lobby?who=${who}`);
  ws.got = [];
  ws.onmessage = (e) => ws.got.push(String(e.data));
  ws.onopen = () => ok(ws);
  ws.onerror = (e) => fail(new Error(`${who}: ${e.message || "error"}`));
});
const ann = await open("ann"), bob = await open("bob");
ann.send("hello");
await new Promise((r) => setTimeout(r, 500));
const history = await (await fetch(`http://localhost:${port}/room/lobby`)).text();
const ok = bob.got.join("|") === "ann: hello" && ann.got.join("|") === "ann: hello" && history === "ann: hello";
console.log(ok ? "SOCKETS OK" : `SOCKETS WRONG: ann=${ann.got} bob=${bob.got} history=${history}`);
ann.close(); bob.close();
process.exit(ok ? 0 : 1);
