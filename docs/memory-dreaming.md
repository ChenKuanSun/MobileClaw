# Memory & Dreaming System (Design)

Research + implementation plan for porting OpenClaw's memory/dreaming architecture to MobileClaw.

## Current state

MobileClaw has a basic `MemoryTool` (`app/src/main/java/ai/affiora/mobileclaw/tools/MemoryTool.kt`):

- Single JSON file `files/memory/memories.json`
- Flat key-value entries with tags, `createdAt`, `updatedAt`
- Actions: `save`, `search` (substring), `get`, `list`, `delete`
- Substring-only search, no ranking, no promotion pipeline

## OpenClaw's architecture

Three-file system:

| File | Purpose | Lifetime |
|---|---|---|
| `MEMORY.md` | Durable facts, preferences, decisions. Auto-loaded every session start. | Forever |
| `memory/YYYY-MM-DD.md` | Daily running context. Today + yesterday auto-loaded. | Days–weeks |
| `DREAMS.md` | Promotion log / diary. Human-readable reflection. | Append-only |

**Retrieval tools:** `memory_search` (vector + keyword), `memory_get` (direct file read).

**Dreaming pipeline** (3 cooperative phases running at 3 AM by default):

1. **Light Phase** — dedupes short-term signals, stages candidates with reinforcement tracking. No writes to `MEMORY.md`.
2. **REM Phase** — extracts thematic patterns, writes `## REM Sleep` block to `DREAMS.md`, feeds signals to Deep phase.
3. **Deep Phase** — ranks candidates with 6 weighted signals:
   - frequency (0.24)
   - relevance (0.30)
   - query diversity (0.15)
   - recency (0.15)
   - consolidation (0.10)
   - conceptual richness (0.06)
   - Applies threshold gates, rehydrates snippets from live files, appends winners to `MEMORY.md`, generates `## Deep Sleep` entry in `DREAMS.md`.

Stale/deleted snippets skipped during rehydration.

## Port to MobileClaw

### Phase 1: File layout migration (backwards-compatible)

Replace single JSON with three files, all in `files/memory/`:

```
files/memory/
├── MEMORY.md             # durable facts (was: memories.json)
├── daily/
│   ├── 2026-04-09.md     # today's running notes
│   └── 2026-04-08.md     # yesterday
├── DREAMS.md             # promotion log
└── .dreams/
    └── candidates.json    # machine state: reinforcement signals
```

**Migration:** on first launch after update, read `memories.json` → append to `MEMORY.md` as structured entries → delete old file.

### Phase 2: MemoryTool v2 actions

Expand action enum:

| Action | Target | Use case |
|---|---|---|
| `save` | `MEMORY.md` (durable) | `memory.save("user prefers dark mode")` |
| `note` | `daily/YYYY-MM-DD.md` | `memory.note("user asked about billing today")` |
| `search` | All three files, ranked | `memory.search("what does user prefer?")` |
| `get` | Specific file/line range | `memory.get("MEMORY.md", 1, 50)` |
| `list` | Enumerate durable facts | shows MEMORY.md structure |
| `delete` | Durable entry | `memory.delete("<line-range>")` |
| `dream` | Trigger promotion now | `memory.dream()` runs the pipeline |
| `diary` | Read DREAMS.md | shows recent promotions |

### Phase 3: Auto-loading (context injection)

On each `AgentRuntime.run()`:
1. Load `MEMORY.md` (durable) — prepend to system prompt
2. Load today's `daily/YYYY-MM-DD.md` + yesterday's — append to system prompt
3. Truncate to max ~2000 tokens so local models (Gemma 4 E2B) still have room

Currently MobileClaw just registers `MemoryTool` as a tool — the model has to explicitly call `memory.search`. This forces the model to remember to ask. Auto-loading makes durable facts always available.

### Phase 4: Dreaming scheduler (WorkManager)

Android has no cron. Use WorkManager:

```kotlin
val dreamingWork = PeriodicWorkRequestBuilder<DreamingWorker>(1, TimeUnit.DAYS)
    .setInitialDelay(calculateDelayUntil3AM(), TimeUnit.MILLISECONDS)
    .setConstraints(
        Constraints.Builder()
            .setRequiresCharging(true)       // only run while charging
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)     // don't wake user's phone
            .build()
    )
    .build()
```

### Phase 5: Dreaming pipeline

`DreamingEngine.kt`:

```kotlin
class DreamingEngine {
    suspend fun run(): DreamReport {
        val candidates = loadCandidates()  // from .dreams/candidates.json

        // Light: gather signals from recent daily notes + session history
        lightPhase(candidates)

        // REM: find themes, write to DREAMS.md
        val themes = remPhase(candidates)
        appendDreamsDiary("REM Sleep", themes)

        // Deep: rank and promote
        val promoted = deepPhase(candidates)
        appendToMemoryMd(promoted)
        appendDreamsDiary("Deep Sleep", promoted)

        saveCandidates(candidates)
        return DreamReport(...)
    }

    private fun rankCandidate(c: Candidate): Double {
        return 0.30 * c.relevance
             + 0.24 * normalizeFrequency(c.hitCount)
             + 0.15 * normalizeQueryDiversity(c.uniqueQueries)
             + 0.15 * recencyScore(c.lastHit)
             + 0.10 * consolidationScore(c.daysAlive)
             + 0.06 * conceptualRichness(c.text)
    }
}
```

Thresholds (match OpenClaw defaults):
- `minScore = 0.55`
- `minRecallCount = 3`
- `minUniqueQueries = 2`

### Phase 6: Cloud AI vs Local AI trade-offs

For **local Gemma 4**, the promotion LLM call (deep phase scoring) should use the local model — inference is free and private.

For **cloud models**, promotion should be opt-in since it costs tokens. Add `memory.dreaming.enabled` setting (default: on for local, off for cloud).

### Phase 7: UI — Dream Diary screen

New Settings sub-page: `docs/images/dream-diary-mockup.png`

- List of recent `DREAMS.md` entries (REM + Deep Sleep)
- "Run Dreaming Now" button (manual trigger)
- Last run timestamp
- Count of durable facts in `MEMORY.md`

## Implementation phases (ordered)

| Phase | Scope | Effort |
|---|---|---|
| 1 | File layout + migration from `memories.json` | Small |
| 2 | MemoryTool v2 actions (`note`, `dream`, `diary`) | Medium |
| 3 | Auto-load MEMORY.md into system prompt | Small |
| 4 | WorkManager scheduling | Small |
| 5 | DreamingEngine ranking pipeline | Large |
| 6 | Local-only dreaming default | Small |
| 7 | Dream Diary UI | Medium |

Start with 1 + 2 + 3 — those give 80% of the value with 20% of the work. Dreaming (4-7) is experimental even in OpenClaw.

## Open questions

- **Embedding-based search?** OpenClaw uses vector similarity when embeddings are configured. MobileClaw could use:
  - Cloud embeddings (Google Gemini embedding — free tier)
  - On-device via sentence-transformers (adds ~200 MB)
  - Skip for now, keyword-only (what we have)
- **File rotation?** Daily notes from 3 months ago are noise. Auto-archive after N days?
- **Size limits?** `MEMORY.md` could grow unbounded. Need compaction when > N lines.
- **Privacy:** Should the user be able to review/edit MEMORY.md? Yes — Settings > Memory > Edit Memory File.

## References

- https://github.com/openclaw/openclaw/tree/main/extensions/memory-core
- https://github.com/openclaw/openclaw/blob/main/docs/concepts/dreaming.md
- https://github.com/openclaw/openclaw/blob/main/docs/concepts/memory.md
