import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Writes every GLSL stage the mod's classes hold to files, with no game and no GPU, for glslang to compile.
 *
 * <pre>
 * DumpShaders OUTPUT_FOLDER CLASSES_FOLDER...
 * </pre>
 *
 * A stage is a static String field that begins with a version line, in a class whose constants hold one. A file is
 * named by the class and the field, with the stage's extension: {@code .comp} for a stage that declares its work
 * group, {@code .vert} for one that writes {@code gl_Position}, {@code .frag} for the rest. Exits 1 when a class that
 * holds a version line cannot be initialized, or when no stage is found.
 */
public final class DumpShaders {
    private static final String VERSION = "#version";
    private static final byte[] MARK = VERSION.getBytes(StandardCharsets.UTF_8);

    private DumpShaders() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        int stages = 0;
        List<String> failed = new ArrayList<>();
        for (int a = 1; a < args.length; a++) {
            Path root = Path.of(args[a]);
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> classes;
            try (Stream<Path> walk = Files.walk(root)) {
                classes = walk.filter(path -> path.toString().endsWith(".class")).sorted().toList();
            }
            for (Path file : classes) {
                if (!holds(Files.readAllBytes(file), MARK)) {
                    continue;
                }
                String relative = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), ".");
                String name = relative.substring(0, relative.length() - ".class".length());
                Class<?> type;
                try {
                    type = Class.forName(name, true, DumpShaders.class.getClassLoader());
                } catch (Throwable e) {
                    failed.add(name + ": " + e);
                    continue;
                }
                for (Field field : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                        continue;
                    }
                    field.setAccessible(true);
                    String text = (String) field.get(null);
                    if (text == null || !text.startsWith(VERSION)) {
                        continue;
                    }
                    String stage = text.contains("local_size_x") ? "comp" : text.contains("gl_Position") ? "vert" : "frag";
                    Path target = out.resolve(name + "." + field.getName() + "." + stage);
                    Files.writeString(target, text, StandardCharsets.UTF_8);
                    System.out.println(target.getFileName());
                    stages++;
                }
            }
        }
        for (String failure : failed) {
            System.err.println("Could not initialize " + failure);
        }
        if (stages == 0) {
            System.err.println("No GLSL stage found.");
        }
        System.out.println(stages + " stages written to " + out);
        if (!failed.isEmpty() || stages == 0) {
            System.exit(1);
        }
    }

    private static boolean holds(byte[] bytes, byte[] mark) {
        outer:
        for (int i = 0; i + mark.length <= bytes.length; i++) {
            for (int j = 0; j < mark.length; j++) {
                if (bytes[i + j] != mark[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
