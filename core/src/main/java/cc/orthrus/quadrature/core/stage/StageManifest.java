package cc.orthrus.quadrature.core.stage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a folder handed over for a shading stage says it holds: its programs, where each sampler is
 * bound, and every file with its role, format, shape, size and hash. The stage is loaded from this and
 * nothing else in the folder is looked at.
 *
 * <p>Everything read is data. A file is taken only by a plain name inside the folder, only if its size
 * and its hash are the manifest's, and where its format says how wide an element is, only if its size
 * is its shape's. Program sources are text for the driver's compiler and are never run as anything
 * else.
 */
public final class StageManifest {

    /**
     * The newest version of the manifest that is read. A later one may mean things this does not know
     * of, and is refused; so is version 4, which was never handed over.
     */
    public static final int NEWEST = 5;
    /** The version from which a manifest says what is to be run, as a {@link StagePlan}. */
    public static final int PLANNED_FROM = 5;

    /** The manifest's own name in the folder. */
    public static final String FILE = "manifest.json";

    /** One file of the folder. */
    public static final class Entry {
        public final String file;
        public final String role;
        public final String format;
        /** Rows, columns and channels, as many as the manifest gives; none for text. */
        public final int[] shape;
        public final long bytes;
        public final String sha256;
        /** How many entries a list says it holds, or -1 where the manifest does not say. */
        public final int entries;

        Entry(String file, String role, String format, int[] shape, long bytes, String sha256, int entries) {
            this.file = file;
            this.role = role;
            this.format = format;
            this.shape = shape;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.entries = entries;
        }

        /** The bytes of one number of the file by its format's name, or 0 where the format is text or not known. */
        public int elementBytes() {
            String name = format.toLowerCase(Locale.ROOT);
            if (name.contains("glsl") || name.contains("utf") || name.contains("json") || name.contains("text")
                || name.contains("properties") || name.contains("std140") || name.contains("png")) {
                return 0;
            }
            if (name.contains("32")) {
                return 4;
            }
            if (name.contains("16")) {
                return 2;
            }
            return name.contains("8") ? 1 : 0;
        }

        /** How many numbers the shape holds. */
        public long elements() {
            long count = shape.length == 0 ? 0 : 1;
            for (int side : shape) {
                count *= side;
            }
            return count;
        }
    }

    /** A program: the files of its two stages. */
    public static final class Program {
        public final String vertex;
        public final String fragment;

        Program(String vertex, String fragment) {
            this.vertex = vertex;
            this.fragment = fragment;
        }
    }

    private final Path folder;
    private final int version;
    private final Map<String, Program> programs = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> samplers = new LinkedHashMap<>();
    private final Map<String, Entry> files = new LinkedHashMap<>();
    private final Map<String, Object> tolerance;
    private final String byteOrder;
    private final String rowOrder;
    private final int materialKindsRevision;
    private final StagePlan plan;

    @SuppressWarnings("unchecked")
    private StageManifest(Path folder, Map<String, Object> root) {
        this.folder = folder;
        version = number(root.get("manifest"), "manifest");
        if (version < 1 || version > NEWEST || version == 4) {
            throw new IllegalArgumentException("The manifest is of version " + version + "; versions 1 to 3 and " + NEWEST
                + " are read");
        }
        byteOrder = String.valueOf(root.get("byteOrder"));
        rowOrder = String.valueOf(root.get("rowOrder"));
        Object revision = root.get("materialKindsRevision");
        materialKindsRevision = revision instanceof Double ? ((Double) revision).intValue() : -1;
        tolerance = root.get("tolerance") instanceof Map ? (Map<String, Object>) root.get("tolerance") : new LinkedHashMap<>();
        if (root.get("programs") instanceof Map) {
            for (Map.Entry<String, Object> program : ((Map<String, Object>) root.get("programs")).entrySet()) {
                Map<String, Object> stages = map(program.getValue(), "program " + program.getKey());
                programs.put(program.getKey(), new Program(plain(text(stages.get("vertex"), "vertex")),
                    plain(text(stages.get("fragment"), "fragment"))));
            }
        }
        if (root.get("samplers") instanceof Map) {
            for (Map.Entry<String, Object> program : ((Map<String, Object>) root.get("samplers")).entrySet()) {
                Map<String, Integer> units = new LinkedHashMap<>();
                for (Map.Entry<String, Object> sampler : map(program.getValue(), "samplers of " + program.getKey()).entrySet()) {
                    units.put(sampler.getKey(), number(sampler.getValue(), sampler.getKey()));
                }
                samplers.put(program.getKey(), units);
            }
        }
        if (!(root.get("files") instanceof List)) {
            throw new IllegalArgumentException("The manifest lists no files");
        }
        for (Object item : (List<Object>) root.get("files")) {
            Map<String, Object> entry = map(item, "a file's entry");
            int[] shape = new int[0];
            if (entry.get("shape") instanceof List) {
                List<Object> sides = (List<Object>) entry.get("shape");
                shape = new int[sides.size()];
                for (int i = 0; i < shape.length; i++) {
                    shape[i] = number(sides.get(i), "shape");
                    if (shape[i] < 0 || shape[i] > 1 << 16) {
                        throw new IllegalArgumentException("A shape's side is out of range: " + shape[i]);
                    }
                }
            }
            Object size = entry.get("bytes");
            if (!(size instanceof Double) || (Double) size < 0 || (Double) size != Math.rint((Double) size)) {
                throw new IllegalArgumentException("A file's size is not a count of bytes: " + size);
            }
            String name = plain(text(entry.get("file"), "file"));
            Object count = entry.get("entries");
            files.put(name, new Entry(name, text(entry.get("role"), "role"), text(entry.get("format"), "format"), shape,
                ((Double) size).longValue(), text(entry.get("sha256"), "sha256").toLowerCase(Locale.ROOT),
                count instanceof Double && (Double) count >= 0 ? ((Double) count).intValue() : -1));
        }
        // From the version that says what is to be run, it is read here and found sound or the manifest is refused.
        plan = version >= PLANNED_FROM ? new StagePlan(root, files.keySet(), programs.keySet(), tolerance.keySet()) : null;
    }

    /** What is to be run, or null for a manifest of a version before {@link #PLANNED_FROM}, which does not say. */
    public StagePlan plan() {
        return plan;
    }

    /** A number of one of the tolerances by its key, or the fallback where the manifest does not give it as a number. */
    public double toleranceNumber(String key, String field, double fallback) {
        return tolerance(key, field, fallback);
    }

    /** A yes or no of one of the tolerances by its key, or the fallback where the manifest does not give one. */
    public boolean toleranceFlag(String key, String field, boolean fallback) {
        return tolerance(key, field, fallback);
    }

    /** Whether a tolerance is one of display's kind: by levels of 255, not by a part and a share. */
    public boolean toleranceByLevels(String key) {
        Object group = tolerance.get(key);
        return group instanceof Map && ((Map<?, ?>) group).get("maxCodeValueDifference") instanceof Double;
    }

    /** Reads the manifest of a folder. Throws where there is none or it is not as described. */
    @SuppressWarnings("unchecked")
    public static StageManifest read(Path folder) throws IOException {
        Object root = Json.parse(new String(Files.readAllBytes(folder.resolve(FILE)), StandardCharsets.UTF_8));
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("The manifest is not an object");
        }
        return new StageManifest(folder, (Map<String, Object>) root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String what) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Not an object: " + what);
        }
        return (Map<String, Object>) value;
    }

    private static String text(Object value, String what) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Not text: " + what);
        }
        return (String) value;
    }

    private static int number(Object value, String what) {
        if (!(value instanceof Double) || (Double) value != Math.rint((Double) value)) {
            throw new IllegalArgumentException("Not a whole number: " + what);
        }
        return ((Double) value).intValue();
    }

    /** A file's name must be a name and no more: nothing that could lead out of the folder. */
    private static String plain(String name) {
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..") || name.contains(":")) {
            throw new IllegalArgumentException("Not a plain file name: " + name);
        }
        return name;
    }

    public int version() {
        return version;
    }

    /** The programs by name, each the files of its vertex and fragment stages. */
    public Map<String, Program> programs() {
        return programs;
    }

    /** For a program, the unit each of its samplers is bound to; empty where the manifest gives none. */
    public Map<String, Integer> samplers(String program) {
        return samplers.getOrDefault(program, new LinkedHashMap<>());
    }

    /** Every file by name, in the manifest's order. */
    public Map<String, Entry> files() {
        return files;
    }

    /** The files whose role begins with a word, such as "input:" or "expected:". */
    public List<Entry> withRole(String beginning) {
        List<Entry> found = new ArrayList<>();
        for (Entry entry : files.values()) {
            if (entry.role.startsWith(beginning)) {
                found.add(entry);
            }
        }
        return found;
    }

    /** The tolerances as the manifest states them, for the log. */
    public Map<String, Object> tolerance() {
        return tolerance;
    }

    /** A number of one of the tolerance's parts, or the fallback where the manifest does not give it as a number. */
    private double tolerance(String part, String field, double fallback) {
        Object group = tolerance.get(part);
        Object value = group instanceof Map ? ((Map<?, ?>) group).get(field) : null;
        return value instanceof Double ? (Double) value : fallback;
    }

    private boolean tolerance(String part, String field, boolean fallback) {
        Object group = tolerance.get(part);
        Object value = group instanceof Map ? ((Map<?, ?>) group).get(field) : null;
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    /** What a radiance channel may differ by apart from its share of the expected value. */
    public double radianceAbsolute() {
        return tolerance("radiance", "absolute", StageCompare.RADIANCE_ABSOLUTE);
    }

    /** The share of the expected value a radiance channel may differ by besides. */
    public double radianceRelative() {
        return tolerance("radiance", "relative", StageCompare.RADIANCE_RELATIVE);
    }

    /** Whether radiance's alpha must be exactly the expected. */
    public boolean radianceAlphaExact() {
        return tolerance("radiance", "alphaExact", true);
    }

    /** The levels of 255 a display channel may differ by. */
    public int displayLevels() {
        return (int) tolerance("display", "maxCodeValueDifference", StageCompare.DISPLAY_LEVELS);
    }

    /** Whether display's alpha is compared. Where the manifest does not say, it is. */
    public boolean displayAlphaCompared() {
        return tolerance("display", "alphaCompared", true);
    }

    /** The file of the mask that leaves pixels out of the comparison, or null where the manifest names none. */
    public String maskFile() {
        Object group = tolerance.get("shadowEdge");
        Object value = group instanceof Map ? ((Map<?, ?>) group).get("maskFile") : null;
        if (value instanceof String) {
            return plain((String) value);
        }
        return files.containsKey("shadowEdgeMask.r8") ? "shadowEdgeMask.r8" : null;
    }

    /** The mask's value for a pixel that is compared. */
    public int maskComparedValue() {
        return (int) tolerance("shadowEdge", "comparedValue", 0);
    }

    public String byteOrder() {
        return byteOrder;
    }

    public String rowOrder() {
        return rowOrder;
    }

    /** The revision of the list of kinds the folder holds, or -1 where the manifest names none. */
    public int materialKindsRevision() {
        return materialKindsRevision;
    }

    /**
     * Reads a file the manifest names, whole.
     *
     * @throws IOException where the manifest does not name it, or its size, its hash, or the size its
     *                     shape and format give is not the manifest's
     */
    public byte[] load(String name) throws IOException {
        Entry entry = files.get(name);
        if (entry == null) {
            throw new IOException("The manifest names no file " + name);
        }
        Path file = folder.resolve(entry.file);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Missing: " + entry.file);
        }
        long size = Files.size(file);
        if (size != entry.bytes) {
            throw new IOException(entry.file + " is " + size + " bytes; the manifest says " + entry.bytes);
        }
        int element = entry.elementBytes();
        if (element > 0 && entry.elements() * element != entry.bytes) {
            throw new IOException(entry.file + " is " + entry.bytes + " bytes; its shape and format give "
                + entry.elements() * element);
        }
        byte[] bytes = Files.readAllBytes(file);
        String found = sha256(bytes);
        if (!found.equals(entry.sha256)) {
            throw new IOException(entry.file + " has sha256 " + found + "; the manifest says " + entry.sha256);
        }
        return bytes;
    }

    /** The SHA-256 of some bytes, in hexadecimal. */
    public static String sha256(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        try {
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append(String.format("%02x", b));
            }
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
        return hex.toString();
    }
}
