package flow.extension.host;

import flow.extension.ExtensionOption;
import flow.extension.ModuleExtension;
import flow.extension.ViewExtension;
import flow.extension.ProcessorExtension;

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

    /**
     * How long to keep watching a view before deciding the window is not coming.
     *
     * It is not a delay on the ordinary path: this returns the moment a window appears, which on a
     * warm worker is immediate. It is how long a view that never manages one is given before that
     * is reported, so it has to outlast a cold JVM loading Skiko — the failure that made this
     * necessary took longer than a second and a half to surface, which is why the fixed wait it
     * replaces called it a success.
     */
    private static final long WINDOW_GRACE_MS = 8_000;

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
        Map<String, ProcessorExtension> byId = loaded.processors;
        switch (op) {
            case Wire.DESCRIBE:
                return () -> reply(out, writeLock, reqId, o -> describe(byId, o));
            case Wire.PROCESS: {
                String id = Wire.readString(in);
                Map<String, byte[]> inputs = Wire.readByteMap(in);
                Map<String, String> options = Wire.readStringMap(in);
                Map<String, String> settings = Wire.readStringMap(in);
                return () -> reply(out, writeLock, reqId, o -> {
                    Map<String, byte[]> r = need(byId, id).process(inputs, merged(settings, options));
                    if (r == null) throw new IllegalStateException("processor '" + id + "' returned no result");
                    Wire.writeByteMap(o, r);
                });
            }
            case Wire.PORTS: {
                String id = Wire.readString(in);
                Map<String, String> options = Wire.readStringMap(in);
                return () -> reply(out, writeLock, reqId, o -> {
                    ProcessorExtension e = need(byId, id);
                    Wire.writeStringList(o, e.inputsFor(options));
                    Wire.writeStringList(o, e.outputsFor(options));
                });
            }
            case Wire.OPTIONS: {
                String id = Wire.readString(in);
                Map<String, String> values = Wire.readStringMap(in);
                return () -> reply(out, writeLock, reqId, o -> writeOptions(o, need(byId, id).optionsFor(values), java.util.List.of()));
            }
            case Wire.SETTINGS: {
                String id = Wire.readString(in);
                Map<String, String> values = Wire.readStringMap(in);
                // off the host's UI thread by the time it gets here, which is what lets an
                // extension answer this by asking a server what it has
                return () -> reply(out, writeLock, reqId, o -> {
                    ProcessorExtension e = need(byId, id);
                    writeOptions(o, e.settingsFor(values), e.getSecretSettings());
                });
            }
            case Wire.VIEW_DESCRIBE:
                return () -> reply(out, writeLock, reqId, o -> describeViews(loaded.views, o));
            case Wire.VIEW_OPEN: {
                String id = Wire.readString(in);
                byte[] data = Wire.readBytes(in);
                Map<String, String> options = Wire.readStringMap(in);
                return () -> reply(out, writeLock, reqId, o -> {
                    ViewExtension v = loaded.views.get(id);
                    if (v == null) throw new IllegalStateException("view '" + id + "' is not in this extension");
                    // A window has its own life: opening one blocks for as long as it is up, and the
                    // app is not waiting for it. So this returns as soon as the window is up, and
                    // anything that happens afterwards is the window's own business.
                    //
                    // "As soon as the window is up" is watched for rather than waited out. A fixed
                    // pause is wrong in both directions: too short and a view that dies slowly is
                    // reported as opened — which is what happened when Compose gained a module the
                    // worker was not being given, and views stopped opening with nothing said —
                    // while too long is felt on every open that works.
                    final Throwable[] failure = new Throwable[1];
                    int before = showingWindows();
                    Thread window = new Thread(() -> {
                        try {
                            v.open(data != null ? data : new byte[0], options);
                        } catch (Throwable t) {
                            failure[0] = t;
                        }
                    }, "view-" + id);
                    window.setDaemon(false);
                    window.start();

                    // Watched for on screen, not waited on by the thread: open() hands the window
                    // to the toolkit and returns at once, so the thread is finished a few
                    // milliseconds later whether a window ever appears or not. That is why the
                    // fixed join this replaces could never have caught anything.
                    long deadline = System.currentTimeMillis() + WINDOW_GRACE_MS;
                    while (failure[0] == null && showingWindows() <= before
                        && System.currentTimeMillis() < deadline) {
                        Thread.sleep(20);
                    }
                    if (failure[0] != null) {
                        throw new IllegalStateException(
                            "the view could not open a window: " + failure[0], failure[0]);
                    }
                    if (showingWindows() <= before) {
                        // Nothing threw where this could see it — a window that fails inside the
                        // toolkit's own event thread takes the exception with it — so all that can
                        // be said is what the user already knows. The reason is on stderr.
                        throw new IllegalStateException(
                            "the view did not open a window (see the log for why)");
                    }
                });
            }
            case Wire.VIEW_FOCUS:
                return () -> reply(out, writeLock, reqId, o -> bringWindowsForward());
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

    private static ProcessorExtension need(Map<String, ProcessorExtension> byId, String id) {
        ProcessorExtension e = byId.get(id);
        if (e == null) throw new IllegalStateException("processor '" + id + "' is not in this extension");
        return e;
    }

    private static void describe(Map<String, ProcessorExtension> byId, DataOutputStream o) throws IOException {
        o.writeInt(byId.size());
        for (ProcessorExtension e : byId.values()) {
            Wire.writeString(o, e.getId());
            Wire.writeString(o, e.getDisplayName());
            Wire.writeString(o, e.getVersion());
            Wire.writeStringList(o, e.getInputs());
            Wire.writeStringList(o, e.getOutputs());
            writeOptions(o, e.getOptions(), java.util.List.of());
            // the extension's own settings, which the host edits once rather than per node, and
            // which of them hold a key
            writeOptions(o, e.getSettings(), e.getSecretSettings());
            // what a generated schema needs beyond the names: which ports may be left out, and
            // what each port and option is for. Asked with the options at their defaults, which is
            // the only set of values a static schema can be about.
            Map<String, String> defaults = new LinkedHashMap<>();
            for (ExtensionOption opt : e.getOptions()) defaults.put(opt.getName(), opt.getDefault());
            Wire.writeStringList(o, e.optionalInputsFor(defaults));
            Wire.writeStringMap(o, e.getPortDescriptions());
            Wire.writeStringMap(o, e.getOptionDescriptions());
            Wire.writeStringList(o, e.getSensitiveInputs());
        }
    }

    /**
     * Brings whatever this process has on screen to the front.
     *
     * Nothing an extension writes is involved: a JVM knows its own windows, so the host can do this
     * for any view without the view having to offer a way. Which matters because the reason to ask
     * is switching between windows, and that has to work for every view, not the ones that thought
     * of it.
     *
     * A process with no dock icon is a background one to macOS and cannot put itself in front of
     * the app that is — raising the window above everything and letting go at once can.
     */
    /** How many windows this process currently has on screen — the signal that a view opened one. */
    private static int showingWindows() {
        int n = 0;
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w.isShowing()) n++;
        }
        return n;
    }

    private static void bringWindowsForward() {
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (!w.isShowing()) continue;
            w.setAlwaysOnTop(true);
            w.toFront();
            w.requestFocus();
            w.setAlwaysOnTop(false);
        }
    }

    private static void describeViews(Map<String, ViewExtension> views, DataOutputStream o) throws IOException {
        o.writeInt(views.size());
        for (ViewExtension v : views.values()) {
            Wire.writeString(o, v.getId());
            Wire.writeString(o, v.getDisplayName());
            Wire.writeString(o, v.getVersion());
            Wire.writeString(o, v.getDescription());
        }
    }

    /**
     * The node's options over the extension's settings.
     *
     * A blank node option is not an answer, it is the absence of one — every node is created with
     * its options at their defaults, so "unset" has to look like something, and blank is what it
     * looks like. That is what makes a setting a default the node can override rather than a value
     * nothing can reach.
     */
    private static Map<String, String> merged(Map<String, String> settings, Map<String, String> options) {
        Map<String, String> all = new LinkedHashMap<>(settings);
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) all.put(e.getKey(), e.getValue());
            else all.putIfAbsent(e.getKey(), e.getValue());
        }
        return all;
    }

    private static void writeOptions(DataOutputStream o, List<ExtensionOption> options, List<String> secrets)
        throws IOException {
        o.writeInt(options.size());
        for (ExtensionOption opt : options) {
            Wire.writeString(o, opt.getName());
            Wire.writeString(o, opt.getType().name());
            Wire.writeString(o, opt.getDefault());
            Wire.writeStringList(o, opt.getChoices());
            o.writeBoolean(secrets.contains(opt.getName()));
        }
    }

    /** What one extension folder turned out to provide. */
    private static final class Loaded {
        final Map<String, ProcessorExtension> processors = new LinkedHashMap<>();
        final Map<String, ViewExtension> views = new LinkedHashMap<>();
    }

    /**
     * Every jar named on the command line, loaded together as this one extension.
     *
     * The three kinds are looked up separately but come from the same loader, so one extension may
     * ship more than one — a processor and the output that makes sense of what it produces.
     */
    @SuppressWarnings("deprecation")
    private static Loaded load(String[] jars) throws Exception {
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) urls[i] = new File(jars[i]).toURI().toURL();
        // the contract comes from this process's own classpath; everything else from the jars
        URLClassLoader cl = new URLClassLoader(urls, ExtensionWorker.class.getClassLoader());
        Loaded loaded = new Loaded();
        for (ProcessorExtension e : ServiceLoader.load(ProcessorExtension.class, cl)) {
            loaded.processors.put(e.getId(), e);
        }
        // also under the name a processor used to have, so an extension built before the rename
        // still loads without being rebuilt
        for (ModuleExtension e : ServiceLoader.load(ModuleExtension.class, cl)) {
            loaded.processors.putIfAbsent(e.getId(), e);
        }
        // a jar with no views is the common case, and ServiceLoader is happy to find none
        for (ViewExtension v : ServiceLoader.load(ViewExtension.class, cl)) loaded.views.put(v.getId(), v);
        return loaded;
    }
}
