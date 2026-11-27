package io.github.axyom.openwigagent;

import bitwig.obf.SZB; // a colour (ARGB)
import com.bitwig.base.platform.Lpf; // a named pool of native memory
import com.bitwig.base.platform.NativeMemoryBlock; // a block of native memory
import com.bitwig.graphics.FontExtents; // a font's ascent, descent, line height (pixels)
import com.bitwig.graphics.Gd1; // a paint source (a gradient)
import com.bitwig.graphics.Hvv; // how an image is filtered when drawn
import com.bitwig.graphics.PPR; // a pixel format
import com.bitwig.graphics.TextExtents; // a text's measured size (pixels)
import com.bitwig.graphics.ZtI; // the graphics system a surface belongs to (makes images)
import com.bitwig.graphics.iiG; // a font at a size, for one window
import com.bitwig.graphics.ijY; // Bitwig's drawing surface
import com.bitwig.graphics.mLC; // a fill rule
import com.bitwig.graphics.ofb; // a gradient
import com.bitwig.graphics.pQW; // an image
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Bitwig's drawing surface under readable names. It is Cairo's model (Bitwig ships a Cairo back end with the
 * same interface), recorded as render commands that Bitwig's GPU renderer (Skia) runs: build a path, then fill
 * or stroke it with the current colour, line width and gradient; save and restore the state; clip to a path.
 * Coordinates are the widget's pixels, (0, 0) at its top left. Only valid during the paint call it was made for.
 */
final class Canvas {
    private static final Lpf PIXELS = new Lpf("openwig-agent pixels"); // native memory for image uploads
    final ijY g;

    Canvas(ijY g) {
        this.g = g;
    }

    // ---- state -------------------------------------------------------------------------------------------------

    void save() { g.ZRB(); }
    void restore() { g.jTs(); }
    void translate(double x, double y) { g.MgQ(x, y); }
    void scale(double x, double y) { g.sCA(x, y); }
    void rotate(double radians) { g.Gef(radians); }
    /** The UI scale (1 at 100 %): pixels per layout unit. */
    double uiScale() { return g.sCA(); }

    void color(int argb) { g.sCA(new SZB(argb)); }            // the source colour, 0xAARRGGBB (straight alpha)
    void lineWidth(double w) { g.BBg(w); }
    void dash(double[] lengths, double offset) { g.beI(lengths, offset); }
    void evenOdd(boolean evenOdd) { g.beI(evenOdd ? mLC.Gef : mLC.beI); } // fill rule: even-odd or winding

    // ---- paths -------------------------------------------------------------------------------------------------

    void newPath() { g.gNZ(); }
    void moveTo(double x, double y) { g.Gef(x, y); }
    void lineTo(double x, double y) { g.beI(x, y); }
    void curveTo(double x1, double y1, double x2, double y2, double x3, double y3) { g.beI(x1, y1, x2, y2, x3, y3); }
    void closePath() { g.ymo(); }
    void rect(double x, double y, double w, double h) { g.Gef(x, y, w, h); }
    /** A circular arc, clockwise in screen coordinates, angles in radians from the +x axis. */
    void arc(double cx, double cy, double r, double from, double to) { g.beI(cx, cy, r, from, to); }
    void circle(double cx, double cy, double r) { g.Gef(cx, cy, r); }
    /** A rectangle with all four corners rounded (as Bitwig's own rounded panels draw). */
    void roundRect(double x, double y, double w, double h, double r) { g.sCA(x, y, w, h, r); }

    void fill() { g.scr(); }
    void fillPreserve() { g.wGt(); }
    void stroke() { g.wLf(); }
    void strokePreserve() { g.dvk(); }
    /** Intersects the clip with the current path (and clears the path), until the matching restore. */
    void clip() { g.Bqk(); }

    void fillRect(double x, double y, double w, double h, int argb) {
        newPath();
        rect(x, y, w, h);
        color(argb);
        fill();
    }

    void fillRoundRect(double x, double y, double w, double h, double r, int argb) {
        newPath();
        roundRect(x, y, w, h, r);
        color(argb);
        fill();
    }

    void strokeRoundRect(double x, double y, double w, double h, double r, double width, int argb) {
        newPath();
        roundRect(x + width / 2, y + width / 2, w - width, h - width, r);
        color(argb);
        lineWidth(width);
        stroke();
    }

    void line(double x0, double y0, double x1, double y1, double width, int argb) {
        newPath();
        moveTo(x0, y0);
        lineTo(x1, y1);
        color(argb);
        lineWidth(width);
        stroke();
    }

    /** A linear gradient from (x0, y0) to (x1, y1) as the source; stops are {offset, argb} pairs. */
    ofb linearGradient(double x0, double y0, double x1, double y1, double[] offsets, int[] colours) {
        ofb gradient = g.sCA(x0, y0, x1, y1);
        for (int i = 0; i < offsets.length; i++) gradient.beI(offsets[i], new SZB(colours[i])); // a colour stop
        g.beI((Gd1) gradient); // the source
        return gradient; // dispose (iz_) once the fill is done
    }

    // ---- text --------------------------------------------------------------------------------------------------

    void font(iiG font) { g.beI(font); }
    /** Text on the baseline at (x, y), in the current font. */
    void text(double x, double y, String text, int argb) { g.beI(x, y, text, new SZB(argb)); }
    static double advance(iiG font, String text) { return font.beI(text).BBg(); } // advanceX
    static double ascent(iiG font) { return font.beI().beI(); }
    static double descent(iiG font) { return font.beI().Gef(); }
    static double lineHeight(iiG font) { return font.beI().sCA(); }
    static TextExtents measure(iiG font, String text) { return font.beI(text); }
    static FontExtents extents(iiG font) { return font.beI(); }

    // ---- images ------------------------------------------------------------------------------------------------

    /**
     * An image of this surface's graphics system from premultiplied ARGB pixels (0xAARRGGBB, rows top to bottom),
     * uploaded to the GPU renderer. Dispose it ({@code iz_()}) when it is no longer drawn.
     */
    pQW image(int[] argb, int width, int height) {
        ZtI system = g.beI(); // the surface's graphics system
        int stride = system.beI(PPR.beI, width); // bytes per row for ARGB32
        NativeMemoryBlock block = PIXELS.beI(stride * height);
        try {
            ByteBuffer bytes = block.ymo(); // native byte order: 0xAARRGGBB lands as B, G, R, A as Bitwig wants
            if (stride == width * 4) {
                bytes.asIntBuffer().put(argb, 0, width * height);
            } else {
                for (int y = 0; y < height; y++) {
                    bytes.position(y * stride);
                    IntBuffer row = bytes.slice().order(bytes.order()).asIntBuffer();
                    row.put(argb, y * width, width);
                }
            }
            return system.beI(block, width, height, PPR.beI); // copies the pixels
        } finally {
            block.iz_(); // free
        }
    }

    /** The image at (x, y), one image pixel per surface pixel. */
    void drawImage(pQW image, double x, double y) { g.beI(image, x, y, Hvv.Gef); } // GOOD filtering
    void drawImage(pQW image, double x, double y, double alpha) { g.beI(image, x, y, alpha); }
}
