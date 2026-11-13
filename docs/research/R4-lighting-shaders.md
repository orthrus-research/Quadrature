# R4: lighting, shading and the shader system

Requirement sections 7 and 8. First pass, 2026-11-13. Basis marks are defined in
[README.md](README.md). This pass is the thinnest of the five: most findings are [K].

## 7. Lighting and shading

**7.1 Pipeline shape. [K]**
- Deferred shading with a geometry buffer suits this frame: hosted draws can write the
  same outputs as terrain (R1, 9.1), and many small block lights are cheap to accumulate.
- Translucent surfaces are shaded forward, after the opaque lighting.
- A visibility buffer is ruled out by hosted draws, which cannot supply primitive
  identifiers.
- Clustered light lists apply to either shape if block lights become real lights.

**7.2 Shadows. [K, M]**
Cascaded shadow maps are the baseline. Virtual shadow maps give stable resolution at
range at a high implementation cost. For distant terrain, shadowing from the terrain's own
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
- DLSS: no OpenGL path was found; its SDK targets DirectX and Vulkan [K].
- Temporal anti-aliasing is written in-house in every case.

**7.5 Output. [K]**
Exposure, tone mapping (ACES, AgX and Khronos PBR Neutral are the current choices), and
display encoding belong to the final stage.

**7.6 Material data. [K]**
Most blocks ship a color texture only. Sources of material data: a resource pack
convention for normal and specular textures in wide use by the community (to be read and
cited), per-block definitions shipped by Quadrature and by mods through the mod interface,
and defaults derived from the block's own properties.

**7.7 Baseline shader. [K]**
A shader that reproduces the game's look is the reference for image comparison in P1 and
P2, and the fallback for players who select no other.

## 8. Shader system

**8.1 Format. [K]**
Open design. Elements common to current engines: stage entry points with declared inputs
and outputs, shared include files, compile-time options that produce permutations, and
metadata for options shown in settings.

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
2. Read and cite the community material texture convention (7.6).
3. Confirm DLSS's API support from NVIDIA's own documentation (7.4).
4. Draft the shader format against two concrete shaders: the baseline and one with
   shadows and atmosphere (8.1).

## Sources

- Crassin et al., "Interactive Indirect Illumination Using Voxel Cone Tracing" (2011):
  https://research.nvidia.com/sites/default/files/publications/GIVoxels-pg2011-authors.pdf
- CryEngine, voxel-based global illumination documentation:
  https://www.cryengine.com/docs/static/engines/cryengine-3/categories/1114113/pages/19377157
- "The Technology of The Tomorrow Children", thesis discussion:
  https://cgvr.informatik.uni-bremen.de/theses/finishedtheses/VoxelConeTracing/TheTechnologyOfTomorrowsChildrenFinal.pdf
- "Integrating AMD FSR1 for OpenGL": https://codeonwort.github.io/articles/fsr1-for-opengl.html
- "Porting FSR 2 to OpenGL": https://juandiegomontoya.github.io/porting_fsr2.html
- AMD, FSR 1 requirements: https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-spatial/
