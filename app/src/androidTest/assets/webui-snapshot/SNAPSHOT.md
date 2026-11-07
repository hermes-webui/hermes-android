# WebUI prompt-card snapshot

`prompts.html` is a reduced copy of the Hermes WebUI markup and CSS that the Android wrapper's
viewport shim depends on: the approval and clarify prompt cards, the zero-height
`.composer-flyout` they hang from, `.composer-wrap`, `.app-titlebar` and the messages container.
`HermesWebUiCompatibilityTest` loads it to check that every card control stays reachable by touch
(`document.elementFromPoint`), so an upstream markup change or a shim regression is caught in CI.

## Source

- Project: Hermes WebUI (sibling checkout, read-only reference), MIT licensed,
  Copyright (c) 2025 Hermes Web UI Contributors.
- Version: `exp-v0.52.214-47-g2d330eb8` (`git describe --tags --always`).
- Files and lines (that checkout):
  - `static/index.html`: 121-153, 483-525
  - `static/style.css`: 1499-1531, 1976-1996, 2057-2091, 2261, 2574-2575, 3053-3263, 3510-3515
  - `static/messages.js`: 7291 and 8546 (how a card is shown and hidden)

## What was changed on purpose

- Kept the real selectors, positioning, `vh`/`dvh` caps, `z-index` stacking and the quirk that
  cards are absolutely positioned against a zero-height flyout above the composer.
- Dropped colors, fonts, skins, icons, the queue card and the terminal panel, and the `<script>`.
  Tests own showing and hiding a card.
- `var(--bg)` became `#fff`.

This is a test fixture only. It is not shipped in the app.

## Limits

It was derived from source and trimmed, so it can drift from the released WebUI. It proves the
shim against this snapshot, not against the live server. `tools/qa` scenarios `s03` (selector
drift) and `s04` (cards on the real DOM) are what detect drift against a real server.

## Refreshing

1. Check out Hermes WebUI at the version the app targets.
2. Re-extract the line ranges above (they move between versions) and diff against `prompts.html`.
3. Update the version and ranges in this file.
4. Run `HermesWebUiCompatibilityTest`. If a `promptCards_*` test fails, decide whether upstream
   changed legitimately (update the shim) or the shim regressed (fix the shim).
