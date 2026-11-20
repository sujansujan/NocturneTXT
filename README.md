# NocturneTXT — Plain-Text Audiobook Release Tracker (v3.1)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v3.1 (Pure ASCII Polish, Color Hierarchy & Portrait Verification)
1. **Cleaner Collapsed Upcoming Cards**: Keeps only `Runtime` on collapsed Upcoming cards; `Publisher` and `Match` appear cleanly when you tap to expand.
2. **Strict Author Portrait & Cover Verification (`C. Mantis` Fix)**: `AudibleApiClient.fetchAudibleAuthorImageUrl` strictly verifies punctuation-normalized author names against Audnex/Audible responses, and `ReleaseRepository.syncCatalog` filters book-cover fallbacks exclusively to books written by that author while auto-repairing stale wrong thumbnails on sync.
3. **Pure 7-Bit ASCII Chrome & Countdown Bars**: Replaced `•` bullets and nested double brackets with clean `|` separators (`+---[ 2026-12-01 | [####--] T-11d ]---+`, `+=== DECEMBER 2026 (4) | 83H 53M ===+`, `+---[ AUTHOR | 0 UPCOMING ]---+`), visible `- - - - -` card rules, and 6-cell ASCII countdown bars for `<=30d` drops.
4. **Syntax-Highlighted Terminal Color Hierarchy**: Column-aligned `SER:`, `AUT:`, `NAR:` tags in muted `comment` with crisp values; urgency-tiered countdowns and borders (`OUT TODAY`/`ABB` green, `1–7d` orange, `8–30d` cyan, `>30d`/TBA comment); de-emphasized `0 UPCOMING` Tracked rows.
5. **Inverted Terminal Nav & Filter Pills + Pure Text Mode**: Numbered inverse-video bottom tabs (`1:UPCOMING(12)`, `2:TRACKED(22)`, `3:SET`), inverted active filter pills, a two-tap `[ x ]` -> `[ del? ]` delete guard on Tracked sources, and a `Hide Cover Art (Pure Text)` toggle in Settings (persisted in Backup Schema v8).
