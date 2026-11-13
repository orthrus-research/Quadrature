# Cube source contract

What Cubature supplies to Quadrature so near terrain can be meshed from cubes. This is a
draft for agreement between the two projects (work order P0-A2). Nothing in it is
implemented. Where it says "must", it states the contract; open points are listed at the
end.

The architecture this serves is in [ARCHITECTURE.md](ARCHITECTURE.md), sections 5 and 6.

## Parties

| Party | Owns |
|---|---|
| Cubature (`../Cubature`) | Cubes on the client: storage, light, block entities, arrival and removal, the work executor |
| Quadrature | Everything made from cubes: meshes, visibility data, GPU memory, drawing |

Quadrature never reads Cubature's internal classes. It reads the interfaces below.
Cubature never calls into Quadrature except through the listener it is given.

## Terms

- **Cube**: 16 by 16 by 16 blocks at a cube position (x, y, z in cube coordinates).
- **Neighborhood**: a cube and the 26 cubes around it.
- **Storage-less cube**: a loaded cube with no block storage. It is all air, and its sky
  light is implicit: full above its column's highest opaque block, none at or below it.
- **Client thread**: the game's client thread, which also owns the graphics context.

## 1. World

The source exists for a client world that Cubature reports as cubic. It gives:

- the world's height range in cube coordinates, lowest inclusive and highest exclusive;
- whether the dimension has sky light;
- an identity that changes when the client world is replaced (dimension change,
  disconnect). Every snapshot and event belongs to one identity and is void after it.

## 2. Snapshot

A snapshot is an immutable copy of a neighborhood, taken on the client thread.

**Contents**, for each of the 27 cubes:

| Data | Notes |
|---|---|
| Presence | Loaded, or absent |
| Block states | 4,096 per cube; none for a storage-less cube |
| Block light and sky light | Per block. For a storage-less cube the snapshot answers sky light by the implicit rule, from the column tops it captured |
| Biomes | As the cube stores them, for tinting |
| Block entities | The cube's block entity map as it stood. The entities themselves are not copied |
| Version | See section 3 |

**Guarantees:**

1. **Immutable.** Nothing readable through a snapshot changes after it is returned.
   Block entities are the exception: the snapshot holds the live objects, as the game's
   own chunk cache does, and a model that reads one off the client thread sees whatever
   that object then holds.
2. **Consistent.** All 27 cubes are captured between two client ticks, so the snapshot
   never shows half of an edit.
3. **Any thread.** A snapshot may be read from any thread, for as long as it is held.
4. **Block access.** A snapshot presents the game's block access interface
   (`IBlockAccess`): block state, block entity, combined light, biome, air test and
   side solidity, for any block in the neighborhood. Existing block models and block
   color handlers run against it unchanged.
5. **Absent neighbors read as empty.** An absent cube reads as air with implicit sky
   light. When it arrives, the cubes around it receive a change event (section 3), so
   the faces drawn against the gap are rebuilt.
6. **Empty is cheap.** A snapshot whose center cube is storage-less says so without
   being read block by block.

**Cost.** Cubature may share unchanged cube data between snapshots instead of copying it.
The contract requires only the guarantees above. Today's renderer integration copies
blocks and block entities per section; that is the cost to beat.

**Release.** Quadrature releases a snapshot when its build finishes or is cancelled.
Holding one must not keep a cube loaded.

## 3. Versions and events

Each loaded cube has a version: a number that increases whenever anything a snapshot
would capture changes. A snapshot records the version of each cube it holds. A finished
build is discarded if its center cube's version is no longer current.

Cubature delivers events to one listener, on the client thread, after the data they
describe is readable:

| Event | When |
|---|---|
| World attached, world detached | The client world appears or is replaced |
| Cube loaded | A cube's data arrived, including a full resend of a cube already held |
| Cube unloaded | A cube left the client |
| Cube changed | Blocks, light or biomes in a loaded cube changed; carries the kinds that changed and, where known, the bounds within the cube |

**Guarantees:**

1. Events arrive in the order the changes were applied.
2. A change in a cube's outermost layer of blocks, or its arrival or removal, also raises
   a change for each loaded neighbor that touches it.
3. Cubature translates every route by which a cube changes into these events: whole-cube
   packets, small block-change packets, the game's own block and light notifications,
   and its own lighting. Quadrature listens to nothing else.
4. Events may be coalesced within a tick: several changes to one cube may arrive as one
   event with their kinds and bounds combined.

## 4. Queries

- The set of loaded cube positions, for populating a renderer that starts or restarts
  while a world is already loaded.
- Whether a given cube is loaded, and its current version.

## 5. Work executor

Quadrature starts no threads. Cubature provides a client-side executor with:

- a bounded number of workers, stated by Cubature and readable by Quadrature;
- task priority, so near and in-view cubes build first;
- cancellation of a queued or running task;
- no use of the common pool.

Tasks submitted by Quadrature read snapshots and write only memory Quadrature owns.

## 6. Settled

The source reports whether cube arrival for the current view is settled: every cube the
client expects within its view distance has arrived, and no light work that would raise a
change event is outstanding. The capture harness waits on this
([VERIFICATION.md](VERIFICATION.md)).

## 7. Threads

| Operation | Thread |
|---|---|
| Take a snapshot, release a snapshot, queries, attach or detach the listener | Client thread |
| Read a snapshot | Any |
| Receive an event | Client thread |
| Run a build task | Cubature's executor |

## 8. How today's integration maps onto this

Cubature's current renderer integration already answers most of these needs. This shows
where each one goes.

| Today (Cubature's Celeritas integration) | In this contract |
|---|---|
| Section query: a loaded cube's block storage | Snapshot, block states and light |
| Stand-in section for a storage-less cube, built from column tops | Snapshot, implicit sky light |
| Block entity map per section | Snapshot, block entities |
| Lowest and highest section | World, height range |
| Loaded cube heights in a column | Queries, loaded set |
| Column loaded, cube loaded, cube unloaded | Events |
| Deferring cube changes until the renderer applies its own events | Events arrive on the client thread at a defined point; Quadrature has no second thread reading its section set |
| Entity gathering from the world's entity list | Not in this contract: entities are hosted drawing |

## 9. What changes in Cubature

Adding the source is additive. Cubature's existing vanilla, Celeritas, Demonica and
Distant Horizons integrations stay as they are until the Quadrature phase that replaces
each one closes (decision D10).

## Open points

1. **Where the interfaces live.** In Cubature's public API module, so the owner of the
   data owns the contract, or in OrthLib, which already carries a Cubature adapter.
2. **Sharing against copying.** Whether cube data can be shared between snapshots
   without changing how Cubature stores it, and what a copy costs if not. Needs
   measurement in Cubature.
3. **Settled on the client.** A client does not know what the server has yet to send.
   What "expects" means in section 6 needs a definition Cubature can compute, or a
   signal from the server.
4. **Biome data.** Whether the stored biomes are enough for the game's color blending at
   neighborhood edges, or a wider ring is needed.
5. **Block entities off-thread.** Guarantee 1 inherits the game's hazard. Whether
   Quadrature should restrict which blocks read their block entity during a build is a
   Quadrature decision that this contract permits either way.
6. **First-run light.** Frames show light on freshly generated terrain differing from the
   same terrain after a reload (VERIFICATION.md). If the cause is the client's own
   relight of small edits, the version and change events must cover it; that is to be
   confirmed once the cause is known.
