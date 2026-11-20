# Agent Guidelines

## Language

- Code comments, identifiers, and commit messages are written in English.
- `README.md` is English and is the main README; `README.ru.md` is its Russian translation, and `docs/` is in Russian.
- Reply to the user in the language they used.

## Comments

Comment only when the code cannot explain itself. One idea per comment, one or two short sentences, plain English.

Keep comments that record:

- invariants and ordering rules (lock order, lifecycle, ownership);
- protocol requirements (HTTP ranges, HLS tags, header formats);
- platform quirks with a concrete cause (AVPlayer, ExoPlayer, ATS, cleartext policy);
- security and privacy decisions (cookie handling, key storage, URL redaction);
- non-obvious workarounds, with the failure they prevent.

Delete comments that:

- restate the next line or the function name;
- describe history, reviews, dates, logs, plan sections (`§6.3`, "log 12.11"), or code in another repository;
- are commit messages pasted into code;
- use unexplained shorthand or transliterated Russian (`вотчдог`, `кик`, `манифест`);
- add emphasis, excitement, or filler ("important", "note that", "as you can see");
- are commented-out code or decorative banners. Keep `// MARK:` and section markers.

Avoid cramming unrelated facts into one sentence or chaining slogans with semicolons. Split them into separate comments. Prefer domain terms: "media playlist", "segment", "stall detector", "retry", "session". Do not use "manifest" when you mean an HLS playlist, and do not use "kick" when you mean "retry" or "restart".

Bad:

```kotlin
// fix(stall): watchdog restarts the manifest; URL map survives manifest change
```

Good:

```kotlin
// The player keeps using the old segment URL until remount, so keep old URLs mapped.
```

Doc comments: one-line summary by default. Add details only for a contract the caller must know (errors, ownership, idempotency). Omit `@param` lines that just repeat parameter names.

## Commit messages

- English, imperative mood, one behavior per commit.
- `type(scope): summary`, at most 72 characters. Body only when the reason is not obvious.
- No semicolon-chained summaries and no internal jargon.
- Bad: `fix(stall): watchdog restarts manifest; URL map survives manifest change`
- Good: `fix(stall): recover stuck segments without remounting the player`

## Editing rules

- Keep diffs minimal. Do not reformat code or rename things while editing comments.
- Do not change log messages, error strings, or public API text as part of a comment cleanup.
- If a comment is stale or wrong, fix or delete it; do not preserve it for history.
- Verify changes: `pwsh -File tools/build-core.ps1` for Kotlin and the self-test (on Windows without `pwsh`: `powershell -ExecutionPolicy Bypass -File tools/build-core.ps1`), `npm run check:hls` for the HLS URL rule, and `npx tsc --noEmit` for TypeScript when dependencies are installed. iOS changes require macOS with Xcode.
