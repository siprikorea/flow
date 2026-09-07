package flow.extension.host;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Framing for the host ⇄ worker pipe.
 *
 * Written by hand rather than with a serialization library so the worker's classpath stays down to
 * the extension contract, the Kotlin runtime and the extension itself — anything else on it would
 * be another thing an extension could collide with, which is the point of running it out of
 * process in the first place.
 *
 * Strings are length-prefixed UTF-8 (not DataOutput's modified UTF-8, which caps at 64KB — option
 * values have no such limit). Byte arrays carry -1 for null, which a port legitimately is.
 */
public final class Wire {
    private Wire() {}

    // requests
    public static final int DESCRIBE = 1;   // -> the extensions this worker loaded
    public static final int PROCESS  = 2;   // (id, inputs, options) -> outputs
    public static final int PORTS    = 3;   // (id, options) -> inputs, outputs
    public static final int OPTIONS  = 4;   // (id, values) -> option specs
    public static final int VIEW_DESCRIBE   = 5; // -> the views this worker loaded
    public static final int VIEW_OPEN       = 6; // (id, data, options) -> opens a window, returns at once
    public static final int VIEW_FOCUS      = 7; // -> brings this process's windows to the front
    public static final int SETTINGS = 8;   // (id, values) -> the extension's own setting specs

    // responses
    public static final int OK    = 0;
    public static final int ERROR = 1;

    public static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    public static String readString(DataInputStream in) throws IOException {
        byte[] b = new byte[in.readInt()];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    public static void writeBytes(DataOutputStream out, byte[] b) throws IOException {
        if (b == null) { out.writeInt(-1); return; }
        out.writeInt(b.length);
        out.write(b);
    }

    public static byte[] readBytes(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0) return null;
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    public static void writeStringMap(DataOutputStream out, Map<String, String> m) throws IOException {
        out.writeInt(m.size());
        for (Map.Entry<String, String> e : m.entrySet()) {
            writeString(out, e.getKey());
            writeString(out, e.getValue());
        }
    }

    public static Map<String, String> readStringMap(DataInputStream in) throws IOException {
        int n = in.readInt();
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) m.put(readString(in), readString(in));
        return m;
    }

    public static void writeByteMap(DataOutputStream out, Map<String, byte[]> m) throws IOException {
        out.writeInt(m.size());
        for (Map.Entry<String, byte[]> e : m.entrySet()) {
            writeString(out, e.getKey());
            writeBytes(out, e.getValue());
        }
    }

    public static Map<String, byte[]> readByteMap(DataInputStream in) throws IOException {
        int n = in.readInt();
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) m.put(readString(in), readBytes(in));
        return m;
    }

    public static void writeStringList(DataOutputStream out, List<String> l) throws IOException {
        out.writeInt(l.size());
        for (String s : l) writeString(out, s);
    }

    public static List<String> readStringList(DataInputStream in) throws IOException {
        int n = in.readInt();
        List<String> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(readString(in));
        return l;
    }
}
