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

**7.8 Rays through occupancy grids and bricks on the GPU. [V for the methods; measured
here, 2026-11-18]**
Indirect light from probes and shadows beyond the light's depth both reduce to one
operation: from a point, along a direction, the distance to the first cell that blocks
light. The scene for lighting already holds what that needs (ARCHITECTURE 5.1).
- **The grid walk.** Amanatides and Woo (1987) walk a uniform grid one cell at a time:
  keep, for each axis, the distance along the ray to the next cell boundary and cross the
  smallest; a step costs two comparisons and one addition. Later voxel ray casters build
  on it; Laine and Karras (2010) name it the basis of most derivative work, theirs
  included.
- **Hierarchies and bricks.** Two-level grids (Kalojanov, Billeter and Slusallek, EG
  2011) nest a fine grid in each coarse cell, are among the fastest structures to build on
  a GPU, and trace about as fast as other structures for dynamic scenes. Museth's
  hierarchical walk in OpenVDB (SIGGRAPH 2014 talks) steps a whole node at a time where a
  node is empty and descends where it is not; he reports stopping its descent at the leaf
  nodes as a good balance between a fast walk and empty-space skipping. GigaVoxels
  (Crassin, Neyret, Lefebvre and Eisemann, I3D 2009) keeps an octree whose nodes point at
  fixed-size bricks in a GPU pool, skips empty nodes and marches inside bricks until a
  sample is opaque. Van Wingerden (Utrecht, 2015) keeps raw layered grids rather than a
  sparse octree so the data can be edited without preprocessing.
- **What a voxel caster reaches.** Laine and Karras's sparse voxel octree cast 60.9
  million primary rays a second through a displaced Sibenik cathedral of 2.7 GB and 122.0
  million through the plain one, on a GTX 285, where the fastest triangle caster of the
  time reached 107.1 million on the same hardware.
- **Coherence on NVIDIA hardware.** Threads run in warps of 32 that share control flow
  (SIMT). Aila and Laine (HPG 2009) measured trace kernels on a GTX 285 and found them 1.5
  to 2.5 times below a simulated bound, mostly through how work is distributed and how
  many lanes of a warp do useful work, not through memory bandwidth; a few long rays keep
  a whole warp waiting. Their best kernel traced 135.6 million primary, 130.7 million
  ambient occlusion and 62.4 million diffuse rays a second in the Conference scene:
  incoherent rays at about half the rate of coherent ones. The 2012 addendum (Aila, Laine
  and Karras, NVR-2012-02) measured 432.6 and 245.4 million on a GTX 680, found throughput
  following peak arithmetic rather than bandwidth, and found Kepler's L1 cache useful
  only for coherent access, so that incoherent rays did better fetching through the
  texture path. NVIDIA's Turing tuning guide describes a unified L1 and texture cache that
  gathers a warp's requests before delivering them, and warns that independent thread
  scheduling does not make long divergent stretches free.
- **Probes.** Dynamic diffuse global illumination (Majercik, Guertin, Nowrouzezahrai and
  McGuire, JCGT 2019) places probes on a grid about the camera; each casts a set of rays
  spread over the sphere every frame. Rays of one probe share an origin and little else:
  incoherent by construction. Rays toward the sun from neighboring points are parallel
  and start side by side: coherent.

*Implication, taken in the build:* the scene's own layout is already a grid of cubes over
grids of cells, so nothing is built for rays. A cube of one state that does not block is
passed whole, and a cell is tested with one 32-bit load. The bricks' coarser bits, which
would let a block of two cells a side be passed whole, are not read (7.8a). The walk
computes every distance afresh from the ray's
origin rather than accumulating it, so that its results can be checked against a CPU
reference to the bit (ARCHITECTURE 5.2). Probe rays and shadow rays are measured apart,
since the published ratio between incoherent and coherent rays is about two.

*Measured on the RTX 3060 (VERIFICATION, rays through the scene),* with the earlier walk
that also passed blocks by the coarser bits: at P0 clocks, settled views and radius 20,
2.1 million rays took 0.92 ms at 16 blocks, 3.9 ms at 64 and 9.3 ms
at 256 for probe rays (2,290, 534 and 224 million a second), and 1.3, 3.2 and 4.8 ms for
coherent rays toward a sun 20 degrees up (1,580, 657 and 438 million). At 256 blocks the
probe rays visit 24 nodes and the coherent ones 26, so the published ratio holds here too:
incoherent rays at half the rate per node visited, 5.3 against 11.2 billion nodes a second.
Batches of 65,536 rays run at 51 to 71% of the rate of 2 million, too few to fill the card.
Two findings beyond the literature: the radius barely changes the rate (probe rays at 256
blocks: 283, 231 and 224 million a second at radii 12, 16 and 20, most of the difference rays
leaving the smaller box early); and incoherent rays are sensitive to where the driver keeps
the bricks. A buffer the CPU has read back may be kept in host memory for a while, and
probe rays traced from it took three to twelve times as long while coherent rays barely
changed. Reading the lookup and bricks through buffer textures rather than storage blocks
made under 5% difference on this card, whose L1 and texture caches are one.

**7.8a Patents searched for the ray work. [2026-11-19; a record, not a legal conclusion]**
Searched on Google Patents, its search pages and each patent's page with the claims as
text, after general searches to find candidates. Statuses are Google Patents' on that day.
Whether anything below may ship is the user's decision.

*The grid walk to the nearest boundary (Amanatides and Woo).*
- US 7,088,361 B2, "Raycasting perspective views", IBM, later LinkedIn; priority 2003-05-14
  (GB 0311048.3); expired, fees unpaid. Claim 1: incremental 3-D DDA denominator vectors
  across a line of samples.
- CN 102509341 B, "Method for intersecting light and voxel", Institute of Automation,
  Chinese Academy of Sciences; priority 2011-10-17; expired, fees unpaid. Claim 1: isosurface
  intersection from trilinear samples along the ray in each voxel.
- US 11,282,260 B2, "Bounded volume hierarchy (BVH) tree traversal using spatial division",
  Qualcomm; priority 2020-06-09; active, anticipated expiry 2040-06-09. Claim 1: a BVH, a grid
  generated from it with each node mapped to cells, a start node chosen from the cell of the
  ray's first intersection, and a 3-D DDA to a further cell where a cell is empty or visited,
  ending on the distance to a primitive. Citations it names include Siemens' bricked volumes
  (US 2009/0295800 A1), Arm (US 2014/0139534 A1), Samsung (US 9,355,491 B2) and Qualcomm
  (US 2017/0249779 A1). The trace has no BVH, no mapping of nodes to cells, no primitives.

*Hierarchical DDA (Museth; OpenVDB, NanoVDB) and two-level grids (Kalojanov, Billeter,
Slusallek).* No patent was found claiming either. The phrase "hierarchical digital
differential analyzer" appears in four pending applications (Tencent US 2025/0218111 A1, a
light update for a virtual scene, priority 2023-03-21; NTT Docomo JP 2025-165258 A;
CN 121616734 A; NetEase CN 120346519 A), a matter for the lighting work. Ken Museth's eight
patents claim neither; the nearest, Digital Domain's US 2010/0114909 A1 (priority 2008-11-05,
blocks stored as value tables and element bit-masks), is abandoned. "Two-level grid" with
an acceleration structure and rays: nothing. Imagination's US 8,717,357 B2 (priority
2011-08-05, active to 2032) claims building an acceleration structure by binning primitives
into volumetric portions; Mental Images' JP 4858795 B2 (active to 2027, Japan) a bounding
interval hierarchy; its US 7,952,583 B2 has expired.

*Raycasting with an approximate traversal.* WO 2020/047338 A1, "Computer vision system",
Movidius, now Intel; priority 2018-08-29. Its first claim casts rays through a sparse
hierarchical voxel structure and finds occupied voxels "according to an approximate
traversal", which leaves some lower-level voxels unchecked. The granted members, US
12,122,420 B2 (active to 2041), CN 113228114 B, JP 7485482 B2, JP 7665901 B2 and KR
102933525 B1, claim pose estimation from visual and inertial data by a neural network
instead; ray claims remain only in the pending EP 3844722 and DE 11 2019 004364. The trace
checks every cell a walk of single cells would.

*Probe grids casting rays over the sphere (DDGI), the benchmark's workload only.* US
2021/0012562 A1, "Probe-based dynamic global illumination", NVIDIA (Luebke, Majercik,
McGuire); priority 2019-07-11; finally rejected 2021-09-08 and abandoned 2023-08-23 after
an appeal. Claim 1: computing an irradiance field probe's diffuse irradiance and the
statistics of a distance distribution, and encoding both in the probe. Prior art named in
its background: light probes holding spherical irradiance maps, and light field probes.

*Occupancy bits at several levels of detail: in force, with claims near the trace.* The
family "Rendering operations using sparse volumetric data", Movidius, now Intel; priority
2016-08-19 (US provisional 62/377,471; PCT/US2017/047696); anticipated expiry 2037-08-19 for
the members read: US 10,748,326 B2, CN 110494895 B, KR 102546996 B1 and EP 3501008 B1, all
active. Claim 1 of US 10,748,326 B2, word for word from its page
(https://patents.google.com/patent/US10748326B2/en):

> 1. An apparatus comprising: a data processing apparatus; lookup logic to: identify
> coordinates of a ray to be cast into a particular volume; obtain a volumetric data
> structure corresponding to the particular volume, wherein the volumetric data structure
> describes the particular volume at a plurality of levels of detail, a first entry in the
> volumetric data structure comprises a first set of bits representing voxels at a first one
> of the plurality of levels of detail, the first level of detail comprises the lowest level
> of detail in the plurality of levels of detail, values of the first set of bits indicate
> whether a corresponding one of the voxels is at least partially occupied by respective
> geometry, wherein the volumetric data structure further comprises a number of second
> entries representing voxels at a second level of detail higher than the first level of
> detail, and the voxels at the second level of detail represent subvolumes of the voxels at
> the first level of detail; determine that the ray is to pass through a particular subset of
> the voxels at the first level of detail; and intersection logic to determine, from the
> first entry in the volumetric data structure, that at least a particular one of the
> particular subset of voxels is occupied by geometry.

Its claims 16 (a method) and 17 (a system that renders by casting rays to find their
reflections) also involve rays, as do claims 1 and 16 of CN 110494895 B. The trace as first
committed tested a brick's coarser bit for the block of two cells a side a ray stood in,
passed the block whole where the bit was clear, and otherwise tested the cell's bit; above
the bricks the cube lookup holds 32-bit codes (nothing known, one state, a brick's slot), not
occupancy bits.

*Claims of the family that recite no ray.* Main line's scene has carried the coarser bits
since 2026-11-15. Independent claims found that recite no ray each need more than the
occupancy bits:
- EP 3501008 B1, claim 1: "the number of second entries corresponds to a number of bits in
  the first set of bits with values indicating that a corresponding voxel volume is
  occupied", the entry "immediately following the first entry" holding the occupied voxel's
  subvoxels, and the structure used to identify geometry; a ray comes only in a dependent
  claim. KR 102546996 B1's claims, read in part in the page's machine translation, run
  parallel. The scene's brick is 128 words of bits for all 4,096 cells, then
  16 words of coarser bits: every cell's bit is kept, before the coarser bits, however many
  of those are set.
- US 11,222,459 B2, claims 1, 15 and 17: loading "a portion of the volumetric data structure"
  into a second or on-board memory "based on the portion of the volumetric data structure
  representing a particular subset of the plurality of levels of detail". The scene's buffers
  receive whole bricks, both levels, of the cubes that changed, 64 a frame.
- US 11,965,743 B2, claim 1: rendering through a machine learning model fed the occupied
  voxels' values. Quadrature has no such model.

A walk that reads no coarser bit, passing cubes of one state whole and stepping a cell at a
time inside bricks, ends with the same distance and outcome for every ray, and in the same
cell for every ray that does not miss, visiting about one and a half times the nodes in the
unit tests' scenes. Set beside claim 1, its coarsest level is the lookup's codes and its one
level of occupancy bits is the cells'.

*Decision, 2026-11-19:* the user chose the walk that reads no coarser bit. It replaces the
walk with coarser bits on the branch in a later commit, history kept: the trace and its CPU
reference no longer read the bricks' coarser bits, which main line's scene still builds.

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
- Amanatides and Woo, "A Fast Voxel Traversal Algorithm for Ray Tracing", Eurographics 1987:
  https://www.eecs.yorku.ca/~amana/research/grid.pdf
- Kalojanov, Billeter and Slusallek, "Two-Level Grids for Ray Tracing on GPUs", Computer
  Graphics Forum 30(2), Eurographics 2011: https://diglib.eg.org/items/71143a61-9f86-414b-abac-12669ca84360
- Museth, "Hierarchical Digital Differential Analyzer for Efficient Ray-Marching in OpenVDB",
  SIGGRAPH 2014 talks, slides:
  https://research.dreamworks.com/wp-content/uploads/2018/07/talk_hierarchical_digital_OpenVDB_v4-Edited.pdf;
  his note on stopping at leaf nodes: https://groups.google.com/g/openvdb-forum/c/Bl0ip1_rX2M
- Crassin, Neyret, Lefebvre and Eisemann, "GigaVoxels: Ray-Guided Streaming for Efficient
  and Detailed Voxel Rendering", I3D 2009: https://graphics.tudelft.nl/Publications-new/2009/CNLE09a
- Van Wingerden, "Real-time Ray Tracing and Editing of Large Voxel Scenes", Utrecht
  University thesis, 2015: https://studenttheses.uu.nl/handle/20.500.12932/20460
- Laine and Karras, "Efficient Sparse Voxel Octrees", I3D 2010:
  https://www.nvidia.com/docs/IO/88889/laine2010i3d_paper.pdf
- Aila and Laine, "Understanding the Efficiency of Ray Traversal on GPUs", HPG 2009, Table 1:
  https://research.nvidia.com/sites/default/files/pubs/2009-08_Understanding-the-Efficiency/aila2009hpg_paper.pdf
- Aila, Laine and Karras, "Understanding the Efficiency of Ray Traversal on GPUs: Kepler and
  Fermi Addendum", NVIDIA NVR-2012-02, Table 1:
  https://research.nvidia.com/sites/default/files/pubs/2012-06_Understanding-the-Efficiency/nvr-2012-02.pdf
- NVIDIA, "Tuning CUDA Applications for Turing" (unified L1 and texture cache, divergence):
  https://docs.nvidia.com/cuda/turing-tuning-guide
- Majercik, Guertin, Nowrouzezahrai and McGuire, "Dynamic Diffuse Global Illumination with
  Ray-Traced Irradiance Fields", JCGT 8(2), 2019:
  https://research.nvidia.com/publication/2019-05_dynamic-diffuse-global-illumination-ray-traced-irradiance-fields
- Jarzynski and Olano, "Hash Functions for GPU Rendering", JCGT 9(3), 2020: https://jcgt.org/published/0009/03/02/
