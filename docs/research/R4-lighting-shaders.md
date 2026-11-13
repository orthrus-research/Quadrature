# R4: lighting, shading and the shader system

Requirement sections 7 and 8. First pass and a reading pass, 2026-11-13. Basis marks are
defined in [README.md](README.md). Several findings here are still [K].

## 7. Lighting and shading

**7.1 Pipeline shape. [K]**
- Deferred shading with a geometry buffer suits this frame: hosted draws can write the
  same outputs as terrain (R1, 9.1), and many small block lights are cheap to accumulate.
- Translucent surfaces are shaded forward, after the opaque lighting.
- A visibility buffer is ruled out by hosted draws, which cannot supply primitive
  identifiers.
- Clustered light lists apply to either shape if block lights become real lights. [V]
  Olsson, Billeter and Assarsson (HPG 2012) group view samples by three-dimensional
  position, which maps lights to samples better than two-dimensional tiles and holds up
  under large depth discontinuities; they report scenes with up to about a million
  lights. Their follow-up reports clustered forward shading outperforming tiled forward
  by more than two times, and notes that with tiles every transparent layer must consider
  every light in the tile. Clusters are therefore the better fit for water and glass.

**7.2 Shadows. [V for structure, M for cost]**
Cascaded shadow maps are the baseline. Unreal Engine's virtual shadow maps [V, Epic
documentation] use clipmap levels around the camera for directional lights, each level a
16K virtual map at the same resolution covering twice the radius of the one before, backed
by pages allocated on demand; Epic presents them as the replacement for cascades. No
published cost comparison at long range was found. For distant terrain, shadowing from the terrain's own
height or voxel data is a candidate that avoids rendering levels of detail into shadow
maps. Costs are unmeasured.

**7.3 Ambient occlusion and indirect light without hardware ray tracing. [V]**
- Voxel cone tracing (Crassin et al., 2011) reported 25 to 70 frames per second on a
  GTX 480. CryEngine shipped a production variant that injects light into a voxelization.
- The Tomorrow Children used concentric cascades of voxel textures instead of a sparse
  octree.
- Radiance cascades: only two-dimensional work was found; no three-dimensional voxel
  version surfaced in this pass.
*Implication:* a voxel world already is the voxelization these methods build at runtime,
so cascaded voxel volumes around the camera are the natural candidate. Colored block light
as a propagated light volume is a related candidate [K, not sourced in this pass].

**7.4 Anti-aliasing and upscaling on OpenGL. [V]**
- FSR 1 is a pair of shader passes and has been run in OpenGL fragment shaders, though AMD
  tests and supports only Vulkan and DirectX. A community port of FSR 2 to OpenGL is
  documented.
- DLSS: summaries of NVIDIA's programming guide (version 310.5.0, December 2025) list
  DirectX 11, DirectX 12 and Vulkan on Windows and Vulkan on Linux. OpenGL is not listed.
  The guide itself was not read; the finding rests on secondary summaries.
- Temporal anti-aliasing is written in-house in every case.

**7.5 Output. [K]**
Exposure, tone mapping (ACES, AgX and Khronos PBR Neutral are the current choices), and
display encoding belong to the final stage.

**7.6 Material data. [V for the convention]**
Most blocks ship a color texture only. Three sources of material data:
- **LabPBR 1.3**, the resource pack convention in wide use (page last edited 2026-02-20).
  It defines two extra textures per block texture:
  - `_n`: normal X and Y in red and green (Y down), with Z reconstructed; ambient
    occlusion in blue; height in alpha.
  - `_s`: perceptual smoothness in red (`roughness = (1 - smoothness)^2`); reflectance in
    green for values 0 to 229, and predefined metals for 230 to 254 (iron, gold, aluminum,
    chrome and others, by measured optical constants), 255 meaning "use the color as
    reflectance"; porosity (0 to 64) and subsurface scattering (65 to 255) in blue;
    emission in alpha.
  A reader is required to honor only smoothness and reflectance.
- Per-block definitions shipped by Quadrature and by mods through the mod interface.
- Defaults derived from the block's own properties.
LabPBR is a resource pack format, not a shader pack format, so reading it does not conflict
with decision D5. Decision D13: Quadrature designs its own material model and maps LabPBR
into it at load.
*Implication:* the model needs at least what LabPBR carries (normal, occlusion, height,
roughness, reflectance or a metal identity, porosity, subsurface scattering, emission), so
nothing a conforming pack provides is discarded. What it carries beyond that is open.

**7.7 Baseline shader. [K]**
A shader that reproduces the game's look is the reference for image comparison in P1 and
P2, and the fallback for players who select no other.

## 8. Shader system

**8.1 Format. [K]**
Open design. Elements common to current engines: stage entry points with declared inputs
and outputs, shared include files, compile-time options that produce permutations, and
metadata for options shown in settings.

**8.1a Permutations. [V]**
Each boolean feature compiled as a variant doubles the variant count; engines report
thousands to millions, most never used, with long builds, memory cost and compile stutter
as the result. The published remedies are to strip variants that are redundant or unused,
which the same source calls the least effective fix, and to specialize late: precompile
modules once and supply constants when a program is linked. Vulkan and Metal expose that
as specialization constants; OpenGL has no equivalent.
*Implication:* a Quadrature shader's options are resolved when the shader is loaded or an
option changes, producing exactly the programs the selected options need, compiled then
and cached. No variant is compiled for an option combination a player has not selected.
Options that can be a uniform instead of a compile-time switch should be.

**7.4a Temporal anti-aliasing practice. [V]**
The common pipeline: jitter the projection each frame; reproject each pixel into the
previous frame with its motion vector and the jitter difference; reject history that
falls off screen or across a depth discontinuity; clamp what remains to the current
frame's neighborhood, by its bounding box or by mean and deviation of the 3 by 3
neighborhood (a factor near 1.25 is a published starting point). The scene must supply
matching depth and motion vectors every frame. Aggressive clamping blurs; no source
addresses blocky, high-contrast content, so that has to be tested on Quadrature's frames.
*Implication:* motion vectors are a geometry buffer output from the start, for terrain
and for hosted draws, where they come from the previous frame's transform.

**8.2 Compilation. [V, dated]**
See R1, 1.5: compile and link at load, cache program binaries, do not rely on parallel
compile until tested. SPIR-V input is part of OpenGL 4.6 core [K]; its benefit on NVIDIA's
OpenGL driver is unmeasured.

**8.3 Interfaces. [K]**
Uniform buffers for per-frame and per-view data, shader storage buffers for per-section
and per-light data, fixed binding points assigned by the pipeline and documented in the
format.

## Follow-ups

1. A second, deeper pass on 7.1 to 7.3 with primary sources, once R1's hosted-rendering
   questions are answered; those answers constrain the pipeline shape.
2. Design the material model and write the LabPBR mapping channel by channel (7.6).
3. Read NVIDIA's DLSS programming guide itself (7.4).
4. Find measured costs for shadows at long range, or measure them (7.2).
5. Draft the shader format against two concrete shaders: the baseline and one with
   shadows and atmosphere (8.1).

## Sources

- Crassin et al., "Interactive Indirect Illumination Using Voxel Cone Tracing" (2011):
  https://research.nvidia.com/sites/default/files/publications/GIVoxels-pg2011-authors.pdf
- CryEngine, voxel-based global illumination documentation:
  https://www.cryengine.com/docs/static/engines/cryengine-3/categories/1114113/pages/19377157
- "The Technology of The Tomorrow Children", thesis discussion:
  https://cgvr.informatik.uni-bremen.de/theses/finishedtheses/VoxelConeTracing/TheTechnologyOfTomorrowsChildrenFinal.pdf
- Pettineo, "The Shader Permutation Problem", parts 1 and 2:
  https://therealmjp.github.io/posts/shader-permutations-part2
- Unity, shader variants: https://docs.unity3d.com/Manual/shader-variants.html
- Slang, link-time specialization (documentation copy):
  https://shader-slanggithubio-aidanfnv-wip.readthedocs.io/en/latest/_sources/external/slang/docs/user-guide/10-link-time-specialization.md.txt
- Arm, temporal anti-aliasing:
  https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/temporal-anti-aliasing
- luma.gl, temporal antialiasing pass:
  https://luma.gl/docs/api-reference/shadertools/shader-passes/temporal-antialiasing
- NVIDIA, "Adaptive Temporal Antialiasing" (2018):
  https://research.nvidia.com/sites/default/files/pubs/2018-08_Adaptive-Temporal-Antialiasing/adaptive-temporal-antialiasing-preprint.pdf
- Olsson, Billeter and Assarsson, "Clustered Deferred and Forward Shading", HPG 2012:
  https://www.cse.chalmers.se/~uffe/clustered_shading_preprint.pdf
- Olsson et al., "Tiled and clustered forward shading: supporting transparency and MSAA":
  https://research.chalmers.se/en/publication/161726
- Epic Games, "Virtual Shadow Maps in Unreal Engine":
  https://dev.epicgames.com/documentation/unreal-engine/virtual-shadow-maps-in-unreal-engine
- LabPBR Material Standard 1.3: https://shaderlabs.org/wiki/LabPBR_Material_Standard
- NVIDIA DLSS programming guide, summarized: https://deepwiki.com/NVIDIA/DLSS
- "Integrating AMD FSR1 for OpenGL": https://codeonwort.github.io/articles/fsr1-for-opengl.html
- "Porting FSR 2 to OpenGL": https://juandiegomontoya.github.io/porting_fsr2.html
- AMD, FSR 1 requirements: https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-spatial/
