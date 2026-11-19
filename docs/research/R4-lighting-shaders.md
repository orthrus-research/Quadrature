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
  is not used. Its code is kept outside the repository and measured from scratch builds only,
  so that the decision can be revisited with its measured quality gain (VERIFICATION, own
  temporal anti-aliasing and scaler).
*Implication:* the history test is the minimum and maximum box of the 3 by 3 neighborhood
(Malan, Karis).

**7b.2 Edge-adaptive spatial upscaling. [V for the sources' scope, K for the kernels]**
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
  once with a lengthened cubic kernel, without iterating. The comparison was sent to the
  project for a decision, and the steered kernel is not used (D23).
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
