package cc.orthrus.quadrature.core.platform;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A graphics driver's version as the context's version string gives it, and its branch: the leading number of
 * the version (D20). NVIDIA's string is the OpenGL version, the vendor's name and the driver's version:
 * {@code "4.6.0 NVIDIA 610.88"} on Windows, {@code "4.6.0 NVIDIA 610.57.04"} on Linux, both of branch 610.
 *
 * @param version the driver's version as written, such as {@code "610.57.04"}
 * @param branch  its leading number
 */
public record DriverVersion(String version, int branch) {

    private static final Pattern VERSION = Pattern.compile("(\\d+)(?:\\.\\d+)+");

    /**
     * The driver's version in a context's version string: the first version after the vendor's name where the
     * name is there, else the last version after the OpenGL version itself; null where there is none.
     */
    public static DriverVersion parse(String versionString, String vendorName) {
        if (versionString == null) {
            return null;
        }
        String text = versionString.trim();
        int glEnd = text.indexOf(' ');
        if (glEnd < 0) {
            // Nothing but the OpenGL version.
            return null;
        }
        int from = glEnd;
        if (vendorName != null && !vendorName.isEmpty()) {
            int at = text.toLowerCase(Locale.ROOT).indexOf(vendorName.toLowerCase(Locale.ROOT), glEnd);
            if (at >= 0) {
                Matcher matcher = VERSION.matcher(text);
                if (matcher.find(at + vendorName.length())) {
                    return of(matcher);
                }
                return null;
            }
        }
        Matcher matcher = VERSION.matcher(text);
        DriverVersion last = null;
        int start = from;
        while (matcher.find(start)) {
            last = of(matcher);
            start = matcher.end();
        }
        return last;
    }

    private static DriverVersion of(Matcher matcher) {
        try {
            return new DriverVersion(matcher.group(), Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException tooLong) {
            return null;
        }
    }
}
