package cc.orthrus.quadrature.core.stage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads JSON text into plain values: an object as a map in the order written, an array as a list, a
 * string, a number as a double, true and false, and null. Enough for a manifest; it reads and never
 * writes, and refuses text that is not JSON by throwing {@link IllegalArgumentException}.
 */
public final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json reader = new Json(text);
        reader.space();
        Object value = reader.value();
        reader.space();
        if (reader.at != text.length()) {
            throw reader.wrong("something after the end");
        }
        return value;
    }

    private IllegalArgumentException wrong(String what) {
        return new IllegalArgumentException("Not JSON at character " + at + ": " + what);
    }

    private void space() {
        while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
    }

    private Object value() {
        if (at >= text.length()) {
            throw wrong("the text ends");
        }
        char c = text.charAt(at);
        if (c == '{') {
            return object();
        }
        if (c == '[') {
            return array();
        }
        if (c == '"') {
            return string();
        }
        if (text.startsWith("true", at)) {
            at += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", at)) {
            at += 5;
            return Boolean.FALSE;
        }
        if (text.startsWith("null", at)) {
            at += 4;
            return null;
        }
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        if (start == at) {
            throw wrong("no value");
        }
        try {
            return Double.valueOf(text.substring(start, at));
        } catch (NumberFormatException notANumber) {
            throw wrong("no number");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        at++;
        space();
        if (at < text.length() && text.charAt(at) == '}') {
            at++;
            return map;
        }
        while (true) {
            space();
            if (at >= text.length() || text.charAt(at) != '"') {
                throw wrong("a name was expected");
            }
            String name = string();
            space();
            if (at >= text.length() || text.charAt(at) != ':') {
                throw wrong("a colon was expected");
            }
            at++;
            space();
            map.put(name, value());
            space();
            if (at < text.length() && text.charAt(at) == ',') {
                at++;
            } else if (at < text.length() && text.charAt(at) == '}') {
                at++;
                return map;
            } else {
                throw wrong("a comma or the object's end was expected");
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        at++;
        space();
        if (at < text.length() && text.charAt(at) == ']') {
            at++;
            return list;
        }
        while (true) {
            space();
            list.add(value());
            space();
            if (at < text.length() && text.charAt(at) == ',') {
                at++;
            } else if (at < text.length() && text.charAt(at) == ']') {
                at++;
                return list;
            } else {
                throw wrong("a comma or the array's end was expected");
            }
        }
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        at++;
        while (true) {
            if (at >= text.length()) {
                throw wrong("a string does not end");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) {
                throw wrong("a string does not end");
            }
            char escaped = text.charAt(at++);
            switch (escaped) {
                case 'n':
                    out.append('\n');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'u':
                    if (at + 4 > text.length()) {
                        throw wrong("a character's number is cut short");
                    }
                    try {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    } catch (NumberFormatException notHex) {
                        throw wrong("a character's number is not hexadecimal");
                    }
                    at += 4;
                    break;
                default:
                    out.append(escaped);
            }
        }
    }
}
