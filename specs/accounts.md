# accounts — who a phone's person is, and what of theirs is kept in the cloud

Status: specification, 2026-10-31. Asked by the operator for okay-faces' phones: «Синхронизировать то что помечено как
общее. вход через Apple или Google или код по почте.» The module is `accounts` (`okay-serverless-accounts`, package
`okay.serverless.accounts`), an `App` and its actors, on Cloudflare and on the host alike.

## What it is

A backend any okay-faces phone app can use as it is:

- SIGNING IN, three ways, each ending in a SESSION — a random bearer token naming the person:
  - a CODE BY MAIL: `POST /auth/email {email}` sends six digits (by the platform's `Mails`), `POST /auth/email/code
    {email, code}` takes them back; ten minutes, five tries;
  - APPLE: `POST /auth/apple {token}` — the identity token Sign in with Apple gave the phone, a JWT signed RS256 by
    one of Apple's keys (`https://appleid.apple.com/auth/keys`), its issuer `https://appleid.apple.com`, its audience
    one of `APPLE_CLIENT_IDS` (the apps' bundle ids);
  - GOOGLE: `POST /auth/google {token}` — Google's ID token, by Google's keys
    (`https://www.googleapis.com/oauth2/v3/certs`), issuer `accounts.google.com` or `https://accounts.google.com`,
    audience one of `GOOGLE_CLIENT_IDS`.
  The person is `email:<address>`, `apple:<sub>`, `google:<sub>` — accounts are not merged across ways in this first
  cut. The answer: `{session, who}`. `POST /auth/out` forgets the session.
- WHAT IS SHARED — only what the program marks so; everything else stays on the phone. One object per person
  (`SHARED`), its values with versions: `GET /shared` (every key, its version), `GET /shared/<key>` (`{value,
  version}`, or 404), `PUT /shared/<key> {value}` (`{version}`; the last write wins), `DELETE /shared/<key>`.

## Decided

- Keys are checked in PLAIN SCALA, the same on every platform: a JWK's modulus and exponent, `s^e mod n` by `BigInt`,
  the PKCS#1 v1.5 block with SHA-256's DigestInfo compared (`Jwt`). Apple's and Google's keys are fetched and kept an
  hour (KV `ACCOUNTS`, `keys/<issuer>`); an unknown `kid` fetches them again once.
- Codes and sessions are random by the runtime's own (`Crypto.random`, handled inside the request). A code is kept
  as its SHA-256, in the email's own object (`SIGNIN`), so two requests for one address are one at a time. A session
  is KV `ACCOUNTS` `session/<token>` → the person, 90 days.
- Mail: `MAIL` the binding, `MAIL_FROM` the sender. `ACCOUNTS_TEST=yes` (tests and a local host only) answers the code
  in the response instead of mailing it.
- The phone side is okay-faces' (`okay.faces.mobile.Accounts`): the session in the keychain, the shared values cached
  on the phone, a sign-in screen as a component.

## Open

- Push notifications (APNs, FCM): a session's devices and a `Notify` to a person — when the operator wants them.
- One person by several ways (link an Apple account to an email).
- Conflicts beyond the last write.
