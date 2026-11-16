package cc.orthrus.quadrature.core.material;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Where the list of kinds is read from: a file of a folder that is named when the game is started, so
 * that a new revision of the list needs no new build, or else the list the build ships.
 *
 * <p>The file is read as a list and nothing more: lines of names. Nothing in it is run.
 */
public final class KindsSource {

    /** The name the list has in the folder, and in what the build ships. */
    public static final String FILE = "material_kinds.properties";

    private final List<String> lines;
    private final String from;
    private final String revision;
    private final String sha256;

    private KindsSource(List<String> lines, String from, String revision, String sha256) {
        this.lines = lines;
        this.from = from;
        this.revision = revision;
        this.sha256 = sha256;
    }

    /**
     * Reads the list.
     *
     * @param folder  the folder named at start, or null where none was
     * @param shipped opens the list the build ships; it may give null where there is none
     * @return the list from the folder's file where the folder is named and the file can be read, else
     *         the shipped list, else an empty list
     */
    public static KindsSource read(Path folder, Supplier<InputStream> shipped) {
        if (folder != null) {
            Path file = folder.resolve(FILE);
            if (Files.isRegularFile(file)) {
                try {
                    return of(Files.readAllBytes(file), "the file " + file);
                } catch (IOException unreadable) {
                    // Falls back to what the build ships.
                }
            }
        }
        try (InputStream in = shipped.get()) {
            if (in != null) {
                return of(in.readAllBytes(), "the list shipped");
            }
        } catch (IOException unreadable) {
            // Falls through to no list.
        }
        return new KindsSource(new ArrayList<>(), "no list", "", "");
    }

    private static KindsSource of(byte[] bytes, String from) throws IOException {
        List<String> lines = new ArrayList<>();
        String revision = "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            lines.add(line);
            if (revision.isEmpty() && line.startsWith("#") && line.toLowerCase(java.util.Locale.ROOT).contains("revision")) {
                revision = line.substring(1).trim();
            }
        }
        StringBuilder hex = new StringBuilder();
        try {
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append(String.format("%02x", b));
            }
        } catch (NoSuchAlgorithmException absent) {
            hex.setLength(0);
        }
        return new KindsSource(lines, from, revision, hex.toString());
    }

    /** The list's lines. */
    public List<String> lines() {
        return lines;
    }

    /** Where the list was read from, in words. */
    public String from() {
        return from;
    }

    /** The first comment line that names a revision, without its mark, or nothing. */
    public String revision() {
        return revision;
    }

    /** The SHA-256 of the bytes read, in hexadecimal, or nothing where there was no list. */
    public String sha256() {
        return sha256;
    }
}
