# Cube source

Cubature owns the cube source contract (decision D16). The contract itself is
`docs/CUBE-SOURCE.md` in `../Cubature`, where it is a design draft: nothing in it is
implemented. Its interfaces will live in Cubature's public API module.

This file records only what Quadrature relies on, so a change to the contract can be
checked against it. The architecture it serves is in
[ARCHITECTURE.md](ARCHITECTURE.md), sections 5 and 6.

## What Quadrature relies on

| From the contract | Quadrature uses it for |
|---|---|
| Immutable neighborhood snapshots, readable on any thread | Meshing on worker threads without locking |
| The snapshot presenting the game's block access interface | Running existing block models and the capture technique unchanged |
| Implicit sky light for storage-less cubes | Lighting faces beside empty cubes correctly |
| A version per cube | Discarding builds that finished against stale data |
| Change events that include touching neighbors, on the client thread | Scheduling rebuilds; it listens to nothing else |
| The loaded cube set | Populating when the renderer starts mid-world |
| The work executor with priority and cancellation | Building without starting threads of its own |
| The settled signal | Captures that wait instead of discarding a first run ([VERIFICATION.md](VERIFICATION.md)) |

## What Quadrature does not need from it

- Entities: they are hosted drawing.
- Connectivity for cave culling: Quadrature computes it from the snapshot.
- Distant terrain: a separate source.

## Open for Quadrature

- Whether blocks that read their block entity during a build go through the capture
  technique on the client thread instead. The contract permits either.
- The first-run lighting difference in VERIFICATION.md. If its cause is a light change
  that raises no event, that is a defect against the contract, to be raised in Cubature.
