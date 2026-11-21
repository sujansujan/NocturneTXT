# NocturneTXT — Plain-Text Audiobook Release Tracker (v3.2)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v3.2 (Read Log, Pinning, Local Filter, Alerts & Polish)
1. **Persistent Mark-as-Read & Listening Log (`READ:N`)**: Mark any audiobook read (Series, Author, Narrator, or standalone Book) with 1-tap `[ Mark Read ]` + `[ UNDO ]`, auto-advancing Series `lastReadSequence`, auto-completing standalone tracked Books, suppressing redundant notifications/ABB scrapes, and logging finished books by completion month with total hours listened.
2. **Priority Pinning (`PIN:N`) & Telemetry Strip**: Star must-listen drops with `[ * Pin ]` (`*` header badge + yellow frame) and view a 1-line terminal telemetry strip (`NEXT: T-4d | UP: 14 | <=30d: 3 | READ: 27(412H)`).
3. **Instant Local `/` Filter & In-Place Tracked Editing**: Toggle `[ / ]` in the top bar to filter `UPCOMING`, `READ`, `DND`, or `TRACKED` locally without API calls, and tap `[ e ]` on any Tracked source to rename it or force-refresh its metadata/portrait in place without losing `lastReadSequence`.
4. **Date Shift (`| MOVED`) & Narrator (`| NAR!`) Alerts + On-Demand ABB Check**: Collapsed cards surface date shifts and newly announced narrators with background notifications, plus `[ CHECK ABB NOW ]` on the `ABB` filter and Settings.
5. **Backup Schema v9 & Visual Polish**: Exports/imports `readBooks` and `pinnedReleases` (backward-compatible with v8), true edge-to-edge Dracula/Alucard system bars, overflow-proof ASCII borders with `[+]`/`[-]` fold indicators, aspect-preserving portraits, regional marketplace API routing, and SHA-256 disk caching.
