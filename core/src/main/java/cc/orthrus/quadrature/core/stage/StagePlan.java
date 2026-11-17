package cc.orthrus.quadrature.core.stage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a stage's manifest says is to be run: the textures the host gives, the assets and targets the
 * stage brings, its passes in order with where each sampler reads from and where each output goes, and
 * the checks that set an output beside a file. Read from a manifest and found sound before anything
 * is made for it: every name a pass uses is one the manifest gives, and everything the host would have
 * to do that it does not do is refused by name.
 *
 * <p>Passes run every frame in the order written; diagnostic passes have the same shape and run only
 * where asked for. A pass's viewport is the size of its first output. A source is one of
 * {@code host:} a texture of the host's, {@code asset:} one the folder brings, {@code target:} a
 * target as written earlier in the frame, and {@code history:} a persistent target as the frame
 * before left it.
 */
public final class StagePlan {

    /** The output that is the host's own presentation target and no target of the stage. */
    public static final String DISPLAY = "display";
    /** The one uniform block the host fills. */
    public static final String FRAME_BLOCK = "QFrame";
    /** Units a sampler may be bound to. */
    public static final int UNITS = 16;
    /** Outputs a pass may have. */
    public static final int OUTPUTS = 8;

    /** The formats a target may have. */
    private static final Set<String> TARGET_FORMATS = new HashSet<>(Arrays.asList("RGBA8", "RGBA16F", "RGBA32F", "R8", "RG8",
        "R16F", "RG16F", "R32F", "R11F_G11F_B10F"));

    public enum Filter { NEAREST, LINEAR }

    public enum Wrap { CLAMP_TO_EDGE, REPEAT }

    public enum Comparison { NONE, LESS_OR_EQUAL }

    public enum Source { HOST, ASSET, TARGET, HISTORY }

    /** What a blend multiplies a color by. */
    public enum Factor { ZERO, ONE, SOURCE_ALPHA, ONE_MINUS_SOURCE_ALPHA, DESTINATION_ALPHA, ONE_MINUS_DESTINATION_ALPHA }

    /**
     * How a pass's outputs are added to what their targets hold: what is written times a source factor
     * plus what is held times a destination factor, for color and for alpha apart. It applies to
     * every output of the pass.
     */
    public static final class Blend {
        public final Factor colorSource;
        public final Factor colorDestination;
        public final Factor alphaSource;
        public final Factor alphaDestination;

        Blend(Factor colorSource, Factor colorDestination, Factor alphaSource, Factor alphaDestination) {
            this.colorSource = colorSource;
            this.colorDestination = colorDestination;
            this.alphaSource = alphaSource;
            this.alphaDestination = alphaDestination;
        }
    }

    /** How a texture is read. */
    public static final class Sampler {
        public final Filter filter;
        public final Wrap wrap;
        public final Comparison comparison;

        Sampler(Filter filter, Wrap wrap, Comparison comparison) {
            this.filter = filter;
            this.wrap = wrap;
            this.comparison = comparison;
        }

        /** The same for two samplers that read alike, so that one object serves both. */
        public int key() {
            return filter.ordinal() | wrap.ordinal() << 1 | comparison.ordinal() << 2;
        }
    }

    /** A texture the host gives. */
    public static final class HostInput {
        public final String name;
        /** The file that stands for it where the stage is checked. */
        public final String file;
        public final String format;
        /** What the window's sides are divided by, or 0 where the size is the host's own. */
        public final int sizeDivisor;
        public final Sampler sampler;

        HostInput(String name, String file, String format, int sizeDivisor, Sampler sampler) {
            this.name = name;
            this.file = file;
            this.format = format;
            this.sizeDivisor = sizeDivisor;
            this.sampler = sampler;
        }
    }

    /** A texture the folder brings, made once. */
    public static final class Asset {
        public final String name;
        public final String file;
        /** 2 or 3 dimensions. */
        public final int dimensions;
        public final String format;
        /** Width, height, and depth where it has three dimensions. */
        public final int[] size;
        public final Sampler sampler;
        public final boolean mipmaps;

        Asset(String name, String file, int dimensions, String format, int[] size, Sampler sampler, boolean mipmaps) {
            this.name = name;
            this.file = file;
            this.dimensions = dimensions;
            this.format = format;
            this.size = size;
            this.sampler = sampler;
            this.mipmaps = mipmaps;
        }
    }

    /** A texture a pass writes. */
    public static final class Target {
        public final String name;
        public final String format;
        public final int sizeDivisor;
        /** Kept from frame to frame: the host holds two and swaps them each frame. */
        public final boolean persistent;
        /** Set to zeros on a frame that drops history; otherwise never cleared. */
        public final boolean zeroOnReset;
        public final Sampler sampler;

        Target(String name, String format, int sizeDivisor, boolean persistent, boolean zeroOnReset, Sampler sampler) {
            this.name = name;
            this.format = format;
            this.sizeDivisor = sizeDivisor;
            this.persistent = persistent;
            this.zeroOnReset = zeroOnReset;
            this.sampler = sampler;
        }
    }

    /** Where one sampler of a pass reads from. */
    public static final class Input {
        public final String sampler;
        public final int unit;
        public final Source source;
        public final String name;

        Input(String sampler, int unit, Source source, String name) {
            this.sampler = sampler;
            this.unit = unit;
            this.source = source;
            this.name = name;
        }
    }

    /** Where one output of a pass goes: a target's name, or {@link #DISPLAY}. */
    public static final class Output {
        public final int location;
        public final String target;

        Output(int location, String target) {
            this.location = location;
            this.target = target;
        }
    }

    /** One draw of a program over its outputs. */
    public static final class Pass {
        public final String name;
        public final String program;
        public final String vertex;
        public final String fragment;
        /** For each uniform block, the binding it is read from. */
        public final Map<String, Integer> uniformBlocks;
        public final List<Input> inputs;
        public final List<Output> outputs;
        public final int vertices;
        public final boolean blending;
        public final boolean depthTest;
        public final boolean depthWrite;
        public final boolean clear;
        public final boolean diagnostic;
        /** How the pass blends, or null where it does not. */
        public final Blend blend;
        /** The group the pass belongs to, which the host may leave out whole, or null. */
        public final String group;

        Pass(String name, String program, String vertex, String fragment, Map<String, Integer> uniformBlocks, List<Input> inputs,
            List<Output> outputs, int vertices, boolean blending, boolean depthTest, boolean depthWrite, boolean clear,
            boolean diagnostic, Blend blend, String group) {
            this.name = name;
            this.program = program;
            this.vertex = vertex;
            this.fragment = fragment;
            this.uniformBlocks = uniformBlocks;
            this.inputs = inputs;
            this.outputs = outputs;
            this.vertices = vertices;
            this.blending = blending;
            this.depthTest = depthTest;
            this.depthWrite = depthWrite;
            this.clear = clear;
            this.diagnostic = diagnostic;
            this.blend = blend;
            this.group = group;
        }

        /** Whether the pass writes the host's presentation target. */
        public boolean writesDisplay() {
            for (Output output : outputs) {
                if (output.target.equals(DISPLAY)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** One output set beside a file. */
    public static final class Check {
        public final String pass;
        public final int output;
        public final String expected;
        /** The file of the mask, or null to compare every pixel. */
        public final String mask;
        /** The key of the manifest's tolerance that the comparison goes by. */
        public final String tolerance;
        /** Which of the check's frames the output is read after, counted from 0. */
        public final int frame;

        Check(String pass, int output, String expected, String mask, String tolerance, int frame) {
            this.pass = pass;
            this.output = output;
            this.expected = expected;
            this.mask = mask;
            this.tolerance = tolerance;
            this.frame = frame;
        }
    }

    private final Map<String, HostInput> hostInputs = new LinkedHashMap<>();
    private final Map<String, Asset> assets = new LinkedHashMap<>();
    private final Map<String, Target> targets = new LinkedHashMap<>();
    private final List<Pass> passes = new ArrayList<>();
    private final List<Pass> diagnosticPasses = new ArrayList<>();
    private final List<Check> checks = new ArrayList<>();
    private final List<String> frames = new ArrayList<>();
    private final int frameBlockBytes;
    private final String frameBlockFile;
    private final double teleportResetBlocks;

    /**
     * @param root     the manifest as read
     * @param files    the names of the files the manifest lists
     * @param programs the names of the programs it lists
     * @param tolerances the keys of its tolerances
     */
    StagePlan(Map<String, Object> root, Set<String> files, Set<String> programs, Set<String> tolerances) {
        frameBlockBytes = whole(root.get("frameBlockBytes"), "frameBlockBytes");
        if (frameBlockBytes != FrameBlock.BYTES) {
            throw new IllegalArgumentException("The manifest's frame block is " + frameBlockBytes + " bytes; the host writes "
                + FrameBlock.BYTES);
        }
        frameBlockFile = root.get("frameBlockFile") instanceof String ? (String) root.get("frameBlockFile") : null;
        if (frameBlockFile != null && !files.contains(frameBlockFile)) {
            throw new IllegalArgumentException("The frame block's file is not listed: " + frameBlockFile);
        }
        // The frames a check runs, each a file of the frame's block: a list of them, or the one file.
        if (root.get("frames") != null) {
            for (Object item : list(root.get("frames"), "frames")) {
                String file = text(item, "a frame's file");
                if (!files.contains(file)) {
                    throw new IllegalArgumentException("A frame's file is not listed: " + file);
                }
                frames.add(file);
            }
            if (frames.isEmpty()) {
                throw new IllegalArgumentException("The manifest's list of frames is empty");
            }
        } else if (frameBlockFile != null) {
            frames.add(frameBlockFile);
        }
        Object teleport = root.get("teleportResetBlocks");
        if (!(teleport instanceof Double) || (Double) teleport <= 0) {
            throw new IllegalArgumentException("teleportResetBlocks is not a distance: " + teleport);
        }
        teleportResetBlocks = (Double) teleport;
        if (root.get("shaderStorageBindings") instanceof List && !((List<?>) root.get("shaderStorageBindings")).isEmpty()) {
            throw new IllegalArgumentException("The manifest asks for shader storage bindings; the host gives none");
        }
        Map<String, Integer> blocks = bindings(root.get("uniformBlocks"), "uniformBlocks");

        for (Object item : list(root.get("hostInputs"), "hostInputs")) {
            Map<String, Object> entry = map(item, "a host input");
            String name = text(entry.get("name"), "a host input's name");
            String file = text(entry.get("file"), "the file of host input " + name);
            if (!files.contains(file)) {
                throw new IllegalArgumentException("Host input " + name + " names a file that is not listed: " + file);
            }
            int divisor = entry.containsKey("sizeDivisor") ? divisor(entry.get("sizeDivisor"), name) : 0;
            unique(hostInputs.put(name, new HostInput(name, file, text(entry.get("format"), "the format of " + name), divisor,
                sampler(entry.get("sampler"), name))), "host input", name);
        }
        for (Object item : list(root.get("assets"), "assets")) {
            Map<String, Object> entry = map(item, "an asset");
            String name = text(entry.get("name"), "an asset's name");
            String file = text(entry.get("file"), "the file of asset " + name);
            if (!files.contains(file)) {
                throw new IllegalArgumentException("Asset " + name + " names a file that is not listed: " + file);
            }
            String kind = text(entry.get("kind"), "the kind of asset " + name);
            if (!kind.equals("2D") && !kind.equals("3D")) {
                throw new IllegalArgumentException("Asset " + name + " is of a kind that is not made: " + kind);
            }
            int dimensions = kind.equals("2D") ? 2 : 3;
            List<Object> sides = list(entry.get("size"), "the size of asset " + name);
            if (sides.size() != dimensions) {
                throw new IllegalArgumentException("Asset " + name + " is " + kind + " and has " + sides.size() + " sides");
            }
            int[] size = new int[dimensions];
            for (int i = 0; i < dimensions; i++) {
                size[i] = whole(sides.get(i), "a side of asset " + name);
                if (size[i] < 1 || size[i] > 1 << 14) {
                    throw new IllegalArgumentException("A side of asset " + name + " is out of range: " + size[i]);
                }
            }
            String format = text(entry.get("format"), "the format of asset " + name);
            if (!format.equals("RGBA8")) {
                throw new IllegalArgumentException("Asset " + name + " has a format that is not made: " + format);
            }
            boolean mipmaps = Boolean.TRUE.equals(entry.get("mipmaps"));
            unique(assets.put(name, new Asset(name, file, dimensions, format, size, sampler(entry.get("sampler"), name), mipmaps)),
                "asset", name);
        }
        for (Object item : list(root.get("targets"), "targets")) {
            Map<String, Object> entry = map(item, "a target");
            String name = text(entry.get("name"), "a target's name");
            if (name.equals(DISPLAY)) {
                throw new IllegalArgumentException("A target may not be named " + DISPLAY + ": that is the host's own");
            }
            String format = text(entry.get("format"), "the format of target " + name);
            if (!TARGET_FORMATS.contains(format)) {
                throw new IllegalArgumentException("Target " + name + " has a format that is not made: " + format);
            }
            String clear = entry.get("clear") instanceof String ? (String) entry.get("clear") : "none";
            if (!clear.equals("none") && !clear.equals("zero on reset")) {
                throw new IllegalArgumentException("Target " + name + " is cleared in a way that is not known: " + clear);
            }
            unique(targets.put(name, new Target(name, format, divisor(entry.get("sizeDivisor"), name),
                Boolean.TRUE.equals(entry.get("persistent")), clear.equals("zero on reset"), sampler(entry.get("sampler"), name))),
                "target", name);
        }

        Map<String, Object> defaults = root.get("passDefaults") instanceof Map ? map(root.get("passDefaults"), "passDefaults")
            : new LinkedHashMap<>();
        Set<String> written = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Object item : list(root.get("passes"), "passes")) {
            passes.add(pass(map(item, "a pass"), defaults, blocks, files, programs, written, names, false));
        }
        if (root.get("diagnosticPasses") != null) {
            for (Object item : list(root.get("diagnosticPasses"), "diagnosticPasses")) {
                // A diagnostic pass may read what any pass of the frame wrote, and what it writes no pass reads.
                diagnosticPasses.add(pass(map(item, "a diagnostic pass"), defaults, blocks, files, programs, new HashSet<>(written),
                    names, true));
            }
        }
        if (root.get("checks") != null) {
            for (Object item : list(root.get("checks"), "checks")) {
                Map<String, Object> entry = map(item, "a check");
                String pass = text(entry.get("pass"), "a check's pass");
                int output = whole(entry.get("output"), "the output of the check of " + pass);
                Pass checked = pass(pass);
                if (checked == null) {
                    throw new IllegalArgumentException("A check names a pass that is not there: " + pass);
                }
                if (target(checked, output) == null) {
                    throw new IllegalArgumentException("The check of " + pass + " names an output it does not have: " + output);
                }
                String expected = text(entry.get("expected"), "what the check of " + pass + " expects");
                String mask = entry.get("mask") instanceof String ? (String) entry.get("mask") : null;
                String tolerance = text(entry.get("tolerance"), "the tolerance of the check of " + pass);
                if (!files.contains(expected) || mask != null && !files.contains(mask)) {
                    throw new IllegalArgumentException("The check of " + pass + " names a file that is not listed");
                }
                if (!tolerances.contains(tolerance)) {
                    throw new IllegalArgumentException("The check of " + pass + " names a tolerance that is not stated: " + tolerance);
                }
                int frame = entry.containsKey("frame") ? whole(entry.get("frame"), "the frame of the check of " + pass) : 0;
                if (frame < 0 || frame >= Math.max(1, frames.size())) {
                    throw new IllegalArgumentException("The check of " + pass + " names a frame that is not there: " + frame);
                }
                checks.add(new Check(pass, output, expected, mask, tolerance, frame));
            }
        }
    }

    private Pass pass(Map<String, Object> entry, Map<String, Object> defaults, Map<String, Integer> manifestBlocks,
        Set<String> files, Set<String> programs, Set<String> written, Set<String> names, boolean diagnostic) {
        String name = text(entry.get("name"), "a pass's name");
        if (!names.add(name)) {
            throw new IllegalArgumentException("Two passes are named " + name);
        }
        String program = text(entry.get("program"), "the program of pass " + name);
        if (!programs.contains(program)) {
            throw new IllegalArgumentException("Pass " + name + " names a program that is not listed: " + program);
        }
        String vertex = text(entry.get("vertex"), "the vertex stage of pass " + name);
        String fragment = text(entry.get("fragment"), "the fragment stage of pass " + name);
        if (!files.contains(vertex) || !files.contains(fragment)) {
            throw new IllegalArgumentException("Pass " + name + " names a source that is not listed");
        }
        Map<String, Integer> blocks = entry.containsKey("uniformBlocks") ? bindings(entry.get("uniformBlocks"),
            "the uniform blocks of pass " + name) : manifestBlocks;
        for (String block : blocks.keySet()) {
            if (!block.equals(FRAME_BLOCK)) {
                throw new IllegalArgumentException("Pass " + name + " reads a uniform block the host does not fill: " + block);
            }
        }
        if (flag(entry, defaults, "vertexBuffers", false)) {
            throw new IllegalArgumentException("Pass " + name + " asks for vertex buffers; the host gives none");
        }
        int vertices = entry.containsKey("vertices") ? whole(entry.get("vertices"), "vertices") : defaults.containsKey("vertices")
            ? whole(defaults.get("vertices"), "vertices") : 3;
        if (vertices < 1 || vertices > 6) {
            throw new IllegalArgumentException("Pass " + name + " draws a count of vertices that is not drawn: " + vertices);
        }
        if (flag(entry, defaults, "depthTest", false) || flag(entry, defaults, "depthWrite", false)) {
            throw new IllegalArgumentException("Pass " + name + " asks for depth; a pass has none of its own");
        }

        List<Output> outputs = new ArrayList<>();
        Set<Integer> locations = new HashSet<>();
        Set<String> writes = new HashSet<>();
        int divisor = -1;
        for (Object item : list(entry.get("outputs"), "the outputs of pass " + name)) {
            Map<String, Object> output = map(item, "an output of pass " + name);
            int location = whole(output.get("location"), "an output's location in pass " + name);
            String target = text(output.get("target"), "an output's target in pass " + name);
            if (location < 0 || location >= OUTPUTS || !locations.add(location)) {
                throw new IllegalArgumentException("Pass " + name + " has an output at a location that cannot be: " + location);
            }
            int own;
            if (target.equals(DISPLAY)) {
                own = 1;
            } else if (targets.containsKey(target)) {
                own = targets.get(target).sizeDivisor;
            } else {
                throw new IllegalArgumentException("Pass " + name + " writes a target that is not there: " + target);
            }
            if (divisor >= 0 && own != divisor) {
                throw new IllegalArgumentException("The outputs of pass " + name + " are not of one size");
            }
            divisor = own;
            if (!writes.add(target)) {
                throw new IllegalArgumentException("Pass " + name + " writes " + target + " twice");
            }
            outputs.add(new Output(location, target));
        }
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("Pass " + name + " has no output");
        }
        if (writes.contains(DISPLAY) && outputs.size() > 1) {
            throw new IllegalArgumentException("Pass " + name + " writes the display and something besides");
        }

        List<Input> inputs = new ArrayList<>();
        Set<Integer> units = new HashSet<>();
        for (Object item : list(entry.get("inputs"), "the inputs of pass " + name)) {
            Map<String, Object> input = map(item, "an input of pass " + name);
            String sampler = text(input.get("sampler"), "an input's sampler in pass " + name);
            int unit = whole(input.get("unit"), "the unit of " + sampler);
            if (unit < 0 || unit >= UNITS || !units.add(unit)) {
                throw new IllegalArgumentException("In pass " + name + ", " + sampler + " is on a unit that cannot be: " + unit);
            }
            String from = text(input.get("source"), "the source of " + sampler);
            int colon = from.indexOf(':');
            String kind = colon < 0 ? "" : from.substring(0, colon);
            String what = from.substring(colon + 1);
            Source source;
            switch (kind) {
                case "host":
                    source = Source.HOST;
                    require(hostInputs.containsKey(what), name, sampler, from);
                    break;
                case "asset":
                    source = Source.ASSET;
                    require(assets.containsKey(what), name, sampler, from);
                    break;
                case "target":
                    source = Source.TARGET;
                    require(targets.containsKey(what), name, sampler, from);
                    if (!written.contains(what)) {
                        throw new IllegalArgumentException("In pass " + name + ", " + sampler + " reads " + what
                            + " before any pass of the frame has written it");
                    }
                    if (writes.contains(what)) {
                        throw new IllegalArgumentException("Pass " + name + " reads " + what + " and writes it");
                    }
                    break;
                case "history":
                    source = Source.HISTORY;
                    require(targets.containsKey(what) && targets.get(what).persistent, name, sampler, from);
                    break;
                default:
                    throw new IllegalArgumentException("In pass " + name + ", " + sampler + " has a source of a kind not known: "
                        + from);
            }
            inputs.add(new Input(sampler, unit, source, what));
        }
        written.addAll(writes);
        Blend blend = entry.get("blend") == null ? null : blend(entry.get("blend"), name);
        if (blend == null && flag(entry, defaults, "blending", false)) {
            throw new IllegalArgumentException("Pass " + name + " asks for blending and does not say how");
        }
        String group = entry.get("group") == null ? null : text(entry.get("group"), "the group of pass " + name);
        return new Pass(name, program, vertex, fragment, blocks, inputs, outputs, vertices, blend != null, false, false,
            flag(entry, defaults, "clear", false), diagnostic, blend, group);
    }

    private static Blend blend(Object value, String pass) {
        Map<String, Object> entry = map(value, "the blend of pass " + pass);
        String equation = text(entry.get("equation"), "the blend's equation in pass " + pass);
        if (!equation.equals("add")) {
            throw new IllegalArgumentException("Pass " + pass + " blends by an equation that is not made: " + equation);
        }
        return new Blend(factor(entry.get("colorSource"), pass), factor(entry.get("colorDestination"), pass),
            factor(entry.get("alphaSource"), pass), factor(entry.get("alphaDestination"), pass));
    }

    private static Factor factor(Object value, String pass) {
        String name = text(value, "a blend factor in pass " + pass);
        switch (name) {
            case "zero":
                return Factor.ZERO;
            case "one":
                return Factor.ONE;
            case "sourceAlpha":
                return Factor.SOURCE_ALPHA;
            case "oneMinusSourceAlpha":
                return Factor.ONE_MINUS_SOURCE_ALPHA;
            case "destinationAlpha":
                return Factor.DESTINATION_ALPHA;
            case "oneMinusDestinationAlpha":
                return Factor.ONE_MINUS_DESTINATION_ALPHA;
            default:
                throw new IllegalArgumentException("Pass " + pass + " blends by a factor that is not made: " + name);
        }
    }

    private static void require(boolean there, String pass, String sampler, String source) {
        if (!there) {
            throw new IllegalArgumentException("In pass " + pass + ", " + sampler + " reads what is not there: " + source);
        }
    }

    private static boolean flag(Map<String, Object> entry, Map<String, Object> defaults, String field, boolean fallback) {
        Object value = entry.containsKey(field) ? entry.get(field) : defaults.get(field);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static void unique(Object before, String what, String name) {
        if (before != null) {
            throw new IllegalArgumentException("Two of a kind are named alike: " + what + " " + name);
        }
    }

    private static Sampler sampler(Object value, String of) {
        Map<String, Object> entry = map(value, "the sampler of " + of);
        String filter = text(entry.get("filter"), "the filter of " + of);
        String wrap = text(entry.get("wrap"), "the wrap of " + of);
        String comparison = text(entry.get("comparison"), "the comparison of " + of);
        if (!filter.equals("nearest") && !filter.equals("linear")) {
            throw new IllegalArgumentException("A filter that is not known, of " + of + ": " + filter);
        }
        if (!wrap.equals("clamp to edge") && !wrap.equals("repeat")) {
            throw new IllegalArgumentException("A wrap that is not known, of " + of + ": " + wrap);
        }
        if (!comparison.equals("none") && !comparison.equals("less or equal")) {
            throw new IllegalArgumentException("A comparison that is not known, of " + of + ": " + comparison);
        }
        return new Sampler(filter.equals("nearest") ? Filter.NEAREST : Filter.LINEAR,
            wrap.equals("repeat") ? Wrap.REPEAT : Wrap.CLAMP_TO_EDGE,
            comparison.equals("none") ? Comparison.NONE : Comparison.LESS_OR_EQUAL);
    }

    private static int divisor(Object value, String of) {
        int divisor = whole(value, "the size divisor of " + of);
        if (divisor < 1 || divisor > 64) {
            throw new IllegalArgumentException("The size divisor of " + of + " is out of range: " + divisor);
        }
        return divisor;
    }

    private static Map<String, Integer> bindings(Object value, String what) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (value == null) {
            return out;
        }
        for (Map.Entry<String, Object> block : map(value, what).entrySet()) {
            int binding = whole(block.getValue(), "the binding of " + block.getKey());
            if (binding < 0 || binding >= 16) {
                throw new IllegalArgumentException("The binding of " + block.getKey() + " is out of range: " + binding);
            }
            out.put(block.getKey(), binding);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String what) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Not an object: " + what);
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value, String what) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("Not a list: " + what);
        }
        return (List<Object>) value;
    }

    private static String text(Object value, String what) {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException("Not text: " + what);
        }
        return (String) value;
    }

    private static int whole(Object value, String what) {
        if (!(value instanceof Double) || (Double) value != Math.rint((Double) value)) {
            throw new IllegalArgumentException("Not a whole number: " + what);
        }
        return ((Double) value).intValue();
    }

    /** A side of a target with a divisor, for a window's side: the rule the manifest states, rounded up. */
    public static int side(int window, int divisor) {
        return (window + divisor - 1) / divisor;
    }

    public Map<String, HostInput> hostInputs() {
        return hostInputs;
    }

    public Map<String, Asset> assets() {
        return assets;
    }

    public Map<String, Target> targets() {
        return targets;
    }

    /** The passes of every frame, in order. */
    public List<Pass> passes() {
        return passes;
    }

    /** The passes run only where asked for. */
    public List<Pass> diagnosticPasses() {
        return diagnosticPasses;
    }

    public List<Check> checks() {
        return checks;
    }

    /** The files of the frames a check runs, in order: one where the manifest names a single frame's block, none where it names none. */
    public List<String> frames() {
        return frames;
    }

    /** The groups the passes of every frame belong to, each once, in the order first met. */
    public List<String> groups() {
        List<String> groups = new ArrayList<>();
        for (Pass pass : passes) {
            if (pass.group != null && !groups.contains(pass.group)) {
                groups.add(pass.group);
            }
        }
        return groups;
    }

    /** A pass of either list by name, or null. */
    public Pass pass(String name) {
        for (Pass pass : passes) {
            if (pass.name.equals(name)) {
                return pass;
            }
        }
        for (Pass pass : diagnosticPasses) {
            if (pass.name.equals(name)) {
                return pass;
            }
        }
        return null;
    }

    /** The target a pass writes at a location, or null where it has no such output. */
    public static String target(Pass pass, int location) {
        for (Output output : pass.outputs) {
            if (output.location == location) {
                return output.target;
            }
        }
        return null;
    }

    /** What the window's sides are divided by for a pass's outputs, which are all of one size. */
    public int divisor(Pass pass) {
        String target = pass.outputs.get(0).target;
        return target.equals(DISPLAY) ? 1 : targets.get(target).sizeDivisor;
    }

    public int frameBlockBytes() {
        return frameBlockBytes;
    }

    /** The file that stands for the frame's block where the stage is checked, or null. */
    public String frameBlockFile() {
        return frameBlockFile;
    }

    /** How far the eye may move in a frame, in blocks, before what was kept is dropped. */
    public double teleportResetBlocks() {
        return teleportResetBlocks;
    }

    /** The bytes the stage's targets take at a window's size, a persistent one counted twice. */
    public long targetBytes(int width, int height) {
        long bytes = 0;
        for (Target target : targets.values()) {
            long pixels = (long) side(width, target.sizeDivisor) * side(height, target.sizeDivisor);
            bytes += pixels * pixelBytes(target.format) * (target.persistent ? 2 : 1);
        }
        return bytes;
    }

    /** The bytes a pixel of a format takes. */
    public static int pixelBytes(String format) {
        switch (format) {
            case "R8":
                return 1;
            case "RG8":
            case "R16F":
                return 2;
            case "RGBA8":
            case "RG16F":
            case "R32F":
            case "R11F_G11F_B10F":
                return 4;
            case "RGBA16F":
                return 8;
            case "RGBA32F":
                return 16;
            default:
                throw new IllegalArgumentException("A format that is not made: " + format);
        }
    }
}
