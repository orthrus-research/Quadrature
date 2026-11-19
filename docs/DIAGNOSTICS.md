# Cross-mod diagnostics

`tools/diagnostics.py` measures where a Riven world's time goes, by mod: Quadrature, Cubature,
Riven, the other mods of the pack, the game, Forge and Cleanroom. It answers three questions:
which mod costs the most on the threads a player waits on, what the engine still does for each mod
that the mod could do itself, and what Cleanroom and Forge cost of their own. `tools/mod_index.py`
reads the mods' jars for the parts no recording shows: their Mixins, the engine systems those
patch, the events they subscribe to and the threads they start.

## Commands

```powershell
python tools/diagnostics.py check PLAN
python ../Riven/tools/gpu_queue.py turn --lane near-terrain --note "..." --session <id> -- python tools/diagnostics.py run PLAN
python tools/diagnostics.py attribute RUN_DIR
python tools/diagnostics.py report RUN_DIR [RUN_DIR ...] --markdown report.md
python tools/mod_index.py MODS_DIR [MODS_DIR ...] --json mods.json
```

`run` refuses to start outside a queued GPU turn. `check`, `run` and `attribute` need `JAVA_HOME`
set to a JDK whose `bin` holds `jfr` (the turn's command inherits it); `--cores` is the logical
processor count of the machine that ran it (default: this one's).

## Plans

A plan is a JSON file, kept with the run's evidence outside the repository: the Riven checkout,
the frozen Riven jar, the Cubature jar, the client mods (Quadrature, its NVIDIA module when DLSS
runs, and the motion helper when a flight runs), a fresh instance name, the run directory, the
switches common to every run, and the runs. A run is a tour (`"tour"`: a tour file) or a flight
(`"flight"`: a start pose `[x, y, z, yaw, pitch]`, a route of waypoints `[x, y, z]` or
`[x, y, z, dwell seconds]`, a speed and acceleration, a corner radius, a turn rate, laps, and the
settle, stationary and hold seconds around it).

A flight is the motion helper's (0.2.0 or later) route flight, not a series of teleports: the
launcher places the player at the start, the helper waits for it to settle, then moves the player
every tick along the route, so the client reports each step and the server loads the world ahead
and unloads it behind as for a player. The flier accelerates from rest to its cruise speed and
brakes into each stop, rounds corners, and turns its view toward travel at a bounded rate; a
dwell is a stop to look around; a loop or a second lap revisits ground the world has let go of.
Where the next cube has not arrived in the client, the helper holds the player there, as the game
holds a player, and counts the held ticks, so the time a route takes beyond its own is how far the
world fell behind. At the route's end the helper closes the client itself, which writes the
client's recording at exit; the launcher then stops the server. A tour keeps the helper off. A
plan's `remove` names mods to take out of both sides of the instance: an ablation, whose cost is
the difference from a run with them. A plan's `owners` maps package prefixes to owner names, for
local tools the owner table does not name (the motion helper among them).

`check` refuses, before a turn is spent: a missing artifact; a switch without the switches it needs
(`quadrature.gbuffer` needs own terrain, reversed depth and face records; `quadrature.shadow.near`
the light view; `quadrature.scene.states` the scene on the GPU; the join fade distant terrain and
the near fade; TAA a render scale; DLSS its shim, the SDK's runtime and the `quadrature-nvidia`
module); distant terrain without a Riven jar that previews; a flight without a motion helper that
flies routes, with a setting outside the helper's own bounds, longer than its 72,000 ticks, or
without room in its run's limit for itself and the game's start; a tour with two consecutive
views at one position, whose second would wait out the capture timeout;
an instance that exists or a run directory in use; a removal that leaves a mod without one its
`@Mod` dependencies require; less than 30 GiB free; no `jfr` tool. `run` checks again before it
prepares.

## What a run records

Both games run under JFR with the profile settings and `-XX:+DebugNonSafepoints`, which places
each sample at its own instruction rather than the nearest point with debug information.

Sample shares are not CPU shares on every thread. An execution sample is taken only while a thread
runs Java code, so work spent in the VM (allocation slow paths, GC barriers, runtime calls) is
not seen, and a thread that mixes tight Java loops with allocation-heavy work over-reports the
loops. On 2026-11-19, with the flag on, the server thread's samples put Cubature's autosave
discovery at 16.7% (`needsSaving`) and up to 71% in a minute, which would be about 2.9 ms a tick;
its own loop stops at half the 2 ms autosave budget, and a clock measured it at 0.11 ms a tick,
inside an unload-and-save phase of 0.80 ms. So a plan's `timed` methods (`Class::method`, runtime
names, so `func_` names for overrides of the game's methods) are clocked by JFR's method timing,
emitted every 10 s and cumulative, and the report sets each one's time over a window beside the
time its samples imply: their ratio is how far the samples misstate that method there, and each
thread group with a clocked method gets a verdict: its samples stand as CPU shares where every ratio
lies within 0.8 to 1.25, and otherwise its clocks and Cubature's phase times are the measure. Clock
methods called per tick, cube or column, not per block: the timing itself costs a little each call.

Calibrated on the base configuration, 2026-11-19 (generating cave tour and route flight):
Quadrature's meshing pool agrees with its clock (0.89 and 0.99); the generation pool's samples run
0.6 to 0.9 of the clock, which counts wall time and so any wait inside Riven; the server thread's
are off both ways, the delivery tick and the cube install methods under-sampled three to seven
times (0.14 to 0.34) and the unload-and-save tick over-sampled about twice (1.8 and 2.6); the
frame thread's terrain draw, mostly in the driver, has 0.06 of its clocked time in execution
samples (its native samples are counted apart). By clock the server's delivery tick, installs
included, took 3.2 ms a tick on the cave tour and its unload-and-save tick 0.7 ms, which agrees
with Cubature's own phase times.

The launcher kills a tour's client, and an unfinished recorder chunk cannot be read, so once the
client has captured every view the driver asks it with the game's own `jcmd` to dump its
recording; a flight's client closes itself and writes its recording at exit. Recorder chunks of
1 MiB, assembled when no recording was dumped or written, are the fallback. Each side writes a GC
log; `nvidia-smi` samples the GPU every second; crash reports are kept. Logs, recordings and the
helper's frame records go to `RUN_DIR/<label>/`, the instance's mods are indexed into
`RUN_DIR/mod-index.json` and the run's
record, with every artifact's SHA-256, is `run.json`.

## How time is charged

A sample belongs to the owner of the frame nearest the top of its stack. The JDK and general
libraries (fastutil, Guava, ASM, JOML and the like) are transparent: their time goes to whoever
called them. Owners are the mods, by package (read from their jars), and the engine: the game,
Forge as Cleanroom ships it, Cleanroom's own code (its loader and Foundation), Mixin, LWJGL and
Netty. A method Mixin merged into a game class keeps its own name behind Mixin's prefix and is
charged to the mod that name carries, or, failing that, to the mod whose Mixin declares it in the
index; a game method a mod overwrote is charged to that mod. Frames that only wrap the engine's
own work are transparent: Cubature's tick clock and autosave scope, Quadrature's out-of-memory
notice around the client loop, and CodeChickenLib's block dispatcher, which replaces the game's and
passes every block render through (its own renderers are other classes and stay its own).
Otherwise every tick would count as work for Cubature and every block meshed as work for
CodeChickenLib. A stack whose top frames are the frame limiter (`Display.sync`), the driver's
present or a GPU wait is charged to that wait, not to the code that waited.

When the owner is the engine, the nearest mod frame below it is the mod it served, and the pair of
methods is kept: what each mod leaves the engine to do, and on which thread. The engine's own
samples are also summed by system (chunks, world, entities, rendering and the rest, by the class's
package) beside the mods that already patch each system.

A thread's CPU comes from `jdk.ThreadCPULoad`, integrated per thread over the interval before each
reading and capped at one and a half recorder periods, so a pool thread that ended and a new one of
the same name are not bridged; the group's CPU is then split by its samples. Sample counts are
never compared across threads, since the recorder samples a few threads a period. Checked on one
recording: the server thread's CPU agreed with Cubature's own tick clock within 20% (CPU cannot
exceed wall time), and the process's thread groups summed to within 0.2 cores of its CPU, the rest
being the collector's native threads.

Allocation is charged like samples, by the sampled allocations' weights; monitor waits and parks by
their duration; compilations of 100 ms or more (the profile settings' threshold) and
deoptimizations to the owner of their method. GC pauses are totalled.

Windows: `startup` (to the first teleport), `run` (from it), each tour view (from the capture before
it to its own) and each flight phase (settling, stationary, move, dwell, hold, from the helper's
frame records). The server's tick phases come from Cubature's minute lines inside the run, the
first minute left out since it can hold the spawn area's preparation; the stage times are the
median over the run of Quadrature's stage reports. A flight adds, by phase, its frames, those held
for the world, the frame interval and the CPU render time over its eligible frames; the helper's
GPU render-tick times over its eligible samples; and its receipt's outcome and held ticks.

## The report

For each run: the mods ranked by their CPU on the server tick and the frame, each with the engine
time spent for it, its CPU in the worker pools, its allocation, its compile time and its
deoptimizations; each thread group's CPU by owner; the engine calls each mod's samples went to on
the critical threads; the hottest methods there; the engine's CPU by system beside the mods that
patch it; Cleanroom's, Forge's, Mixin's and LWJGL's own CPU; allocation, compilation and GC; startup
CPU by owner; the server's tick phases; the stage, frame and GPU times; and a table of the views or
flight phases. Labels present in more than one run directory are set side by side. The mod survey
closes it.

## Limits

- A sample is not a duration: shares become CPU seconds only through each thread's own CPU, and a
  thread that waits (on the GPU, a lock, the disk) is charged nothing for the wait except in the
  park and monitor tables.
- Native code is seen only through `jdk.NativeMethodSample`, by the Java frame that called it: time
  inside the GPU driver on the client thread is charged to whoever made the GL call.
- An overwritten method is charged to its mod only where the index names it; a mod built without a
  refmap and with development names would be missed.
- Live heap by owner is not measured: old-object samples appear only when a recording is stopped or
  dumped by command, not at exit.
- The GPU is timed per stage only where Quadrature hosts the stage; work other mods draw inside a
  stage counts as that stage's.
- A flight follows its route exactly, in no-clip spectator mode: it does not walk, collide or
  look around as a person would, beyond its dwells and turns. Its route takes longer in wall time
  wherever the world held it; compare runs by their held ticks as well as their frame times.
- The helper's GPU time is its render-tick bracket (the world and the interface, not the blit,
  swap or presentation), not per pass.
- Windows are seconds of the local day, as the logs and recordings stamp them: a run that crosses
  midnight is not windowed correctly.
- JFR's method timing rewrites a class when it loads and skips a class whose rewriting needs a game
  class it cannot resolve from the loader (it logs `Could not resolve class Chunk. Skipping
  instrumentation`): Cubature's `PeriodicAutosave` and `ChunkGc` are not clocked; the provider's
  tick, which calls both, is.
