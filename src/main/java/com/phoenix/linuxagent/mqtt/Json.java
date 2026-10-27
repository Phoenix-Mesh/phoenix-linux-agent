package com.phoenix.linuxagent.mqtt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The little JSON the MQTT protocol needs, without a library.
 *
 * <p>{@link #parseObject} reads any JSON object into maps, lists, strings,
 * {@code Long}s (integers), {@code Double}s, booleans and {@code null}.
 * {@link #write} writes the same kinds of values back. Agents receive small,
 * flat messages from Phoenix Core, so this stays deliberately small; the
 * accessors ({@link #string}, {@link #number}) never throw on a missing or
 * mistyped field.
 */
public final class Json {

    /** Nesting deeper than this is refused; protocol messages are flat. */
    private static final int MAX_DEPTH = 32;

    private Json() {
    }

    // ------------------------------------------------------------------
    // reading
    // ------------------------------------------------------------------

    /**
     * Parses a JSON object.
     *
     * @throws IllegalArgumentException if {@code text} is not a JSON object
     */
    public static Map<String, Object> parseObject(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("unexpected text after the value");
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("not a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    /** The string field {@code key}, or {@code null} if it is missing or not a string. */
    public static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof String ? (String) value : null;
    }

    /** The string field {@code key}, or {@code fallback}. */
    public static String string(Map<String, Object> object, String key, String fallback) {
        String value = string(object, key);
        return value == null ? fallback : value;
    }

    /**
     * The integral number field {@code key}, or {@code fallback} if it is
     * missing, not a number, or not a whole number.
     */
    public static long number(Map<String, Object> object, String key, long fallback) {
        Object value = object.get(key);
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Double) {
            double d = (Double) value;
            if (d == Math.rint(d) && Math.abs(d) < 9.0e15) {
                return (long) d;
            }
        }
        return fallback;
    }

    private static final class Parser {

        private final String text;
        private int at;

        Parser(String text) {
            if (text == null) {
                throw new IllegalArgumentException("no JSON text");
            }
            this.text = text;
        }

        boolean atEnd() {
            return at >= text.length();
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Malformed JSON at offset " + at + ": " + message);
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = text.charAt(at);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    at++;
                } else {
                    return;
                }
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nested too deeply");
            }
            if (atEnd()) {
                throw error("unexpected end");
            }
            char c = text.charAt(at);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> object = new LinkedHashMap<>();
            at++; // {
            skipWhitespace();
            if (peek('}')) {
                at++;
                return object;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw error("expected a field name");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                object.put(key, value(depth + 1));
                skipWhitespace();
                if (peek(',')) {
                    at++;
                    continue;
                }
                expect('}');
                return object;
            }
        }

        private List<Object> array(int depth) {
            List<Object> array = new ArrayList<>();
            at++; // [
            skipWhitespace();
            if (peek(']')) {
                at++;
                return array;
            }
            while (true) {
                skipWhitespace();
                array.add(value(depth + 1));
                skipWhitespace();
                if (peek(',')) {
                    at++;
                    continue;
                }
                expect(']');
                return array;
            }
        }

        private String string() {
            at++; // opening quote
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error("control character in a string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("unterminated escape");
                }
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"':
                    case '\\':
                    case '/':
                        out.append(escape);
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (at + 4 > text.length()) {
                            throw error("short \\u escape");
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("bad \\u escape");
                        }
                        at += 4;
                        break;
                    default:
                        throw error("unknown escape \\" + escape);
                }
            }
        }

        private Object number() {
            int start = at;
            if (peek('-')) {
                at++;
            }
            boolean integral = true;
            digits();
            if (peek('.')) {
                integral = false;
                at++;
                digits();
            }
            if (peek('e') || peek('E')) {
                integral = false;
                at++;
                if (peek('+') || peek('-')) {
                    at++;
                }
                digits();
            }
            String literal = text.substring(start, at);
            try {
                if (integral) {
                    try {
                        return Long.parseLong(literal);
                    } catch (NumberFormatException tooLarge) {
                        return Double.parseDouble(literal);
                    }
                }
                return Double.parseDouble(literal);
            } catch (NumberFormatException e) {
                throw error("bad number " + literal);
            }
        }

        private void digits() {
            int start = at;
            while (!atEnd() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
                at++;
            }
            if (at == start) {
                throw error("expected a digit");
            }
        }

        private void literal(String word) {
            if (!text.startsWith(word, at)) {
                throw error("expected " + word);
            }
            at += word.length();
        }

        private boolean peek(char c) {
            return !atEnd() && text.charAt(at) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
            at++;
        }
    }

    // ------------------------------------------------------------------
    // writing
    // ------------------------------------------------------------------

    /** Starts an object whose fields keep their insertion order. */
    public static Builder object() {
        return new Builder();
    }

    /** Builds one JSON object. */
    public static final class Builder {

        private final Map<String, Object> fields = new LinkedHashMap<>();

        /** Adds a field; a {@code null} value is written as JSON {@code null}. */
        public Builder put(String key, Object value) {
            fields.put(key, value);
            return this;
        }

        /** The fields so far (read-only view). */
        public Map<String, Object> fields() {
            return Collections.unmodifiableMap(fields);
        }

        @Override
        public String toString() {
            return write(fields);
        }
    }

    /**
     * Writes a value: maps, iterables, strings, numbers, booleans, null.
     *
     * @throws IllegalArgumentException for other types and non-finite numbers
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            quote(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("JSON has no " + d);
            }
            out.append(d);
        } else if (value instanceof Number) {
            out.append(((Number) value).longValue());
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, item);
            }
            out.append(']');
        } else if (value instanceof Builder) {
            write(out, ((Builder) value).fields);
        } else {
            throw new IllegalArgumentException("cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
