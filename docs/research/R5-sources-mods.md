# R5: scene sources, mod interface, other mods

Requirement sections 3, 10 and 11. First pass, 2026-11-13. Basis marks are defined in
[README.md](README.md).

## 3. Scene sources

**3.1 Cube snapshots. [K, with a local precedent V]**
Cubature's current renderer integration already clones blocks and block entities out of
cubes into the renderer's sections, and reads a cube without storage as its implicit
height-based sky light. That is a working, if copying, snapshot. Designs that avoid the
copy: versioned immutable sections shared by reference, or copy-on-write at the block
storage level.
*Implication:* the cube source contract (work order P0-A2) starts from what the existing
integration needs: blocks, block entities, light, neighbors, and implicit sky light.

**3.2 Distant source and the server. [K]**
See R3, 6.5: a client holding the seed and definitions computes ungenerated terrain
itself. The bandwidth question reduces to changed terrain only.

## 10. Mod interface

**10.1 First client. [V]**
GTCEu: Reloaded today adds a bloom value to the game's render layer enum, wraps the
translucent terrain call in the world pass, and carries its own bloom framebuffers, depth
texture hook and post-processing shaders. Under Quadrature these become: declare an
emissive layer, submit emissive geometry, and receive bloom as a pipeline stage.

**10.2 Shape. [K]**
Handles instead of object references, capability queries instead of version checks, and a
versioned contract in OrthLib. Open design.

## 11. Exclusivity and other mods

**11.1 Mods that reach into the game's chunk rendering. [V, from published documents]**
Two renderer projects publish their compatibility work per mod; those documents show what
each mod needs from a renderer. Read for behavior only, on 2026-11-13.

| Mod | What it does to rendering | What it needs from Quadrature |
|---|---|---|
| CodeChickenLib (and Thermal, Ender IO pipes) | Draws blocks by writing vertices directly | The capture technique (R2, 4.4) |
| ConnectedTexturesMod, Chisel | Wraps block models; extra layers per quad | Model path with per-quad layer |
| LittleTiles, ArchitectureCraft | Own chunk-level geometry and lighting | Capture technique; lighting inputs |
| Fluidlogged API | Changes which layer a block draws in | Layer query honored off-thread |
| Chunk Animator | Animates sections as they appear | A per-section transform hook, or reported |
| Snow! Real Magic!, Component Model Hider | Redraw or hide blocks inside the dispatcher | Capture technique for affected blocks |
| GregTech CEu, Lumenized | Bloom layer and post-processing | The mod interface (10.1) |
| Texture animation optimizers (Chibi) | Skip animating unseen textures | A visible-texture report, or reported |
| HUD caches (StellarCore, Gnetum) | Cache HUD draws in framebuffers | State containment (R1, 2.4) |
| ReplayMod, minimaps | Read frames and depth back | Frame resource access |
| RenderChunk rebuildChunk Hooks (used by BetterFoliage and others) | A core mod that adds events inside the game's chunk rebuild, exposing the block dispatcher and layers | Its events never fire once the game's chunk rebuild is replaced; its clients need the capture technique or the mod interface |
| Kirino, graphics state | Replaces the game's world render | None: the game stops (decision D6; research R1, 2.5) |
| OptiFine and other renderers | Replace the renderer | None: the game stops (decision D6) |

Cleanroom's LWJGL 2 shim keeps its own list of mods that reach LWJGL 2 internals by
reflection and fail at runtime [V, `MOD_COMPATIBILITY.md`, read 2026-11-13]. Two entries
concern rendering: FarPlaneTwo's LWJGL 2 backend resolves GL entry points through members
the shim does not provide, and Vivecraft's input injection drives LWJGL 2's platform
classes. Both fail on Cleanroom regardless of Quadrature.

*Implication:* three needs recur: the capture technique, an honored layer query, and state
containment. Most of the list is covered by those three.

**11.3 A survey of one large pack. [V]**
`tools/mod_survey.py` reads the constant pools of every class in a folder of mods and
lists which OpenGL calls each mod makes and which rendering classes it patches. Run on
2026-11-13 over the 189 client mods of Supersymmetry 0.2.0.1-patch2 (68,661 classes). A
reference is not proof of use at runtime, and a call's arguments are not visible.

Mods that patch the game's rendering classes, by Mixin or class transformer:

| Class | Mods | Which |
|---|---|---|
| `EntityRenderer` | 9 | Aqua Acrobatics, Quark RotN, Serene Seasons, StellarCore, Susy Core, Universal Tweaks, Chibi, Darkness, GregTech CEu |
| `RenderGlobal` | 8 | Quark RotN, StellarCore, Susy Core, Universal Tweaks, Chibi, NAE2, Phosphor, VintageFix |
| `RenderChunk` | 5 | StellarCore, Susy Core, Chibi, GregTech CEu, VintageFix |
| `BlockRendererDispatcher` | 3 | Susy Core, Chibi, VintageFix |
| `BlockModelRenderer` | 4 | Quark RotN, Susy Core, Universal Tweaks, Chibi |
| `BlockFluidRenderer` | 3 | Aqua Acrobatics, Ocean API, Chibi |
| `BufferBuilder` | 4 | Aqua Acrobatics, Susy Core, Universal Tweaks, Chibi |
| `GlStateManager` | 8 | Aqua Acrobatics, Retro Sophisticated Backpacks, StellarCore, Universal Tweaks, Inventory Bogo Sorter, Chibi, VintageFix, Visual Ores |
| `TextureMap` | 7 | Aqua Acrobatics, ConnectedTexturesMod, StellarCore, Sussy Patches, Universal Tweaks, Chibi, VintageFix |

Mods by the OpenGL calls they make:

| Call | Mods |
|---|---|
| Enable or disable state directly, bypassing the game's cache | 45 |
| Immediate-mode drawing | 20 |
| Scissor or viewport directly | 20 |
| The attribute stack | 18 |
| Depth comparison through the game's state manager | 18 |
| Matrices directly | 15 |
| Depth mask directly | 11 |
| Stencil | 10 |
| Own shader programs | 9 |
| Blend function directly | 9 |
| Polygon offset through the game's state manager | 8 |
| Read pixels | 6 |
| Own framebuffers | 5 |
| Depth clear value | 4 |
| Buffer objects directly | 4 |
| Display lists | 3 |
| Depth comparison directly | 2 |
| Pixel storage; copying the framebuffer to a texture | 2 each |
| Vertex array objects; generic vertex attributes | 1 each |
| Depth range; polygon offset directly | 0 |

*Implications:*
- Nine mods in one pack patch the method Quadrature observes. Observing without
  redirecting (R1, section 2.5) is what lets them all keep working. The terrain calls
  Phase 2 takes over are a different matter: the five mods that patch `RenderChunk` and
  the three that patch `BlockRendererDispatcher` are the ones whose behavior Phase 2 must
  account for one by one.
- Hosted code changes depth state in many places and reads depth back. Reversed depth
  therefore translates those calls where they are made, in every class (R1, section 2.2).
- Hosted code leaves modern state behind as well as fixed-function state. That decides
  the envelope (R1, section 9a).
- One pack is one sample. The tool runs on any mods folder; packs the projects target
  should each be surveyed.

**11.2 When a stop message can be shown. [V, partly]**
Forge 1.12.2 provides `CustomModLoadingErrorDisplayException`, which a client mod throws
during loading to show its own error screen (`GuiCustomModLoadingErrorScreen`).
Detection can happen earlier, when Mixin configurations are selected, but no screen exists
then.
Confirmed on Cleanroom 0.7.14-alpha, 2026-11-13 [V]: thrown from pre-initialization, the
exception makes Forge log "the game will display an error screen and halt" and open that
screen. The graphics context cannot be read at pre-initialization, because the loading
screen's thread holds it; it is read when the first screen opens, and an unsupported
context replaces every screen with a stop screen from then on.
*Implication:* other renderers are refused at pre-initialization; the context is refused
at the first screen. Detection will have to move earlier, to where Mixin configurations
are selected, once Quadrature has Mixins of its own.

## Follow-ups

1. Write the cube source contract with Cubature (3.1).
2. Survey the other packs the projects target with `tools/mod_survey.py`, and for each mod
   that patches `RenderChunk` or `BlockRendererDispatcher`, record what it changes.
3. Move renderer detection to Mixin selection when Quadrature gains Mixins (11.2).

## Sources

- Cubature, renderer integration (`core/compat/celeritas` and the `selectable.celeritas`
  Mixin set), as of 2026-11-13
- GTCEu: Reloaded, `gregtech/mixins` and `gregtech/client/utils` (bloom), as of 2026-11-13
- Actinium, `docs/compatibility-matrix.md` (updated 2026-11-07), read for behavior
- Demonica, README and release notes 0.9.1, read for behavior
- Relictium, README (known incompatibilities)
- RenderChunk rebuildChunk Hooks, project page:
  https://www.curseforge.com/minecraft/mc-mods/renderchunk-rebuildchunk-hooks
- CleanroomMC/LWJGLY, `MOD_COMPATIBILITY.md`: https://github.com/CleanroomMC/LWJGLY
- Forge 1.12.2 Javadoc, `CustomModLoadingErrorDisplayException`:
  https://skmedix.github.io/ForgeJavaDocs/javadoc/forge/1.12.2-14.23.5.2859/net/minecraftforge/fml/client/CustomModLoadingErrorDisplayException.html
