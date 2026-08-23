package flow.extension.host;

import flow.extension.ViewCanvas;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The canvas a view is handed. It writes down each call rather than drawing it, so the drawing can
 * cross the pipe and be replayed by the app.
 *
 * The view is unaware of this — it draws, and what it drew arrives on screen. Recording is what
 * makes that work across the process boundary, and it is also what keeps text as text: the app
 * lays out the glyphs itself, at its own resolution, from the strings recorded here.
 *
 * A view is arbitrary code and may draw without ever stopping, so the number of calls is capped.
 * Hitting the cap fails the drawing instead of growing until the app runs out of memory.
 */
final class RecordingCanvas implements ViewCanvas {

    /** Enough for a very long hex dump; far short of what it takes to exhaust a heap. */
    private static final int MAX_OPS = 200_000;

    private final float width;
    private final float monoCharWidth;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(buf);
    private int ops = 0;
    private float contentHeight = 0f;

    RecordingCanvas(float width, float monoCharWidth) {
        this.width = width;
        this.monoCharWidth = monoCharWidth;
    }

    @Override
    public float getWidth() {
        return width;
    }

    @Override
    public float getMonoCharWidth() {
        return monoCharWidth;
    }

    @Override
    public void contentHeight(float height) {
        // the last word wins, so a view may revise it as it goes
        contentHeight = Math.max(0f, height);
    }

    @Override
    public void text(float x, float y, String text, float size, int color, boolean mono) {
        if (text == null || text.isEmpty()) return;
        begin(Wire.DRAW_TEXT);
        write(o -> {
            o.writeFloat(x);
            o.writeFloat(y);
            Wire.writeString(o, text);
            o.writeFloat(size);
            o.writeInt(color);
            o.writeBoolean(mono);
        });
    }

    @Override
    public void rect(float x, float y, float width, float height, int color, boolean filled) {
        begin(Wire.DRAW_RECT);
        write(o -> {
            o.writeFloat(x);
            o.writeFloat(y);
            o.writeFloat(width);
            o.writeFloat(height);
            o.writeInt(color);
            o.writeBoolean(filled);
        });
    }

    @Override
    public void line(float x1, float y1, float x2, float y2, int color, float strokeWidth) {
        begin(Wire.DRAW_LINE);
        write(o -> {
            o.writeFloat(x1);
            o.writeFloat(y1);
            o.writeFloat(x2);
            o.writeFloat(y2);
            o.writeInt(color);
            o.writeFloat(strokeWidth);
        });
    }

    @Override
    public void image(float x, float y, float width, float height, byte[] png) {
        if (png == null || png.length == 0) return;
        begin(Wire.DRAW_IMAGE);
        write(o -> {
            o.writeFloat(x);
            o.writeFloat(y);
            o.writeFloat(width);
            o.writeFloat(height);
            Wire.writeBytes(o, png);
        });
    }

    /** The recording, in the shape the app reads it back in. */
    byte[] finish() {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(all);
        try {
            o.writeFloat(contentHeight);
            o.writeInt(ops);
            o.write(buf.toByteArray());
            o.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return all.toByteArray();
    }

    private void begin(int op) {
        if (++ops > MAX_OPS) {
            throw new IllegalStateException(
                "the view drew more than " + MAX_OPS + " times — it should show less of the data at once");
        }
        write(o -> o.writeInt(op));
    }

    private interface Body { void write(DataOutputStream out) throws IOException; }

    private void write(Body body) {
        try {
            body.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // a growing byte array does not fail for real
        }
    }
}
