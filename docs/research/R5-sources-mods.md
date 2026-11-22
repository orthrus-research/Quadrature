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

**10.2 Shape. [V, read and measured 2026-11-21]**
The interface has three parts that evolve under different rules: what a mod holds and calls,
which OrthLib alone implements; what a provider implements; and the absence answers, which
implement the whole contract themselves.

*Binary compatibility.* New parameter or result types remove the old method (JLS 13.4.14,
13.4.15); changed record components may break binaries not recompiled (13.4.27); a new enum
constant or permitted subclass links, but a switch compiled as exhaustive fails on it (13.4.26,
13.4.2.1, 14.11.3); an abstract method added to an interface fails when called on an older
implementation, a default method does not (13.5.4, 13.5.7); a constant variable's value is
copied into the code that reads it (13.4.9). On Temurin 26.0.2, a binary built against a first
version of a small API and run against a second gave `MatchException` for both switches,
`NoSuchMethodError` for a record's dropped constructor (none once it is kept, JLS 8.10.4) and
`AbstractMethodError` for the abstract method; an older implementation ran under the default.
Two hazards showed beside them: a provider's own method with a new default's signature
silently implements it (JLS 8.4.8.1), and a provider that states its version by reading an API
field reports the version installed, not the one it was built against. Java 26's compiler with
`--release 25` reads OrthLib's class files (version 70) without warnings and writes version 69,
which links with them on Java 26; Java 25's compiler warns on each one.

*Handles.* A mod holds only objects OrthLib creates, a declared layer, a bloom kind, a
contribution, each also its lease. A provider keys its own state by them, so a provider
replaced or absent leaves them valid, as Vulkan's handles are opaque to the application
(Vulkan, Object Model). Adding a method to a type clients implement breaks them (des Rivières,
Evolving Java-based APIs), so the types mods and providers implement stay small.

*Capabilities.* OpenGL lists its extensions by name (`glGetStringi`) beside vendor and renderer
strings for recognizing a platform (`glGetString`), the practice requirement rule 1 excludes.
Vulkan's minor versions add and never remove; since 1.1 an implementation accepts any requested
version, and use is validated against the lower of the two (Vulkan, Version Numbers;
`VkApplicationInfo`). OpenXR instead refuses an unsupported version (`XrApplicationInfo`), and
COM answers no when an old object is asked for a newer contract (`QueryInterface`). A refusal
would make a mod depend on its provider, which D25 excludes, so each minor addition, a new value
of an existing type included, is named by a capability: a provider without it gets the absence
answer and never sees the value, and no behavior is chosen by version number.

*Versions.* Semantic versioning leaves 0.y.z free to change (semver 2.0.0, rule 4), and OrthLib
is 0.5.0-dev, so the interface carries its own version, 1.0. OSGi has a consumer accept up to
the next major version but a provider only up to the next minor, since almost any change breaks
providers (OSGi, Semantic Versioning); default methods that return the absence answer keep a 1.0
provider working under every 1.x. A mod's floor is the OrthLib release it was built against,
stated as `required-after:orthlib@[...]`, which Forge enforces before the game starts (`@Mod`); a
provider states its version and capabilities in its own code, never reads them from OrthLib.

*Discovery.* Inter-mod messages carry a provider only as a class name for the receiver to load
(`FMLInterModComms`), and `ServiceLoader` names no mod and is unsafe for concurrent use (Java SE
25), so a provider registers by a typed call from its own loading event. Registration closes when
loading completes (`FMLLoadCompleteEvent`), and a provider's presence never changes in play.
Fabric admits one renderer and throws on a second (`RendererAccess`); a second provider here
stops the game with both named (D6).

*Threads.* Vulkan states, command by command, which parameters a caller keeps to one thread
(Vulkan, Threading Behavior), and each operation here states its thread the same way. Forge's
current layer is a `ThreadLocal` (`ForgeHooksClient.renderLayer` in Cleanroom 0.7.14-alpha, read
with `javap`), and the same block code runs in the game's rebuild, in the provider's meshing and
in a mod's own previews, which set that layer themselves (GTCEu: Reloaded's
`MultiblockPreviewRenderer`). A provider's answers therefore hold only inside scopes it opens on
its meshing thread; every other call gets the absence answer. Membership callbacks run on the
provider's threads, several at once, and do not block; declarations are immutable snapshots
behind one volatile reference (JLS 17.4.5, 17.5); contributions are drawn on the client thread,
the one thread the GL context is current to (`wglMakeCurrent`).

*Absence.* As Fabric ships a default renderer behind its API (Fabric wiki, Rendering), the
absence answers implement every operation, in the same result types, with no exception or null
for absence and leases retired alike; only what is drawn differs, and `supports`, which no mod
needs for correctness.

*Implication:* each operation's contract states master plan 2's six items (inputs, ownership
transfer, thread and context, validity lifetime, expected failure, cleanup), its absence answer,
and the version and capability that introduced it. That is the contract format.

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
- Of the six mods that read pixels, two read depth: GregTech (`WorldSceneRenderer`) and
  ModularUI (`BaseSchemaRenderer`, `Projection`). Three mods create or attach a depth
  texture: IvToolkit, GroovyScript and Commons0815. Found by pairing each call with a depth
  constant in the same method, a heuristic that can report an unrelated pair.
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
4. Check that a stop raised after pre-initialization shows its screen: a second provider of the
   render interface registering in a later loading event (10.2, 11.2).

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
- Forge 1.12.2 Javadoc (14.23.5.2859), `Mod`, `FMLInterModComms`, `FMLLoadCompleteEvent`,
  `Loader`, `LoaderState`, `MinecraftForgeClient`, `ForgeHooksClient`, read 2026-11-21:
  https://skmedix.github.io/ForgeJavaDocs/javadoc/forge/1.12.2-14.23.5.2859/
- Cleanroom 0.7.14-alpha userdev JAR: `ForgeHooksClient` and `MinecraftForgeClient` member
  signatures, read with `javap`, 2026-11-21
- The Java Language Specification, Java SE 25: chapter 13 (13.2, 13.4.2.1, 13.4.9, 13.4.14,
  13.4.15, 13.4.26, 13.4.27, 13.5.2, 13.5.4, 13.5.7), 8.4.8, 8.4.8.1, 8.10.4, 17.4.5, 17.5:
  https://docs.oracle.com/javase/specs/jls/se25/html/
- Java SE 25 API, `ServiceLoader` and `java.util.concurrent` (memory consistency):
  https://docs.oracle.com/en/java/javase/25/docs/api/java.base/
- Vulkan specification: Extending Vulkan (version numbers, compatibility guarantees),
  Fundamentals (object model, threading behavior), Initialization (`VkApplicationInfo`):
  https://docs.vulkan.org/spec/latest/
- OpenXR 1.1 reference pages, `XrApplicationInfo` and `xrCreateInstance`:
  https://registry.khronos.org/OpenXR/specs/1.1/man/html/
- OpenGL 4 reference page, `glGetString`:
  https://registry.khronos.org/OpenGL-Refpages/gl4/html/glGetString.xhtml
- Semantic Versioning 2.0.0: https://semver.org/spec/v2.0.0.html
- OSGi, Semantic Versioning whitepaper (2019-01-10) and the `ProviderType` annotation (8.0.0):
  https://docs.osgi.org/whitepaper/semantic-versioning/
- J. des Rivières, Evolving Java-based APIs (rev. 1.2, 2007):
  https://wiki.eclipse.org/Evolving_Java-based_APIs
- Microsoft, "QueryInterface: Navigating in an Object" and `wglMakeCurrent`:
  https://learn.microsoft.com/en-us/windows/win32/
- Fabric API Javadoc, `RendererAccess` and `Renderer`, and the Fabric wiki draft "Rendering"
  (2023-09-13), read for behavior only: https://maven.fabricmc.net/docs/
- GTCEu: Reloaded at dd763c5f1, `MultiblockPreviewRenderer`, `WorldSceneRenderer`,
  `EmissiveOreBakedModel`, `LampBakedModel`, read for behavior, 2026-11-21
