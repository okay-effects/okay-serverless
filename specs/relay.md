# Sealed rooms and Telegram (spec, 2026-10-28)

Two modules for apps that reach a person's own machine from their phone (okay-remote is the first), each general.

## `relay`: a sealed room

```text
guest (a browser) ──wss──▶ Rooms (an actor a room: the sockets' tags only) ◀──wss── host (a machine)
                                     every frame sealed end to end; the room cannot read it
```

- **Pairing**: a secret S (32 bytes) the host makes and shows (a QR code's fragment). From it, by SHA-256 with the
  app's label: `enc` the AES-256-GCM key, `auth` (hex), `room = hex SHA-256(auth)`. Both ends dial
  `/r/<room>/{host|guest}?auth=<auth>`; the room lets in only an `auth` whose SHA-256 is its name. The room never has
  `enc`.
- **Rooms** (`Actor`): one host (the newest; an older one closed, 4000), any number of guests. A frame from a guest goes
  to the host, from the host to every guest; the guests told in plain words whether the host is there (`online` /
  `offline`); `ping` answered `pong`. The host's control lines (`ctl:…`) are never passed: they go to the app's
  `Steward` — with the host's tags (set when it dialled, from its request, by the steward: a bound chat) and the
  requests to the room that are not a socket's (`call`). Frames at most 2.8 MB. Nothing in storage.
- **A frame** (`Envelope`): `base64url(iv ‖ AES-GCM(enc, iv, json, aad "<label>/1"))`; the JSON says who sent it,
  when, a nonce; a frame from the wrong side, out of the window (5 minutes), or seen before, is not taken.
- **Sealing**: `Sealer` — JCA on the JVM, WebCrypto in a browser. The host's client on the JVM is `RelayClient`
  (the JDK's WebSocket: dials out, dials again, held notifications, control lines); a guest in a browser `Guest`.
- **Tokens**: a short signed token (HMAC-SHA-256, a deadline) naming a room — a link that binds something to the room
  later (a chat), with no state kept.
- `Hashes`: SHA-256, HMAC-SHA-256, hex, base64url, in plain Scala (the same on every platform).

## `telegram`: a bot as an effect

```scala
enum Telegram[X]:
  case Send(chat: String, text: String, open: Option[String]) extends Telegram[Boolean]   // false: the chat is gone
```

- `Telegram.viaBotApi(token)` handles it over `Fetch` (the Bot API's `sendMessage`; `open` a Mini App button, https
  only); `Telegram.inMemory(sent)` for tests.
- `Telegram.update(request, secret)`: a webhook's update — refused (403) without Telegram's secret header; the chat,
  the text, whether it is private.
