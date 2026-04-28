# Storage Format Comparison

This document compares the current JSON-based replay storage format against alternative strategies, with a focus on **CPU**, **IO**, **memory**, and **crash resilience** characteristics.

## Current Architecture

Replays are stored as flat JSON arrays — one JSON object per timeline event — serialized via Gson with a custom `TimelineEventAdapter`. GZIP compression is applied by default (`.json.gz`).

```
Recording:  Bukkit events → TimelineEvent records → Gson/TimelineEventAdapter → JSON string → GZIP → disk
Playback:   disk → GZIP → JSON string → Gson/TimelineEventAdapter → TimelineEvent records → entity manipulation
```

The full timeline is held in memory as a `List<TimelineEvent>` for the duration of the recording and written to disk in a single pass when the session ends.

## Format Options

| Format | Size vs raw JSON | CPU improvement | Random access | Implementation effort |
|--------|-----------------|-----------------|---------------|----------------------|
| **JSON + GZIP** (current) | ~85–90% reduction | Baseline | No | Already implemented |
| **MessagePack / CBOR** | ~40–50% smaller raw; comparable to GZIP JSON compressed | 2–5× faster ser/deser | No | Low — drop-in serializer swap |
| **FlatBuffers** | Similar to binary formats | Zero-copy reads, near-zero deser cost | Yes (by offset) | Medium |
| **Protocol Buffers** | ~60–70% smaller than raw JSON | 5–10× faster than Gson | No (but streamable) | Medium — requires `.proto` schema |
| **Custom binary + tick index** | ~60% smaller raw; ~30–50% smaller compressed | 10–30× faster | Yes | High |

## Why JSON Is Suboptimal

### Verbose encoding

Field names like `"type"`, `"uuid"`, `"world"`, `"x"`, `"y"`, `"z"` are repeated in **every event object**. For a replay recording thousands of `PlayerMove` events per second of gameplay, the same key strings are written millions of times. GZIP mitigates file size (it excels at repeated strings) but the CPU cost of serializing and then compressing remains.

### Expensive number handling

Double-precision coordinates are stored as decimal text (e.g. `"1234.5678901234"` — ~15 bytes per double vs 8 bytes in binary). `Double.toString()` and `Double.parseDouble()` are among the most expensive operations in the serialize/deserialize path due to rounding, sign handling, and decimal formatting.

### All-or-nothing loading

The entire timeline must be parsed into memory to play any portion. There is no way to seek to "tick 5000" without loading everything before it.

### No crash resilience

The full recording lives in heap until `saveReplay()` is called. A server crash discards the entire recording.

## Custom Binary + Index: Detailed Analysis

### Design

```
Recording:  Bukkit events → TimelineEvent records → BinaryTimelineCodec → ByteBuffer → append to disk
Playback:   disk → seek via tick index → ByteBuffer → BinaryTimelineCodec → TimelineEvent records → entity manipulation
```

Each event type gets a **numeric tag** (e.g. `PlayerMove = 0x01`, `EntityMove = 0x02`) instead of the current string discriminator. The `TimelineEvent` sealed hierarchy and all downstream playback code remain unchanged — only the serialization layer is swapped.

### File layout

```
┌──────────────────────────────────────────────────┐
│  Header (magic bytes, format version, metadata)  │
├──────────────────────────────────────────────────┤
│  Event Stream (appended during recording)        │
│                                                  │
│  DEFINE_STRING  index=0  "uuid-of-steve"         │  ← first time this string appears
│  DEFINE_STRING  index=1  "Steve"                  │
│  DEFINE_STRING  index=2  "world"                  │
│  PLAYER_MOVE    tick=0  idx=0 idx=1 idx=2 ...    │
│  PLAYER_MOVE    tick=1  idx=0 idx=1 idx=2 ...    │
│  ...                                             │
│  DEFINE_STRING  index=3  "uuid-of-alex"          │  ← new player joins mid-recording
│  DEFINE_STRING  index=4  "Alex"                   │
│  PLAYER_MOVE    tick=500  idx=3 idx=4 idx=2 ...  │
│  ...                                             │
│  Event N                                         │
├──────────────────────────────────────────────────┤
│  Tick Index (written on clean shutdown)           │
│    String Table (complete copy for random access) │
│      count: 150                                  │
│      0 → "uuid-of-steve"                         │
│      1 → "Steve"                                 │
│      ...                                         │
│    Tick Offsets                                   │
│      tick 0    → byte offset 42                  │
│      tick 50   → byte offset 18,230              │
│      tick 100  → byte offset 36,415              │
│      ...                                         │
│  Index section offset (last 8 bytes of file)     │
└──────────────────────────────────────────────────┘
```

String definitions are **interleaved into the event stream** as `DEFINE_STRING` records, written the first time each unique string is encountered. This means every event's string references are guaranteed to be preceded by their definitions — even if the server crashes mid-recording, the file is fully decodable up to the last flushed event.

The tick index section is written **only on clean shutdown**. It contains a complete copy of the string table (redundant with the inline definitions, but precomputed for instant access) plus tick-to-offset mappings for random seeking. If the index is missing (crash), the decoder falls back to sequential reading — slower to seek, but all data is intact.

**Playback paths:**

| Scenario | Startup sequence |
|----------|------------------|
| **Clean shutdown** (tick index present) | Read index from end of file → load complete string table → seek to requested tick → decode events |
| **Crash recovery** (no tick index) | Sequential read from start → build string table incrementally from `DEFINE_STRING` records → decode events in order |

The crash recovery path can optionally rebuild and append the tick index for future loads, turning a sequential fallback into a one-time cost.

### String table (deduplication)

In JSON, every `PlayerMove` event repeats the player's UUID (36 bytes), name (~5–16 bytes), and world name (~5–20 bytes) as full strings — ~50–70 bytes of redundant string data per event. GZIP catches some of this via LZ77 back-references, but the encoder still must process the full text and the decoder must reconstruct it.

A binary format avoids this entirely with a **string table** (also called a symbol table or dictionary). Every unique string encountered during recording is assigned a numeric index. Events then store a 1–2 byte **varint index** instead of the full string. For 50 players, there are roughly ~150 unique strings (UUIDs, names, world names, entity types) — all fitting in 1-byte indices.

**Example — a `PlayerMove` event:**

```
Without string table:  [tag=0x01][tick=4B][uuid=36B][name=5-16B][world=5-20B][x=8B][y=8B][z=8B][yaw=4B][pitch=4B]
                        Total: ~82–100 bytes

With string table:     [tag=0x01][tick=4B][uuid_idx=1B][name_idx=1B][world_idx=1B][x=8B][y=8B][z=8B][yaw=4B][pitch=4B]
                        Total: ~35 bytes
```

The table is built incrementally during recording. When a string is seen for the first time, a `DEFINE_STRING` record is written to the event stream before the event that uses it:

```java
int getOrDefine(String value) {
    Integer existing = table.get(value);
    if (existing != null) return existing;
    int index = table.size();
    table.put(value, index);
    // Write inline definition to the event stream
    buffer.put(TAG_DEFINE_STRING);
    writeVarInt(buffer, index);
    writeUTF(buffer, value);  // length-prefixed UTF-8
    return index;
}

// Writing a PlayerMove — definitions emitted automatically on first use
buffer.put(TAG_PLAYER_MOVE);
buffer.putInt(event.tick());
writeVarInt(buffer, getOrDefine(event.uuid()));   // 1 byte (+ DEFINE_STRING on first call)
writeVarInt(buffer, getOrDefine(event.name()));    // 1 byte (+ DEFINE_STRING on first call)
writeVarInt(buffer, getOrDefine(event.world()));   // 1 byte (+ DEFINE_STRING on first call)
buffer.putDouble(event.x());
buffer.putDouble(event.y());
buffer.putDouble(event.z());
buffer.putFloat(event.yaw());
buffer.putFloat(event.pitch());
```

A `DEFINE_STRING` record is written **once per unique string** for the entire recording — for 50 players, that's ~150 definitions total (~5–10 KB of overhead). After the first definition, all subsequent uses of that string are a single varint byte.

During playback, the decoder builds the string table by processing `DEFINE_STRING` records as they appear (sequential read) or by loading the precomputed table from the tick index (random access).

**What benefits from indexing vs. what doesn't:**

| Field | Repeats across events? | String table? |
|-------|----------------------|---------------|
| Player UUID | Every tick per player | **Yes** |
| Player name | Every tick per player | **Yes** |
| World name | Every tick per player | **Yes** |
| Entity type (`"PLAYER"`, `"ZOMBIE"`) | Many events | **Yes** |
| Block data (`"minecraft:stone"`) | Moderate repetition | **Yes** |
| Serialized ItemStack | Low repetition (enchants, NBT vary) | No — store inline |
| Coordinates (doubles) | Unique per event | No — store as raw bytes |
| Tick number (int) | Unique per event | No — store as raw bytes |

**Size savings from the string table alone (50 players, 1.8M move events):**

| | Per event | 1.8M move events |
|---|---|---|
| Full strings | ~50–70 bytes | ~90–126 MB |
| String table indices | ~3 bytes | ~5.4 MB |
| **Savings** | | **~85–120 MB** (~94% reduction in string data) |

This is the same trick GZIP uses internally (LZ77 back-references to repeated byte sequences), but performed **at the semantic level** — the encoder doesn't have to search for patterns and the decoder doesn't have to decompress before accessing data. Any compression applied on top (e.g. LZ4) then works on already-compact data, making both the compression and decompression passes faster.

### Varint index encoding

String table indices use **variable-length integer (varint) encoding** rather than a fixed-width index. This avoids choosing between a 1-byte limit (max 255 strings) that could overflow and a 2-byte fixed width that wastes space in the common case.

The encoding uses the high bit of each byte as a continuation flag (LEB128 / Protocol Buffers style):

| Index range | Bytes used | Capacity |
|-------------|-----------|----------|
| 0–127 | 1 byte | 128 unique strings |
| 128–16,383 | 2 bytes | 16,384 unique strings |
| 16,384–2,097,151 | 3 bytes | 2M+ unique strings |

**Examples:**

```
Index 5:       0x05                     → 1 byte
Index 200:     0xC8 0x01                → 2 bytes
Index 20000:   0xA0 0x9C 0x01          → 3 bytes
```

**Why varint instead of fixed 2-byte:**

A typical recording with ≤127 unique strings (50 players × 3 fields = UUIDs, names, worlds, entity types, block types) fits entirely in 1-byte indices. A fixed 2-byte index would waste 1 byte per field per event — across 1.8M move events with 3 string fields each, that's **5.4 MB of unnecessary zero-bytes**. Varint gives 1-byte efficiency for normal recordings and unlimited headroom for extreme cases (massive servers, long recordings with thousands of unique block data strings) without any format version change.

**Implementation:**

```java
static void writeVarInt(ByteBuffer buf, int value) {
    while ((value & ~0x7F) != 0) {
        buf.put((byte) ((value & 0x7F) | 0x80));
        value >>>= 7;
    }
    buf.put((byte) value);
}

static int readVarInt(ByteBuffer buf) {
    int value = 0, shift = 0;
    byte b;
    do {
        b = buf.get();
        value |= (b & 0x7F) << shift;
        shift += 7;
    } while ((b & 0x80) != 0);
    return value;
}
```

The decoder reads bytes until it encounters one without the high bit set. No upfront size decision is baked into the format — it scales automatically.

### Reference Workload

A 30-minute replay at 20 TPS tracking 10 entities ≈ **360,000 events**.

### Serialization Speed (Recording Path)

| Strategy | Per event | 360K events |
|----------|-----------|-------------|
| Gson → JSON → GZIP | ~1–3 μs | ~0.5–1.0 s |
| Custom binary (ByteBuffer) | ~0.05–0.2 μs | ~20–70 ms |
| **Speedup** | | **~10–20×** |

The primary gains come from eliminating `Double.toString()` and Deflate. Writing `buffer.putDouble(e.x())` is a single memory copy.

### Deserialization Speed (Playback Path)

| Strategy | Per event | Full 360K load |
|----------|-----------|-----------------|
| GZIP → JSON → Gson | ~2–5 μs | ~1–2 s |
| Binary (sequential) | ~0.05–0.3 μs | ~20–100 ms |
| Binary + index (seek to tick N) | — | **<1 ms** to start at any tick |
| **Speedup (full load)** | | **~10–30×** |

### File Size

| Format | Raw | Compressed |
|--------|-----|------------|
| JSON | ~90 MB | ~5–10 MB (GZIP) |
| Custom binary | ~30–40 MB | ~3–6 MB (LZ4 block) |
| **Improvement** | ~60% smaller raw | **~30–50% smaller** compressed |

> [!NOTE]
> The compressed improvement is modest because GZIP is already very effective on repetitive JSON. The raw size difference matters mostly for the **compression CPU budget** — LZ4 runs at ~2–4 GB/s vs GZIP's ~100–200 MB/s, giving an additional **~15–20×** compression speedup.

### Memory During Recording

| Strategy | Peak heap |
|----------|-----------|
| Current (in-memory `List<TimelineEvent>`, flush at end) | ~70–100 MB for 360K events |
| Binary append-log (flush per event or small batches) | ~4–16 KB buffer |
| **Improvement** | **~99% less memory** |

This is the most impactful practical win. The current design holds the entire recording in heap until `saveReplay()` is called. A binary append-log writes events to disk incrementally, keeping almost nothing in memory — and a server crash does not lose the entire recording.

## Summary

| Metric | JSON + GZIP (current) | Custom binary + index |
|--------|----------------------|----------------------|
| Serialize CPU | Baseline | ~10–20× faster |
| Deserialize CPU | Baseline | ~10–30× faster |
| Time-to-first-frame (seek) | Must load entire file | <1 ms to any tick |
| File size (compressed) | Baseline | ~30–50% smaller |
| Recording memory | ~70–100 MB (360K events) | ~4–16 KB |
| Crash resilience | None — full loss on crash | Full — append-log |
| Human readability | High — text editor | Low — needs tooling |
| Implementation effort | Already done | High |

### Where It Matters Less

- **Short recordings** (<5 min, <60K events): the current approach loads in ~100–300 ms, which is acceptable.
- **Disk throughput**: both formats are well under the sequential write bandwidth of any modern disk. IO is not the bottleneck — CPU (text parsing/formatting and GZIP) is.

### Recommendation

For a v1 plugin, JSON + GZIP is a reasonable default — human-readable, debuggable, and file sizes are acceptable with compression. For production use on busy servers with long recordings, a binary format with an append-log and tick index provides order-of-magnitude improvements in CPU, memory, and resilience at the cost of implementation complexity and debuggability.

**MessagePack** represents a practical middle ground: binary encoding with ~2–5× faster serialization, minimal code changes (swap the serializer, keep the `TimelineEvent` data model), and no loss of the existing architecture.

---

## Appendix: 50-Player Recording Scenario

The reference workload above tracks 10 entities. Many servers will want to record large-scale events — PvP battles, minigames, or build sessions — with **50 players in a single recording**. This section scales the estimates to that scenario.

### Workload

- 50 players, 30-minute recording, 20 TPS
- **Primary events:** `PlayerMove` every tick per player = 50 × 20 × 1,800 = **1,800,000 move events**
- **Secondary events:** `InventoryUpdate`, `HeldItemChange`, `BlockPlace`, `BlockBreak`, `Attack`, `Swing`, etc. — conservatively ~10% overhead = **~180,000 additional events**
- **Total: ~2,000,000 events**

### Serialization Speed (Recording Path)

| Strategy | Per-tick cost (50 players) | Full 30-min recording |
|----------|---------------------------|----------------------|
| Gson → JSON → GZIP | ~50–150 μs/tick | ~2.5–5.0 s |
| Custom binary | ~2.5–10 μs/tick | ~100–350 ms |

> [!WARNING]
> At 20 TPS, each tick budget is **50 ms**. The JSON path consumes ~0.1–0.3% of each tick just for serialization (if done on-tick). This seems small, but the current design accumulates events in memory and serializes all at once at session end — meaning the 2.5–5 s flush blocks the async thread and delays the next recording or playback start. At 50 players the binary format reduces this to a sub-second flush.

### Deserialization Speed (Playback Path)

| Strategy | Full load (2M events) | Seek to tick 18,000 (halfway) |
|----------|-----------------------|-------------------------------|
| GZIP → JSON → Gson | ~5–10 s | ~5–10 s (must load everything) |
| Binary (sequential) | ~100–500 ms | ~100–500 ms (must read sequentially) |
| Binary + tick index | ~100–500 ms (full) | **<1 ms** |

A 5–10 second load time is **noticeable** for players waiting to watch a replay. With 50 tracked players, JSON deserialization becomes a real UX bottleneck. The tick index eliminates it entirely for mid-replay seeking.

### File Size

| Format | Raw | Compressed |
|--------|-----|------------|
| JSON | ~500–550 MB | ~25–55 MB (GZIP) |
| Custom binary | ~150–200 MB | ~15–30 MB (LZ4) |

At 50 players, a single uncompressed JSON replay exceeds **half a gigabyte**. Even GZIP-compressed, files reach 25–55 MB each. A busy server storing dozens of these replays will consume significant disk space. Binary cuts this roughly in half.

### Memory During Recording

| Strategy | Peak heap (2M events) |
|----------|-----------------------|
| JSON (in-memory list, flush at end) | **~350–550 MB** |
| Binary append-log | ~4–16 KB |

> [!CAUTION]
> At 50 players, the in-memory `List<TimelineEvent>` holding 2 million event objects (each with multiple `String` and boxed-number fields) consumes **350–550 MB of heap**. For a Minecraft server typically allocated 4–8 GB, this is **5–14% of total heap** for a single recording session. Multiple concurrent recordings can push the server toward GC pressure or `OutOfMemoryError`. The binary append-log eliminates this entirely.

### GC Impact

| Strategy | Object allocations (recording) | GC pressure |
|----------|-------------------------------|-------------|
| JSON (in-memory list) | ~2M `TimelineEvent` records + ~10M+ `String` objects | High — large tenured-generation footprint, promotes to old gen, triggers full GC |
| Binary append-log | ~1 reusable `ByteBuffer` | Negligible |

With 50 players, the JSON approach creates millions of long-lived objects that survive minor GC cycles and accumulate in the old generation. This increases full-GC pause frequency and duration — directly impacting server tick stability (TPS drops, player rubber-banding).

### Side-by-Side Summary (50 Players, 30 Minutes)

| Metric | JSON + GZIP | Custom binary + index |
|--------|-------------|----------------------|
| Total events | ~2,000,000 | ~2,000,000 |
| Serialize (total) | ~2.5–5.0 s | ~100–350 ms |
| Deserialize (full load) | ~5–10 s | ~100–500 ms |
| Seek to arbitrary tick | 5–10 s | <1 ms |
| File size (compressed) | ~25–55 MB | ~15–30 MB |
| Recording heap usage | ~350–550 MB | ~4–16 KB |
| GC pressure | High (millions of tenured objects) | Negligible |
| Crash data loss | Entire recording | None (append-log) |

### Conclusion

At 10 entities the JSON approach is tolerable. At 50 players it becomes a **server stability risk**: hundreds of megabytes of heap consumed by a single recording, multi-second serialization stalls, and complete data loss on crash. The custom binary format transforms all of these from problems into non-issues — the recording barely registers on the heap or CPU, persists incrementally, and loads an order of magnitude faster for playback.

---

## Debugging and Troubleshooting Binary Recordings

JSON's biggest advantage is that anyone can open a `.json` file in a text editor and immediately see what happened at tick 500. Binary files are opaque by default — but this is a **solved problem** with the right tooling built into the plugin itself.

### The JSON debugging experience

With JSON, troubleshooting typically looks like:

1. Open the `.json` or decompress the `.json.gz`
2. Search for a player UUID or tick number
3. Visually scan the event fields

This is intuitive but scales poorly. A 50-player, 30-minute JSON replay is 500+ MB uncompressed — most text editors will struggle or refuse to open it. Searching through 2 million JSON objects by hand is not practical at scale, even if the format is technically human-readable.

### Access model: hidden debug commands

Most Minecraft server operators use shared hosting (Pterodactyl, Multicraft, panel-based hosts) with **no SSH access and no ability to run CLI tools**. Debug tooling must be accessible from the game console or in-game commands.

At the same time, debug commands shouldn't clutter `/replay` tab completion for normal users. The solution is a **hidden config flag** — the commands only exist when explicitly enabled:

```yaml
# config.yml — not present in the default config.
# Server admins add this manually when directed by support or documentation.
# Advanced.Debug-Commands: true
```

**Behavior:**

| Config flag | Permission | Result |
|-------------|-----------|--------|
| Missing or `false` | Any | Commands don't exist. Not in tab completion, not in `/help`. |
| `true` | No `betterreplay.debug` | Commands exist but are denied. |
| `true` | Has `betterreplay.debug` | Full access to debug subcommands. |
| `true` | Console | Always available — no permission check. |

```java
// During plugin enable
if (getConfig().getBoolean("Advanced.Debug-Commands", false)) {
    registerDebugSubcommands();
}
```

All debug commands live under a `/replay debug` namespace, keeping the main `/replay` command clean:

```
/replay debug info <name>
/replay debug validate <name>
/replay debug dump <name> [filters...]
/replay debug export <name> [filters...]
```

### Debug commands

#### 1. Info — recording summary

A high-level overview for quick health checks:

```
/replay debug info MyReplay
```
```
Recording:    MyReplay
Format:       binary v1
Duration:     30:00 (36,000 ticks)
Players:      50
Total events: 1,982,341
File size:    18.4 MB (LZ4 compressed)
Tick index:   present (720 entries, 50-tick intervals)

Event breakdown:
  player_move:       1,800,000  (90.8%)
  inventory_update:     45,000  ( 2.3%)
  held_item_change:     36,000  ( 1.8%)
  block_break:          28,000  ( 1.4%)
  block_place:          25,000  ( 1.3%)
  swing:                22,000  ( 1.1%)
  attack:               12,000  ( 0.6%)
  ...
```

#### 2. Validate — structural integrity check

Binary formats can silently corrupt (truncated writes, disk errors). Validation checks the file end-to-end:

```
/replay debug validate MyReplay
```
```
✓ Header valid (binary v1, compatible)
✓ 1,982,341 events decoded without error
✓ Tick index consistent (720 entries, all offsets valid)
✓ No gaps in tick sequence (0–36,000)
⚠ 3 events have null UUID (block_break_stage at ticks 8012, 8013, 8014) — expected for stage events
```

#### 3. Dump — decoded event output

Decodes binary events into human-readable text. Output goes to the **server console** (not chat — chat would flood with thousands of lines). In-game senders see a summary with a note to check the console for full output.

```
/replay debug dump MyReplay --player Steve --ticks 500-600
```
```
[tick=500]   PLAYER_MOVE  uuid=a1b2c3  name=Steve  x=150.2 y=68.0 z=-180.5  yaw=120.0 pitch=-10.0
[tick=500]   BLOCK_BREAK  uuid=a1b2c3  world=world  x=151 y=67 z=-180  blockData=minecraft:stone
[tick=501]   PLAYER_MOVE  uuid=a1b2c3  name=Steve  x=150.3 y=68.0 z=-180.4  yaw=121.0 pitch=-10.0
...
```

With the tick index, the dump can seek directly to tick 500 and decode only the requested range — even for a massive recording, results are instant.

Available filters:

| Filter | Purpose |
|--------|---------|
| `--player <name\|uuid>` | Events involving a specific player |
| `--ticks <start>-<end>` | Events in a tick range |
| `--type <event_type>` | Only specific event types (e.g. `attack`, `block_break`) |
| `--world <name>` | Events in a specific world |
| `--area <x> <y> <z> <radius>` | Events within a spatial region |

#### 4. Export — write JSON to disk

For cases where a developer truly needs a text file to inspect or share. Writes a standard JSON file to the replays folder — downloadable via the hosting panel's file manager.

```
/replay debug export MyReplay --format json
/replay debug export MyReplay --format json --ticks 0-1000 --player Steve
```

Scoping to a tick range or player avoids producing an unwieldy 500 MB file.

### Dual-mode JAR (bonus for self-hosters)

For server operators who **do** have shell access, the plugin JAR also works as a standalone CLI tool. The binary codec classes have zero Bukkit dependencies — they're pure Java operating on bytes and `TimelineEvent` records.

```bash
# On the server machine, using the same JAR that's in plugins/
java -jar plugins/BetterReplay.jar dump MyReplay --ticks 500-600
java -jar plugins/BetterReplay.jar info MyReplay
java -jar plugins/BetterReplay.jar validate MyReplay
java -jar plugins/BetterReplay.jar export MyReplay --format json
```

This is enabled by adding a `main()` entry point to the JAR manifest:

```xml
<!-- pom.xml -->
<manifest>
    <mainClass>me.justindevb.replay.cli.ReplayToolCLI</mainClass>
</manifest>
```

When Paper loads the JAR, it's a plugin. When someone runs `java -jar`, it's a CLI tool. The codec is always version-matched to the plugin — no format mismatch risk. For database-stored replays, the CLI accepts a `--source` flag pointing to the replay directory or JDBC URL.

> [!NOTE]
> The dual-mode JAR is a zero-cost addition (one small class), but the **in-game debug commands are the primary interface** since most hosting environments don't provide shell access.

### Comparison

| Debugging task | JSON | Binary + debug commands |
|---------------|------|------------------------|
| "Show me what happened" | Open in text editor | `/replay debug dump <name>` |
| "What did Steve do at tick 500?" | `grep` / `jq` through full file | `/replay debug dump <name> --player Steve --ticks 500-510` (instant) |
| "How big is this recording?" | `ls -la` + manual inspection | `/replay debug info <name>` |
| "Is this file corrupted?" | Try to load it, hope for a useful Gson error | `/replay debug validate <name>` with specific error locations |
| "Give me JSON for external analysis" | Already JSON | `/replay debug export <name> --format json` (downloadable via file manager) |
| "Debug a 500 MB, 50-player replay" | Text editor chokes or is unusable | Filtered dump returns results in milliseconds |

### Bottom line

JSON's human readability is a **development convenience**, not a production debugging tool. Nobody is reading 2 million JSON objects by hand. Purpose-built tooling on top of a binary format provides a **better** debugging experience — faster, filterable, and with features (validation, stats, spatial queries) that raw JSON cannot offer. The debug commands are hidden behind a config flag so they never impact normal users, and the dual-mode JAR serves self-hosters who want CLI access.
