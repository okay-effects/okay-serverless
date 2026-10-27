// A POSTGRES FOR THE TESTS, from npm (embedded-postgres: its binaries in node_modules), in a fresh directory:
// started on a free port, the database `okay` made, its URL printed as one line; stopped on SIGTERM.
// `node tools/postgres.mjs`
import EmbeddedPostgres from "embedded-postgres";
import { mkdtempSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";

const free = () => new Promise((ok) => {
  const s = createServer();
  s.listen(0, () => { const p = s.address().port; s.close(() => ok(p)); });
});
const port = await free();
const pg = new EmbeddedPostgres({ databaseDir: join(mkdtempSync(join(tmpdir(), "okay-pg-")), "data"), user: "okay",
  password: "okay", port, persistent: false, onLog: () => {}, onError: () => {} });
await pg.initialise();
await pg.start();
await pg.createDatabase("okay");
console.log(`postgresql://okay:okay@localhost:${port}/okay`);
const stop = async () => { await pg.stop(); process.exit(0); };
process.on("SIGTERM", stop);
process.on("SIGINT", stop);
setInterval(() => {}, 1 << 30);
