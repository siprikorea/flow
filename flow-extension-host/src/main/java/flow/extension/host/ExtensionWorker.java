package flow.extension.host;

import flow.extension.ExtensionOption;
import flow.extension.ModuleExtension;
import flow.extension.ViewEvent;
import flow.extension.ViewExtension;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.*;

/**
 * Serves one extension folder in its own process.
 *
 * Started with nothing on its classpath but the extension contract, the Kotlin runtime and the
 * folder's own jars, so an extension's dependencies cannot meet the app's — and the app's cannot
 * quietly replace the extension's, which is what happened while both lived in one JVM.
 *
 * Requests are read from stdin and answered on stdout, each tagged with the id the host gave it so
 * several can be in flight at once: the engine runs independent nodes at the same time and one
 * slow module must not hold up the rest. Nothing else is written to stdout — a stray print would
 * corrupt the stream — so the extension's own stdout is redirected to stderr on the way in.
 */
public final class ExtensionWorker {

    public static void main(String[] args) throws Exception {
        // an extension printing to stdout would land in the middle of a frame
        PrintStream appStdout = System.out;
        System.setOut(System.err);

        Loaded loaded = load(args);

        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(appStdout));
        Object writeLock = new Object();
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "extension-call");
            t.setDaemon(true);
            return t;
        });

        while (true) {
            int reqId;
            try {
                reqId = in.readInt();
            } catch (EOFException e) {
                break; // host closed the pipe: it is done with us
            }
            int op = in.readInt();
            // the whole request is read here, on the one reading thread, before it is handed off
            Runnable job = readJob(reqId, op, in, loaded, out, writeLock);
            pool.execute(job);
        }
        pool.shutdownNow();
    }

    /** Reads one request off the pipe and returns the work that answers it. */
    private static Runnable readJob(
        int reqId, int op, DataInputStream in, Loaded loaded,
        DataOutputStream out, Object writeLock
    ) throws IOException {
        Map<String, ModuleExtension> byId = loaded.modules;
        switch (op) {
            case Wire.DESCRIBE:
                return () -> reply(out, writeLock, reqId, o -> describe(byId, o));
            case Wire.PROCESS: {
                String id = Wire.readString(in);
                Map<String, byte[]> inputs = Wire.readByteMap(in);
                Map<String, String> options = Wire.readStringMap(in);
                return () -> reply(out, writeLock, reqId, o -> {
                    Map<String, byte[]> r = need(byId, id).process(inputs, options);
                    if (r == null) throw new IllegalStateException("module '" + id + "' returned no result");
                    Wire.writeByteMap(o, r);
                });
            }
            case Wire.PORTS: {
                String id = Wire.readString(in);
                Map<String, String> options = Wire.readStringMap(in);
                return () -> reply(out, writeLock, reqId, o -> {
                    ModuleExtension e = need(byId, id);
                    Wire.writeStringList(o, e.inputsFor(options));
                    Wire.writeStringList(o, e.outputsFor(options));
                });
            }
            case Wire.OPTIONS: {
                String id = Wire.readString(in);
                Map<String, String> values = Wire.readStringMap(in);
                return () -> reply(out, writeLock, reqId, o -> writeOptions(o, need(byId, id).optionsFor(values)));
            }
            case Wire.VIEW_DESCRIBE:
                return () -> reply(out, writeLock, reqId, o -> describeViews(loaded.views, o));
            case Wire.VIEW_DRAW: {
                String id = Wire.readString(in);
                byte[] data = Wire.readBytes(in);
                Map<String, String> options = Wire.readStringMap(in);
                float width = in.readFloat();
                float monoCharWidth = in.readFloat();
                return () -> reply(out, writeLock, reqId, o -> {
                    ViewExtension v = loaded.views.get(id);
                    if (v == null) throw new IllegalStateException("view '" + id + "' is not in this extension");
                    RecordingCanvas canvas = new RecordingCanvas(width, monoCharWidth);
                    v.draw(canvas, data != null ? data : new byte[0], options);
                    o.write(canvas.finish());
                });
            }
            case Wire.VIEW_EVENT: {
                String id = Wire.readString(in);
                String kind = Wire.readString(in);
                String region = Wire.readString(in);
                float x = in.readFloat();
                float y = in.readFloat();
                Map<String, String> options = Wire.readStringMap(in);
                return () -> reply(out, writeLock, reqId, o -> {
                    ViewExtension v = loaded.views.get(id);
                    if (v == null) throw new IllegalStateException("view '" + id + "' is not in this extension");
                    // an empty region name means the click landed where the view claimed nothing
                    ViewEvent event = new ViewEvent(kind, region.isEmpty() ? null : region, x, y);
                    Map<String, String> next = v.onEvent(event, options);
                    Wire.writeStringMap(o, next != null ? next : options);
                });
            }
            default:
                return () -> reply(out, writeLock, reqId, o -> { throw new IllegalStateException("unknown request " + op); });
        }
    }

    private interface Body { void write(DataOutputStream out) throws Exception; }

    /** Runs [body], framing whatever it produces — or whatever it threw — as this request's reply. */
    private static void reply(DataOutputStream out, Object writeLock, int reqId, Body body) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int status = Wire.OK;
        try {
            body.write(new DataOutputStream(buf));
        } catch (Throwable t) {
            // an extension is arbitrary code: whatever it throws is this call's failure, not ours
            status = Wire.ERROR;
            buf.reset();
            String m = t.getMessage();
            try {
                Wire.writeString(new DataOutputStream(buf), m != null ? m : t.getClass().getSimpleName());
            } catch (IOException ignored) {
                return;
            }
        }
        synchronized (writeLock) {
            try {
                out.writeInt(reqId);
                out.writeInt(status);
                byte[] payload = buf.toByteArray();
                out.writeInt(payload.length);
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                // the host has gone; nothing useful left to do here
            }
        }
    }

    private static ModuleExtension need(Map<String, ModuleExtension> byId, String id) {
        ModuleExtension e = byId.get(id);
        if (e == null) throw new IllegalStateException("module '" + id + "' is not in this extension");
        return e;
    }

    private static void describe(Map<String, ModuleExtension> byId, DataOutputStream o) throws IOException {
        o.writeInt(byId.size());
        for (ModuleExtension e : byId.values()) {
            Wire.writeString(o, e.getId());
            Wire.writeString(o, e.getDisplayName());
            Wire.writeString(o, e.getVersion());
            Wire.writeStringList(o, e.getInputs());
            Wire.writeStringList(o, e.getOutputs());
            writeOptions(o, e.getOptions());
        }
    }

    private static void describeViews(Map<String, ViewExtension> views, DataOutputStream o) throws IOException {
        o.writeInt(views.size());
        for (ViewExtension v : views.values()) {
            Wire.writeString(o, v.getId());
            Wire.writeString(o, v.getDisplayName());
            Wire.writeString(o, v.getVersion());
            o.writeBoolean(v.getWantsHover());
            writeOptions(o, v.getOptions());
        }
    }

    private static void writeOptions(DataOutputStream o, List<ExtensionOption> options) throws IOException {
        o.writeInt(options.size());
        for (ExtensionOption opt : options) {
            Wire.writeString(o, opt.getName());
            Wire.writeString(o, opt.getType().name());
            Wire.writeString(o, opt.getDefault());
            Wire.writeStringList(o, opt.getChoices());
        }
    }

    /** What one extension folder turned out to provide. */
    private static final class Loaded {
        final Map<String, ModuleExtension> modules = new LinkedHashMap<>();
        final Map<String, ViewExtension> views = new LinkedHashMap<>();
    }

    /**
     * Every jar named on the command line, loaded together as this one extension.
     *
     * Modules and views are looked up separately but come from the same loader, so one extension
     * may ship both — a module and the view that makes sense of what it produces.
     */
    private static Loaded load(String[] jars) throws Exception {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) urls[i] = new File(jars[i]).toURI().toURL();
        // the contract comes from this process's own classpath; everything else from the jars
        URLClassLoader cl = new URLClassLoader(urls, ExtensionWorker.class.getClassLoader());
        Loaded loaded = new Loaded();
        for (ModuleExtension e : ServiceLoader.load(ModuleExtension.class, cl)) loaded.modules.put(e.getId(), e);
        // a jar with no views is the common case, and ServiceLoader is happy to find none
        for (ViewExtension v : ServiceLoader.load(ViewExtension.class, cl)) loaded.views.put(v.getId(), v);
        return loaded;
    }
}
