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

*Measured on the RTX 3060 (VERIFICATION, rays through the scene).* The walk as shipped, at
settled views and radius 20: 2.1 million probe rays took 1.1, 4.9 and 12.2 ms at 16, 64 and
256 blocks (1,985, 429 and 172 million a second), and rays toward a sun 20 degrees up 1.4,
3.6 and 5.4 ms (1,550, 591 and 386 million); at 256 blocks they visit 34 and 41 nodes, 5.9
against 15.6 billion nodes a second. The earlier walk, which also passed blocks by the
coarser bits, at P0 clocks, settled views and radius 20: 2.1 million rays took 0.92 ms at 16 blocks, 3.9 ms at 64 and 9.3 ms
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
Measured on the GPU afterwards, it visits 1.3 to 1.7 times the nodes and takes 1.07 to 1.30
times as long for probe rays and 0.79 to 1.22 times for rays toward the sun (VERIFICATION,
rays through the scene).

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

## 7b. Rendering below output resolution

PLAN, capability and tier work, item 6, read on 2026-11-18. The tiers render LOW at 67% and
MEDIUM at 77% of the output's resolution, each with own temporal anti-aliasing and a spatial
scaler, and HIGH at 100% with own temporal anti-aliasing (ARCHITECTURE 12.1, D23). Everything
below is taken as ideas; the records are in PROVENANCE.md and the design is ARCHITECTURE 4.8.

**7b.1 Temporal accumulation. [V where read, K where marked]**
- Salvi, "An Excursion in Temporal Supersampling" (GDC 2016), slides read. Resolved color is
  reused instead of individual samples: an exponential moving average keeps the previous
  frame's result, typically 10% of the current frame against 90% of the history. Neighborhood
  clipping, credited to Malan (2012) and Karis (2014), tests whether the history is consistent
  with the current frame's neighborhood: the segment from the history color to the current
  sample is clipped against the neighborhood's convex hull in a color space such as RGB or
  YCoCg, in practice against the box of the neighborhood's minimum and maximum. A box far
  wider than the hull still ghosts. Variance clipping builds the box from the first two raw
  moments of the 3 by 3 neighborhood as mean ± γ standard deviations: γ = 1 works well, 0.75
  to 1.25 is the useful range, a larger γ is steadier and ghosts more, and the box may be
  clamped to the minimum and maximum box. The history is read with bicubic filtering.
- Karis, "High Quality Temporal Supersampling" (SIGGRAPH 2014, Advances in Real-Time
  Rendering). The course page and abstract were read: no MSAA, deferred-friendly, no spatial
  edge finding, and it covers HDR, ghosting, translucency, edges in motion and flicker. The
  27 MB slide deck could not be fetched in this pass. [K] for what follows: a Halton sequence
  of sub-pixel offsets; clipping in YCoCg toward the box's center; the motion of the nearest
  depth in the 3 by 3 neighborhood; the current frame filtered at the pixel's center by each
  sample's distance after its offset; history and current weighted by the inverse of their
  luminance against flicker; a per-material flag that raises the current frame's weight for
  particles and translucency.
- Pedersen, "Temporal Reprojection Anti-Aliasing in INSIDE" (GDC 2016), abstract read: a
  jittered frustum, reprojection into a history buffer and rules for accepting or rejecting
  history, with disocclusion and trailing as the problems addressed. [K] motion for static
  geometry from depth and the previous camera.
- Yang, Liu and Salvi, "A Survey of Temporal Antialiasing Techniques" (Computer Graphics
  Forum 39(2), 2020), abstract read: temporal anti-aliasing as sample accumulation plus
  history validation, with temporal upsampling as an extension of the same formulation.
- Patents, as found on their Google Patents pages (claims read through page summaries, not
  verbatim; not a legal reading). US 10,116,916, "Method for data reuse and applications to
  spatio-temporal supersampling and de-noising", NVIDIA, inventors Salvi, Patney and Lefohn,
  application 15/452,651 filed 2017-03-07, priority 2016-03-17 (provisional 62/309,922), shown
  active to 2037-04-04; its continuation US 10,362,289, application 16/052,537 filed
  2018-08-01, shown active to 2037-03-07. Claim 1 of the continuation: generate a frame; for
  each pixel read the previous frame's resolved color; build a bounding box in a color space
  whose planes are placed from a statistical distribution of the neighborhood's colors, with
  at least one neighborhood color outside it; adjust the resolved color by the box; blend.
  The mean and variance appear in dependent claims, as do clamping and clipping; the two
  summaries of the first patent's claim 1 disagree on whether it recites a mean and variance.
  Both patents' figures 8A and 8B show neighborhood color clamping or clipping with a minimum
  and maximum box "in accordance with the prior art"; figures 9A and 9B, the mean and
  standard deviation box, are an embodiment.
- Decision, 2026-11-18 (D23): the history is clipped to the 3 by 3 neighborhood's minimum
  and maximum, a box that holds every neighborhood color by construction; variance clipping
  is not used. It was built in scratch builds outside the repository only; the one run that
  tried to measure its gain was on a first design that failed, and on 2026-11-19 the user
  dropped the measurement (VERIFICATION, own temporal anti-aliasing and scaler).
*Implication:* the history test is the minimum and maximum box of the 3 by 3 neighborhood
(Malan, Karis).

**7b.2 Spatial upscaling. [V for the sources' scope, K for the kernels]**
- Keys, "Cubic convolution interpolation for digital image processing" (IEEE Transactions on
  Acoustics, Speech and Signal Processing 29(6), 1981) [K]: the separable cubic kernel with
  a = -1/2, interpolating and with a small negative lobe that keeps edges sharp.
- Li and Orchard, "New edge-directed interpolation" (IEEE Transactions on Image Processing
  10(10), 2001): local covariance of the low-resolution image adapts the interpolation, with
  plain bilinear where the image is smooth.
- Zhou, Shen and Dong, "Image zooming using directional cubic convolution interpolation"
  (IET Image Processing 6(6), 2012): cubic convolution along the stronger of two diagonal
  directions where an edge is found, plain cubic convolution elsewhere.
- Takeda, Farsiu and Milanfar, "Kernel Regression for Image Processing and Reconstruction"
  (IEEE Transactions on Image Processing 16(2), 2007): a steering kernel shaped by the local
  dominant orientation, so interpolation runs along edges rather than across them. Its patent,
  as found on its Google Patents page (claims through a page summary; not a legal reading):
  US 7,889,950, "Kernel regression for image processing and reconstruction", the Regents of
  the University of California, inventors Milanfar, Takeda and Farsiu, application 11/513,833
  filed 2006-08-30, priority 2005-08-30, granted 2011-02-15, shown active to 2029-11-26
  (an adjusted expiration the page does not explain; maintenance fees shown paid through
  2022-08-15). Claim 1: estimate local gradients by classic kernel regression; take their
  singular value decomposition for scaling, rotation and elongation, which define a steering
  kernel; apply steering kernel regression with it; repeat the last two steps, up to ten times.
  The steered kernel Quadrature built and measured took gradients by central differences,
  found an edge's direction and coherence from the eigenvectors of the 2 by 2 structure tensor
  (the same information as a singular value decomposition of the gradients), and interpolated
  once with a lengthened cubic kernel, without iterating. The comparison went to the user,
  who declined the steered kernel on 2026-11-19 (D23): measured against the separable cubic,
  it scored no better at either scale and cost more (below).
- Published before that patent's priority date of 2005-08-30, the ideas that kernel combined
  (facts only): the local orientation of an image from the eigenvectors of its gradients'
  second-moment matrix, the structure tensor, in Bigün and Granlund, "Optimal orientation
  detection of linear symmetry" (First International Conference on Computer Vision, 1987,
  pp. 433-438), and Kass and Witkin, "Analyzing oriented patterns" (IJCAI 1985; Computer
  Vision, Graphics, and Image Processing 37(3), 1987, pp. 362-385); a filter steered by the
  structure tensor's eigenvectors and coherence in Weickert, "Coherence-enhancing diffusion
  filtering" (International Journal of Computer Vision 31, 1999, pp. 111-127); interpolation
  guided by edges in Jensen and Anastassiou, "Subpixel edge localization and the interpolation
  of still images" (IEEE Transactions on Image Processing 4(3), 1995, pp. 285-295), Allebach
  and Wong, "Edge-directed interpolation" (Proceedings of ICIP 1996, vol. I, pp. 707-710), and
  Li and Orchard (2001, above); resampling under an elliptical kernel footprint in Greene and
  Heckbert, "Creating raster omnimax images from multiple perspective views using the
  elliptical weighted average filter" (IEEE Computer Graphics and Applications 6(6), 1986) [K,
  not re-read]; and Keys' kernel (1981).
- The scaler is the separable Keys cubic with the sharpening and hold below, and no edge
  steering. VERIFICATION compares it with the steered kernel at 67% and 77%: at both the
  steered kernel scored no better on any measure, and its pass cost 0.613 ms against 0.385 ms
  at 67% and 0.475 ms against 0.274 ms at 77%.
- NVIDIA Image Scaling is a comparison reference only and never shipped (D23); comparing
  against it needs a download, which is a gate.
*Implication:* the scaler reads the 4 by 4 texels around each output pixel's source point under
the separable Keys kernel. The result is held to the four central texels' range, which keeps
the negative lobe from ringing, with a small boost against a bilinear read for sharpness. No
kernel is steered along edges (D23).

**7b.3 This frame below output resolution. [V, read from the game's classes and Quadrature's
code]**
- *The world pass and its framebuffer.* `EntityRenderer.renderWorldPass` draws into the game's
  framebuffer (`Minecraft.getFramebuffer()`), which the game loop binds before
  `updateCameraAndRender` and copies to the window afterward. The pass sets its viewport once,
  at the `clear` section, to the display's size, through `GlStateManager.viewport`; a
  `Framebuffer` bound with its viewport also sets it there. Quadrature's own stages put the
  viewport back through the same call (`StateEnvelope.leave`).
- *What the hosted draws are.* The sky, with depth writes off, before terrain; clouds before
  terrain below height 128 and after translucent terrain above it; opaque terrain; entities and
  block entities, twice (the opaque pass and the translucent pass); the selection outline and
  block damage; lit and unlit particles; falling weather and the world border; translucent
  terrain, with depth writes off; Forge's last world drawing (`RenderWorldLastEvent`); then the
  hand after a depth clear, under its own projection. The interface is drawn after the pass, at
  the output's size.
- *What the pack does inside the pass.* GTCEu Reloaded draws its bloom where the game draws a
  block layer: it copies the game's framebuffer into framebuffers of its own sized like the
  game's, blurs, and draws the result back, binding framebuffers with their viewport and drawing
  full-screen quads through `Framebuffer.framebufferRender`, every frame (read from its classes'
  calls: `BloomEffectUtil.renderBloomInternal`, `PingPongBuffer`, `BloomEffect`; `mod_survey.py`
  lists it among mods with framebuffers of their own and direct viewport calls). A design that
  shrinks viewports of the display's size inside the pass shrinks both its copy and its draw
  back; a design that hands the whole pass a smaller framebuffer and display size keeps it
  consistent.
- *Where the picture must be whole.* The lab's tours take their capture in the last world
  drawing (`RivenClient.render`, a `RenderWorldLastEvent` handler reading the game's
  framebuffer), and mods draw there against the world's depth. The composite therefore runs at
  the boundary just before it, and the drawn depth is stretched over the
  whole buffer for what is drawn after.
- *How a jittered projection reaches everything.* Every projection of the world is built by
  loading the identity and calling `gluPerspective`: in `setupCameraTransform` (the world's),
  twice in `renderWorldPass` (the sky's, then the world's again) and twice in
  `renderCloudsCheck` (the clouds', then the world's). Hosted draws use what is left on the
  stack and do not build their own. A translation of clip space multiplied in just before
  `gluPerspective` therefore reaches terrain, entities, particles, block entities, weather and
  clouds. Own terrain reads the projection the driver holds, so it carries the offset too. The
  reversal of reversed depth changes z alone and the offset reads only w, so the two commute.
  The hand's projection is not offset; nor is anything after the composite, where the offset
  is taken back out of the projection on the stack.
- *How terrain gets motion.* From the depth buffer and two frames' views: each frame's
  projection without its offset, its model-view and its origin, the viewer's interpolated
  position in double precision. One matrix, composed in double precision, takes this frame's
  clip space to the previous one's, with the origin's movement between them; per pixel it is
  applied to the depth. Sky pixels hold the far plane and move with the camera's turn alone.
- *What has no motion.* Entities, particles, falling weather, the clouds' drift, animated
  textures (water, lava, fire, portals) and translucent surfaces, whose pixels keep the depth of
  what lies behind them. The hand is drawn after the composite and needs none. Treatment: the
  history is held to the box of the current neighborhood, which bounds any trail to what the
  frame shows now, and pixels whose depth changed after opaque terrain (entities, block
  entities, opaque particles, the outline, clouds above the player) take a higher share of the
  current frame. Motion vectors for hosted draws, from each draw's previous transform, remain
  the precondition D21 names for the Vulkan bridge.
- *Glowing entities.* `RenderGlobal.renderEntities` binds the outline framebuffer and later the
  game's with no viewport, and its shader group's passes (`Shader.render`) set the viewport to
  their targets' size and draw a quad in their pixels through a projection made from the group's
  main framebuffer, the game's own from creation (`ShaderGroup.resetProjectionMatrix`). Targets
  made at the drawn size would be covered only in part, so they stay at the output's size and the
  outlines are drawn into them at that size, with the drawn viewport set back afterward: the
  passes leave the viewport at the targets' size, and block entities are drawn next.
- *Not covered.* A mod that keeps the game's framebuffer from before the pass and draws into it
  there; anaglyph, which falls back to the output's size.

**7b.4 Ghosting needs a motion tour. [V]**
The lab's tours teleport to a view and wait until the player has held still for 400 ticks
before the capture, so the history has converged and nothing moves. A motion tour needs: a
camera path driven per tick and independent of frame rate, so each run's frames show the same
poses; a frame saved every tick while moving and for a while after stopping; the same path at
the output's resolution with the temporal pass off, and with it on at 100%, as references; and
moving content (animals or a scripted entity) for what reprojection cannot follow. Measures:
the energy of the difference to the reference behind moving edges, the change between
consecutive frames in regions that should hold still, and the frames until a disoccluded
region matches. A helper of that kind exists beside the lab: a client-only mod, inert unless
enabled, that glides the camera along a path set in ticks after a settle (in one scenario 16
blocks and 18 degrees over 80 ticks), saves a frame a tick, and closes the client; the near and
distant terrain join used it on 2026-11-17.

**7b.5 Motion for entities the game draws: a proposal. [P]**
The game draws entities through fixed-function OpenGL, so they cannot write a motion vector as they
are drawn, and reprojecting their pixels by the camera alone gives a moving mob the ground's motion.
What the game does know, per entity and per frame, is enough to give their pixels motion after the
fact: the position it draws the entity at (its last two tick positions interpolated by the frame's
partial tick), its body's yaw interpolated the same way, and its bounding box. Proposed, in three parts:
- *Per entity, two transforms.* At the entity stage, each entity drawn is noted with its interpolated
  position and yaw this frame, and the same from the frame before, kept by entity id for one frame.
  An entity with no entry from the frame before (it just came into view) has none, and its pixels
  keep the camera's motion and the higher share of the current frame they have today.
- *Which pixels are an entity's.* A pass of Quadrature's own, after the entity stage, takes each pixel
  whose depth changed during that stage (the mask the temporal pass already keeps), rebuilds its world
  position from depth, and tests it against the boxes of the entities noted, grown by a block's tenth.
  The box test keeps the ground under a mob's feet and a wall behind it out, since their depth did not
  change in the entity stage; the depth test keeps out a terrain pixel that lies inside a box.
- *The motion.* For a pixel inside an entity's box, its position the frame before is the entity's rigid
  motion applied to it: taken into the entity's frame by this frame's position and yaw and out again by
  the last frame's, then projected with the last frame's view, as the camera's reprojection already does
  for terrain. This covers walking, turning, falling and riding; a limb's own swing is not covered,
  and is left to the box clip, which already bounds what it can trail.

Costs: a list of a few hundred boxes a frame, one pass over the pixels whose depth the entity stage
changed. What it needs from the game is read, not changed. It leaves block entities (whose models move
in place, a chest's lid) and particles to the clip as now. It is the precondition D21 names for DLSS's
picture; it is also what the own temporal pass lacks on moving mobs. Before it is built: a look for
patents on reconstructing per-object motion vectors from depth and object transforms, as for the other
temporal techniques (D23), and a measure on a motion tour past a summoned golem, against camera-only
reprojection: the energy of the difference to a native-resolution reference behind the golem's edges.

The look, 2026-11-20. The nearest family found is Imagination Technologies' US 8,670,483 and its
continuation US 9,100,537 ("object tracking using graphics engine derived vectors", priority 2010-02-25,
estimated to expire 2031-02-24): it separates an object's motion from the camera's using the engine's
camera and depth, but every independent claim is a motion estimator over sets of candidate motion vectors,
determined and then adjusted for the camera's movement, for encoding, frame rate conversion and tracking.
The proposal forms no candidates and searches for nothing: each pixel's motion follows from two known rigid
transforms and the camera, so the determining and adjusting steps have nothing to read on. The primitive
reprojection patents US 6,111,582 and US 6,057,847 (2000) use an object's bounding box to bound its image
motion for scheduling resampling, not to give pixels motion. No claim found reads on the proposal.

## 7c. Bloom

Requirement 10, PLAN work order P3-R1; sources read on 2026-11-21. D25 fixes what blooms: the
emissive layer GTCEu: Reloaded declares and the contributions it submits, each of a bloom kind.

**7c.1 Published techniques. [V where read, K where marked]**
- Mittring, "The Technology Behind the Unreal Engine 4 Elemental Demo" (SIGGRAPH 2012, Advances in
  Real-Time Rendering), slides 61 to 63: five successive halvings, blurred as they are made against
  aliasing, recombined from the coarsest, each level blurred and added to the sum from below;
  blurring while upsampling barely changes the radius. Credited there to Kawase (GDC 2004) [K].
- Jimenez, "Next Generation Post Processing in Call of Duty: Advanced Warfare" (SIGGRAPH 2014,
  Advances): a filter pyramid chosen for temporal stability. His replies on his site, 2014-12-15 to
  2016-08-02: no threshold, the bloom mixed in at about 0.04; a weighted average on each 2 by 2
  block, renormalized; the tent's radius by default equal in texture space at every level; the
  recurrence is Mittring's. The deck itself was not read; [K] for its filters as Christensen
  (LearnOpenGL, 2022) reports them: 13 bilinear reads over 36 texels in five 2 by 2 boxes, the
  inner box weighted 1/2 and each corner box 1/8; a 3 by 3 tent of weights 1, 2, 1 while upsampling;
  the luma weight on the first halving only.
- Karis, "Tone mapping" (blog, 2013-12-15): samples weighted by 1/(1 + luma), divided by the
  weights' sum, average as if tone mapped and inverted; one bright sample cannot dominate.
- Bjørge, "Bandwidth-Efficient Rendering" (SIGGRAPH 2015, Moving Mobile Graphics): dual filtering
  halves with five reads (center 1/2, four diagonal corners 1/8) and doubles with eight (four on
  the axes 1/12, four diagonals 1/6). A 97 by 97 equivalent on a Mali-T760 MP8 takes 2.8 ms at
  1080p against Kawase's 4.5 and a separable Gaussian's 41.9, with 7% of the Gaussian's bandwidth
  against Kawase's 16%; both hold steady under sub-pixel motion, where plain mipmaps alias.
- Kawase (GDC 2003) [K], per Strugar (Intel, 2014): same-size passes of four bilinear corner reads
  at growing distance, 0, 1, 2, 2, 3 approximating a 35 by 35 Gaussian. On an R9 290X at 2560 by
  1600 a 127 by 127 Gaussian takes about 3 ms at full size, 0.5 ms at half, 0.17 ms at quarter.
- Epic, "Bloom in Unreal Engine" (5.9): five Gaussian blurs from half to 1/32 size, each sized in
  percent of screen width and tinted; threshold -1 admits every color; FFT convolution is for
  cinematics and high-end hardware. Unity HDRP 17.0: 13 prefilter samples in place of 4 reduce
  flicker from small bright objects. James and O'Rorke, "Real-Time Glow" (GPU Gems, 2004): sources
  marked by a mask in the texture's alpha, blurred at a third or a quarter size and added; small
  sources alias and flicker as they move.

**7c.2 Source. [P]** In the 8-bit frame a threshold cannot tell a lamp from sunlit snow or sky,
all near 1.0, and pixels crossing it pop under the sub-pixel offset. A per-pixel weight written by
the emissive layer and contributions blooms what GTCEu: Reloaded blooms today, the scene where its
layer drew (PLAN, Phase 3), in any light. In high dynamic range a constant weight on every pixel
adds Jimenez's thresholdless scatter to the same chain.

**7c.3 Place in the frame. [V for Unreal and Quadrature's code; P for the rest]** Epic's
screen-percentage page runs post-processing after the temporal upsampler at its output size, the
plain upscale in the tonemapper. Emissive geometry draws under the offset: a 4.5-pixel square
emitter covers 16 to 25 pixel centers by phase, so a halo taken from the drawn frame pulses with the
8-phase cycle, and a smooth halo added before the temporal pass changes more than its narrow
neighborhood box and passes the clamp. Under the temporal pass the chain therefore reads its output,
with the first weight resolved in the history's alpha (written as 1 and read by nothing before),
and is added after the scale pass, neither entering the history nor sharpened. Levels are sized
from the output, so the glow keeps its screen size across tiers. Without the temporal pass nothing
moves under an offset, and the chain reads the picture where GTCEu: Reloaded's own path did, after
translucent terrain.

**7c.4 Kinds. [V for the documentation; P for the rest]** GTCEu: Reloaded's generated
configuration gives OUT = BG + BLOOM s (b + LT + (1 - Y_BG)(HT - LT)) and three groups: emissive
textures s 1.5, b 0.1, HT 0.5, LT 0.2, 5 mips, step 1; fusion reactor 1.5, 0, 1.3, 0.3; heat effect
1.1, 0, 1.4, 0.6, the last two without mips or step. The factor is g0 - Y_BG (g0 - g1), with
g0 = s(b + HT) and g1 = s(b + LT), linear in BLOOM, so one chain carries the g0-weighted color and
the luma of the (g0 - g1)-weighted color of every kind, exact where one kind lights a pixel. A
radius changes the filter: each distinct radius in a frame is a chain; the three groups share one.

**7c.5 Cost. [M]** With RGBA16F levels the chain moves 4 + 9.33(1 - 4^(1-N)) bytes an output pixel,
13.3 at five levels, and the source 14 a drawn pixel: T = (14 s^2 + 13.3) W H / B + (2N - 1) t_d.
With B of 250 to 360 GB/s and t_d of 5 to 10 µs (assumed): 0.20 to 0.32 ms at 1920 by 1080 and
0.32 to 0.49 ms at 2560 by 1440 at 100%; 0.16 to 0.25 and 0.25 to 0.38 ms at 67%. Thirteen
bilinear reads a texel make 24 million in the first halving at 2560 by 1440; a work group loads its
footprint into shared memory once, as the scaler does. No published timing of this chain was found.

**7c.6 Patents. [V for the records, through page summaries; not a legal reading]** None found on
the 13-read chain, dual filtering, Kawase's passes, sums of Gaussians or the luma weight. Active and
near: US 11,100,701 (IMVU, to 2039-12-17), glow of marked parts through four frame buffers with
stencil data for the opaque ones; US 12,086,957 (Huawei, to 2041-08-23), a previous frame's blur
reused for a matching bright region; US 8,687,011 (Microsoft, to 2032-10-17), direct sampling or a
mipmap chosen against a kernel-size threshold; US 11,915,337 (AMD, to 2041-04-05), all levels in one
dispatch, the last work group finishing. The chain takes none of these steps: no stencil data and
one scene framebuffer, a blur computed every frame and kept nowhere, every level made with fixed
filters, one dispatch a level. Expired: US 7,414,625 (Microsoft glow), US 6,900,799 (Square Enix),
US 7,479,961 (Bandai Namco glare), US 6,925,210 (Herf).

*Implication:* the bloom is Mittring's recurrence with Jimenez's filters: a 13-read halving into N
levels (default 5, GTCEu: Reloaded's mips), a 3 by 3 tent while doubling at r texels of the level
read (r the kind's radius, GTCEu: Reloaded's step, held to 0.5 to 1.5; not a fixed texture-space
radius, which leaves holes at large radii), levels weighted equally, the luma weight on the first
halving from Phase 5, the 8-bit source being bounded and resolved. The source is the resolved
scene times each kind's weight, added by GTCEu: Reloaded's formula. P3-V1 measures the cost and
sets one calibration constant against GTCEu: Reloaded's glow.

## Follow-ups

1. A second, deeper pass on 7.1 to 7.3 with primary sources, once R1's hosted-rendering
   questions are answered; those answers constrain the pipeline shape.
2. Design the material model and write the LabPBR mapping channel by channel (7.6).
3. Read NVIDIA's DLSS programming guide itself (7.4).
4. Find measured costs for shadows at long range, or measure them (7.2).
5. Draft the shader format against two concrete shaders: the baseline and one with
   shadows and atmosphere (8.1).
6. Resolve the bloom weight where the handoff makes the picture (D21): its own temporal pass
   does not run there (7c.3).

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
- Salvi, "An Excursion in Temporal Supersampling", GDC 2016 (read 2026-11-18):
  https://developer.download.nvidia.com/gameworks/events/GDC2016/msalvi_temporal_supersampling.pdf
- Karis, "High Quality Temporal Supersampling", SIGGRAPH 2014 Advances in Real-Time Rendering
  (course page and abstract): https://advances.realtimerendering.com/s2014/index.html
- Pedersen, "Temporal Reprojection Anti-Aliasing in INSIDE", GDC 2016 (abstract):
  https://www.gdcvault.com/play/1022970/Temporal-Reprojection-Anti-Aliasing-in
- Yang, Liu and Salvi, "A Survey of Temporal Antialiasing Techniques", Computer Graphics Forum
  39(2), 2020 (abstract): https://research.nvidia.com/labs/rtr/publication/yang2020survey
- US 10,116,916 and US 10,362,289, "Method for data reuse and applications to spatio-temporal
  supersampling and de-noising" (records): https://patents.google.com/patent/US10116916
- Li and Orchard, "New edge-directed interpolation", IEEE Transactions on Image Processing
  10(10), 2001, pp. 1521-1527 (abstract).
- Zhou, Shen and Dong, "Image zooming using directional cubic convolution interpolation", IET
  Image Processing 6(6), 2012, pp. 627-634 (abstract).
- Takeda, Farsiu and Milanfar, "Kernel Regression for Image Processing and Reconstruction",
  IEEE Transactions on Image Processing 16(2), 2007, pp. 349-366 (abstract); patent
  US 7,889,950: https://patents.google.com/patent/US7889950
- Keys, "Cubic convolution interpolation for digital image processing", IEEE Transactions on
  Acoustics, Speech and Signal Processing 29(6), 1981, pp. 1153-1160.
- Wang, Bovik, Sheikh and Simoncelli, "Image quality assessment: from error visibility to
  structural similarity", IEEE Transactions on Image Processing 13(4), 2004, pp. 600-612.
- Mittring, "The Technology Behind the Unreal Engine 4 Elemental Demo", SIGGRAPH 2012 Advances in
  Real-Time Rendering, slides 35 and 61 to 63 (read 2026-11-21):
  https://de45xmedrsdbp.cloudfront.net/Resources/files/The_Technology_Behind_the_Elemental_Demo_16x9-1248544805.pdf
- Jimenez, "Next Generation Post Processing in Call of Duty: Advanced Warfare", SIGGRAPH 2014
  Advances in Real-Time Rendering (course page): https://advances.realtimerendering.com/s2014/index.html;
  the author's post and replies, 2014-12-15 to 2016-08-02: https://iryoku.com/?p=1707
- Christensen, "Phys. Based Bloom", LearnOpenGL guest article, 2022 (a secondary account of
  Jimenez's filters): https://learnopengl.com/Guest-Articles/2022/Phys.-Based-Bloom
- Karis, "Tone mapping", Graphic Rants, 2013-12-15: https://graphicrants.blogspot.com/2013/12/tone-mapping.html
- Bjørge, "Bandwidth-Efficient Rendering", SIGGRAPH 2015 course Moving Mobile Graphics, notes pp.
  20 to 31 and slides: https://community.arm.com/cfs-file/__key/communityserver-blogs-components-weblogfiles/00-00-00-20-66/siggraph2015_2D00_mmg_2D00_marius_2D00_notes.pdf
- Kawase, "Frame Buffer Postprocessing Effects in DOUBLE-S.T.E.A.L (Wreckless)", GDC 2003 (entry
  only): https://www.gdcvault.com/play/1022664/Frame-Buffer-Postprocessing-Effects-in
- Strugar, "An Investigation of Fast Real-Time GPU-Based Image Blur Algorithms", Intel, 2014:
  https://www.intel.com/content/www/us/en/developer/articles/technical/an-investigation-of-fast-real-time-gpu-based-image-blur-algorithms.html
- Epic Games, "Bloom in Unreal Engine" and "Screen Percentage with Temporal Upscale" (Unreal Engine
  5.9 documentation): https://dev.epicgames.com/documentation/en-us/unreal-engine/bloom-in-unreal-engine
- Unity, HDRP 17.0 manual, "Bloom":
  https://docs.unity3d.com/Packages/com.unity.render-pipelines.high-definition@17.0/manual/Post-Processing-Bloom.html
- James and O'Rorke, "Real-Time Glow", GPU Gems, chapter 21, 2004:
  https://developer.nvidia.com/gpugems/gpugems/part-iv-image-processing/chapter-21-real-time-glow
- Patents read through their records (2026-11-21): US 11,100,701, US 12,086,957, US 8,687,011,
  US 11,915,337, US 9,558,536, US 8,970,614, US 7,414,625, US 6,900,799, US 7,479,961, US 6,925,210:
  https://patents.google.com/
- GTCEu: Reloaded's generated configuration documentation (`gregtech.cfg`: shader options, fusion
  reactor, heat effect), read 2026-11-21
