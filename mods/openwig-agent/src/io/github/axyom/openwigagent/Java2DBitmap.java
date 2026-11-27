package io.github.axyom.openwigagent;

import com.bitwig.graphics.pQW; // an image
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.function.Consumer;

/**
 * Any Java2D drawing as a bitmap: drawn into a premultiplied ARGB {@link BufferedImage} at the widget's pixel size,
 * uploaded once to Bitwig's renderer as an image, and drawn from there until it is invalidated or the size changes.
 * The fallback for libraries that only render to images (or need a Graphics2D feature the vector adapter lacks).
 */
final class Java2DBitmap {
    private BufferedImage image;
    private pQW uploaded;
    private Object system; // the graphics system the upload belongs to
    private boolean dirty = true;
    long drawNanos, uploadNanos; // the last refresh's cost

    void invalidate() { dirty = true; }

    /** Draws the bitmap at (x, y), {@code w} x {@code h} pixels, redrawing it with {@code painter} when needed. */
    void paint(Canvas c, double x, double y, int w, int h, double scale, Consumer<Graphics2D> painter) {
        if (w <= 0 || h <= 0) return;
        if (image == null || image.getWidth() != w || image.getHeight() != h) {
            image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
            dirty = true;
        }
        Object current = c.g.beI(); // the surface's graphics system
        if (current != system) dirty = true;
        if (dirty) {
            long t0 = System.nanoTime();
            Graphics2D g = image.createGraphics();
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, w, h);
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(scale, scale); // draw in layout units, rendered at the screen's pixels
            try {
                painter.accept(g);
            } finally {
                g.dispose();
            }
            long t1 = System.nanoTime();
            dispose();
            uploaded = c.image(((DataBufferInt) image.getRaster().getDataBuffer()).getData(), w, h);
            system = current;
            drawNanos = t1 - t0;
            uploadNanos = System.nanoTime() - t1;
            dirty = false;
        }
        c.drawImage(uploaded, x, y);
    }

    void dispose() {
        if (uploaded != null) uploaded.iz_();
        uploaded = null;
    }
}
