package io.github.axyom.openwigagent;

import com.bitwig.graphics.ofb; // a gradient
import com.bitwig.graphics.pQW; // an image
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.DataBufferInt;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.Map;

/**
 * A {@link Graphics2D} that draws on Bitwig's own surface, so code written for Java2D (charts, diagrams, SVG or
 * HTML renderers) draws vector, GPU-rendered and crisp at any UI scale inside a {@link PaintedWidget}. The transform,
 * paint, stroke, font, clip and composite are kept here; each shape is transformed in Java and sent to Bitwig as a
 * path. Strokes under a similarity transform use Bitwig's own stroker, others are outlined by Java2D's. Text is
 * drawn as the Java font's glyph outlines, so it matches the metrics Java2D code measured with. Images are uploaded
 * per call. Not supported: XOR mode, copyArea, composites other than source-over with alpha.
 */
final class CanvasGraphics2D extends Graphics2D {
    private static final Graphics2D SCRATCH = scratch(); // metrics and the device configuration, as Java2D gives them

    private final Canvas c;
    private AffineTransform transform;
    private Paint paint = Color.BLACK;
    private Color colour = Color.BLACK;
    private Stroke stroke = new BasicStroke(1f);
    private Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private Shape clip; // device space
    private Composite composite = AlphaComposite.SrcOver;
    private Color background = Color.BLACK;
    private final RenderingHints hints;

    /** Draws on {@code canvas} with {@code base} as the starting transform (user space to the widget's pixels). */
    CanvasGraphics2D(Canvas canvas, AffineTransform base) {
        this.c = canvas;
        this.transform = new AffineTransform(base);
        this.hints = new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        hints.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        hints.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private CanvasGraphics2D(CanvasGraphics2D from) {
        this.c = from.c;
        this.transform = new AffineTransform(from.transform);
        this.paint = from.paint;
        this.colour = from.colour;
        this.stroke = from.stroke;
        this.font = from.font;
        this.clip = from.clip;
        this.composite = from.composite;
        this.background = from.background;
        this.hints = (RenderingHints) from.hints.clone();
    }

    private static Graphics2D scratch() {
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    // ---- shapes ---------------------------------------------------------------------------------------------------

    @Override
    public void fill(Shape s) {
        fillDevice(s.getPathIterator(transform));
    }

    @Override
    public void draw(Shape s) {
        if (stroke instanceof BasicStroke basic && similarity(transform)) {
            double scale = Math.sqrt(Math.abs(transform.getDeterminant()));
            c.save();
            applyClip();
            path(s.getPathIterator(transform));
            source();
            c.lineWidth(Math.max(1, basic.getLineWidth() * scale)); // 0: Java2D's thinnest line
            c.g.beI(switch (basic.getEndCap()) { // the line cap
                case BasicStroke.CAP_ROUND -> com.bitwig.graphics.Tlo.Gef;
                case BasicStroke.CAP_SQUARE -> com.bitwig.graphics.Tlo.sCA;
                default -> com.bitwig.graphics.Tlo.beI;
            });
            c.g.beI(switch (basic.getLineJoin()) { // the line join
                case BasicStroke.JOIN_ROUND -> com.bitwig.graphics.uDD.Gef;
                case BasicStroke.JOIN_BEVEL -> com.bitwig.graphics.uDD.sCA;
                default -> com.bitwig.graphics.uDD.beI;
            });
            float[] dashes = basic.getDashArray();
            if (dashes != null) {
                double[] d = new double[dashes.length];
                for (int i = 0; i < d.length; i++) d[i] = dashes[i] * scale;
                c.dash(d, basic.getDashPhase() * scale);
            }
            c.stroke();
            if (dashes != null) c.dash(new double[0], 0);
            c.restore();
        } else {
            fill(stroke.createStrokedShape(s)); // outlined by Java2D, then filled through the transform
        }
    }

    /** No shear and the same scale on both axes (rotations allowed): Bitwig's stroker then draws it exactly. */
    private static boolean similarity(AffineTransform t) {
        double a = t.getScaleX(), b = t.getShearY(), cc = t.getShearX(), d = t.getScaleY();
        return Math.abs(a - d) < 1e-9 && Math.abs(b + cc) < 1e-9 || Math.abs(a + d) < 1e-9 && Math.abs(b - cc) < 1e-9;
    }

    private void fillDevice(PathIterator it) {
        c.save();
        applyClip();
        path(it);
        source();
        c.fill();
        c.restore();
    }

    private void applyClip() {
        if (clip == null) return;
        path(clip.getPathIterator(null));
        c.clip();
    }

    /** The path in device coordinates (already transformed), with its fill rule. */
    private void path(PathIterator it) {
        c.newPath();
        c.evenOdd(it.getWindingRule() == PathIterator.WIND_EVEN_ODD);
        double[] p = new double[6];
        double x = 0, y = 0, startX = 0, startY = 0;
        for (; !it.isDone(); it.next()) {
            switch (it.currentSegment(p)) {
                case PathIterator.SEG_MOVETO -> { c.moveTo(p[0], p[1]); x = startX = p[0]; y = startY = p[1]; }
                case PathIterator.SEG_LINETO -> { c.lineTo(p[0], p[1]); x = p[0]; y = p[1]; }
                case PathIterator.SEG_QUADTO -> { // a quadratic as the equivalent cubic
                    c.curveTo(x + 2.0 / 3 * (p[0] - x), y + 2.0 / 3 * (p[1] - y),
                            p[2] + 2.0 / 3 * (p[0] - p[2]), p[3] + 2.0 / 3 * (p[1] - p[3]), p[2], p[3]);
                    x = p[2]; y = p[3];
                }
                case PathIterator.SEG_CUBICTO -> { c.curveTo(p[0], p[1], p[2], p[3], p[4], p[5]); x = p[4]; y = p[5]; }
                case PathIterator.SEG_CLOSE -> { c.closePath(); x = startX; y = startY; }
                default -> { }
            }
        }
    }

    private float alpha() {
        return composite instanceof AlphaComposite a ? a.getAlpha() : 1f;
    }

    private int argb(Color col) {
        int a = Math.round(col.getAlpha() * alpha());
        return (a << 24) | (col.getRGB() & 0xFFFFFF);
    }

    /** The paint as Bitwig's source: a colour, or a linear gradient (in device space). */
    private void source() {
        if (paint instanceof GradientPaint gp) {
            Point2D p1 = transform.transform(gp.getPoint1(), null), p2 = transform.transform(gp.getPoint2(), null);
            dispose(c.linearGradient(p1.getX(), p1.getY(), p2.getX(), p2.getY(), new double[] {0, 1},
                    new int[] {argb(gp.getColor1()), argb(gp.getColor2())}));
        } else if (paint instanceof LinearGradientPaint lgp) {
            AffineTransform t = new AffineTransform(transform);
            t.concatenate(lgp.getTransform());
            Point2D p1 = t.transform(lgp.getStartPoint(), null), p2 = t.transform(lgp.getEndPoint(), null);
            float[] f = lgp.getFractions();
            Color[] cs = lgp.getColors();
            double[] offsets = new double[f.length];
            int[] colours = new int[f.length];
            for (int i = 0; i < f.length; i++) { offsets[i] = f[i]; colours[i] = argb(cs[i]); }
            dispose(c.linearGradient(p1.getX(), p1.getY(), p2.getX(), p2.getY(), offsets, colours));
        } else {
            c.color(argb(paint instanceof Color col ? col : colour));
        }
    }

    /** Gradients are disposed once set: the source keeps what it needs until the next one (Bitwig's own pattern). */
    private final java.util.List<ofb> gradients = new java.util.ArrayList<>();

    private void dispose(ofb gradient) {
        gradients.add(gradient); // disposed with this Graphics2D (after the frame's commands that use it)
    }

    @Override
    public void dispose() {
        for (ofb g : gradients) g.iz_();
        gradients.clear();
    }

    // ---- text -----------------------------------------------------------------------------------------------------

    @Override
    public void drawString(String str, float x, float y) {
        if (str == null || str.isEmpty()) return;
        drawGlyphVector(font.createGlyphVector(getFontRenderContext(), str), x, y);
    }

    @Override
    public void drawString(String str, int x, int y) { drawString(str, (float) x, (float) y); }

    @Override
    public void drawString(AttributedCharacterIterator iterator, float x, float y) {
        TextLayout layout = new TextLayout(iterator, getFontRenderContext());
        fill(layout.getOutline(AffineTransform.getTranslateInstance(x, y)));
    }

    @Override
    public void drawString(AttributedCharacterIterator iterator, int x, int y) { drawString(iterator, (float) x, (float) y); }

    @Override
    public void drawGlyphVector(GlyphVector g, float x, float y) {
        fill(g.getOutline(x, y));
    }

    @Override
    public FontRenderContext getFontRenderContext() {
        return new FontRenderContext(null, true, true); // antialiased, fractional metrics
    }

    @Override public Font getFont() { return font; }
    @Override public void setFont(Font f) { if (f != null) font = f; }
    @Override public FontMetrics getFontMetrics(Font f) { return SCRATCH.getFontMetrics(f); }

    // ---- images ---------------------------------------------------------------------------------------------------

    @Override
    public boolean drawImage(Image img, AffineTransform xform, ImageObserver obs) {
        BufferedImage image = argbPre(img);
        if (image == null) return true;
        AffineTransform t = new AffineTransform(transform);
        if (xform != null) t.concatenate(xform);
        int w = image.getWidth(), h = image.getHeight();
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        pQW uploaded = c.image(pixels, w, h);
        c.save();
        applyClip();
        // translation, rotation and scale (Bitwig's surface has no shear)
        double angle = Math.atan2(t.getShearY(), t.getScaleX());
        double sx = Math.hypot(t.getScaleX(), t.getShearY());
        double sy = sx == 0 ? 0 : t.getDeterminant() / sx;
        c.translate(t.getTranslateX(), t.getTranslateY());
        c.rotate(angle);
        c.scale(sx, sy);
        float a = alpha();
        if (a < 1f) c.drawImage(uploaded, 0, 0, a); else c.drawImage(uploaded, 0, 0);
        c.restore();
        uploaded.iz_(); // freed after the frame's commands that draw it
        return true;
    }

    /** The image as premultiplied ARGB pixels Bitwig can upload as they are. */
    private static BufferedImage argbPre(Image img) {
        if (img instanceof BufferedImage b && b.getType() == BufferedImage.TYPE_INT_ARGB_PRE) return b;
        int w = img.getWidth(null), h = img.getHeight(null);
        if (w <= 0 || h <= 0) return null;
        BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = copy.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return copy;
    }

    @Override
    public boolean drawImage(Image img, int x, int y, ImageObserver o) {
        return drawImage(img, AffineTransform.getTranslateInstance(x, y), o);
    }

    @Override
    public boolean drawImage(Image img, int x, int y, int w, int h, ImageObserver o) {
        int iw = img.getWidth(null), ih = img.getHeight(null);
        if (iw <= 0 || ih <= 0) return true;
        AffineTransform t = AffineTransform.getTranslateInstance(x, y);
        t.scale((double) w / iw, (double) h / ih);
        return drawImage(img, t, o);
    }

    @Override
    public boolean drawImage(Image img, int x, int y, Color bg, ImageObserver o) {
        fillBackground(bg, x, y, img.getWidth(null), img.getHeight(null));
        return drawImage(img, x, y, o);
    }

    @Override
    public boolean drawImage(Image img, int x, int y, int w, int h, Color bg, ImageObserver o) {
        fillBackground(bg, x, y, w, h);
        return drawImage(img, x, y, w, h, o);
    }

    @Override
    public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, ImageObserver o) {
        BufferedImage image = argbPre(img);
        if (image == null || sx2 <= sx1 || sy2 <= sy1) return true;
        BufferedImage part = image.getSubimage(sx1, sy1, sx2 - sx1, sy2 - sy1);
        return drawImage(part, Math.min(dx1, dx2), Math.min(dy1, dy2), Math.abs(dx2 - dx1), Math.abs(dy2 - dy1), o);
    }

    @Override
    public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, Color bg, ImageObserver o) {
        fillBackground(bg, Math.min(dx1, dx2), Math.min(dy1, dy2), Math.abs(dx2 - dx1), Math.abs(dy2 - dy1));
        return drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, o);
    }

    private void fillBackground(Color bg, int x, int y, int w, int h) {
        if (bg == null) return;
        Paint saved = paint;
        paint = bg;
        fill(new Rectangle(x, y, w, h));
        paint = saved;
    }

    @Override
    public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) {
        drawImage(op != null ? op.filter(img, null) : img, x, y, null);
    }

    @Override
    public void drawRenderedImage(RenderedImage img, AffineTransform xform) {
        if (img instanceof BufferedImage b) drawImage(b, xform, null);
    }

    @Override
    public void drawRenderableImage(RenderableImage img, AffineTransform xform) {
        drawRenderedImage(img.createDefaultRendering(), xform);
    }

    // ---- Graphics' shape helpers ----------------------------------------------------------------------------------

    @Override public void drawLine(int x1, int y1, int x2, int y2) { draw(new Line2D.Float(x1, y1, x2, y2)); }
    @Override public void fillRect(int x, int y, int w, int h) { fill(new Rectangle(x, y, w, h)); }
    @Override public void drawRect(int x, int y, int w, int h) { draw(new Rectangle(x, y, w, h)); }

    @Override
    public void clearRect(int x, int y, int w, int h) {
        Composite saved = composite;
        composite = AlphaComposite.SrcOver;
        fillBackground(background, x, y, w, h);
        composite = saved;
    }

    @Override public void drawRoundRect(int x, int y, int w, int h, int aw, int ah) { draw(new RoundRectangle2D.Float(x, y, w, h, aw, ah)); }
    @Override public void fillRoundRect(int x, int y, int w, int h, int aw, int ah) { fill(new RoundRectangle2D.Float(x, y, w, h, aw, ah)); }
    @Override public void drawOval(int x, int y, int w, int h) { draw(new Ellipse2D.Float(x, y, w, h)); }
    @Override public void fillOval(int x, int y, int w, int h) { fill(new Ellipse2D.Float(x, y, w, h)); }
    @Override public void drawArc(int x, int y, int w, int h, int start, int extent) { draw(new Arc2D.Float(x, y, w, h, start, extent, Arc2D.OPEN)); }
    @Override public void fillArc(int x, int y, int w, int h, int start, int extent) { fill(new Arc2D.Float(x, y, w, h, start, extent, Arc2D.PIE)); }

    @Override
    public void drawPolyline(int[] xs, int[] ys, int n) {
        if (n < 2) return;
        java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
        p.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) p.lineTo(xs[i], ys[i]);
        draw(p);
    }

    @Override public void drawPolygon(int[] xs, int[] ys, int n) { draw(new Polygon(xs, ys, n)); }
    @Override public void fillPolygon(int[] xs, int[] ys, int n) { fill(new Polygon(xs, ys, n)); }

    @Override
    public boolean hit(Rectangle rect, Shape s, boolean onStroke) {
        Shape shape = onStroke ? stroke.createStrokedShape(s) : s;
        return transform.createTransformedShape(shape).intersects(rect);
    }

    @Override public void copyArea(int x, int y, int w, int h, int dx, int dy) { } // not supported

    // ---- state ----------------------------------------------------------------------------------------------------

    @Override public Graphics create() { return new CanvasGraphics2D(this); }
    @Override public Color getColor() { return colour; }
    @Override public void setColor(Color col) { if (col != null) { colour = col; paint = col; } }
    @Override public void setPaint(Paint p) { if (p != null) { paint = p; if (p instanceof Color col) colour = col; } }
    @Override public Paint getPaint() { return paint; }
    @Override public void setPaintMode() { }
    @Override public void setXORMode(Color c1) { } // not supported
    @Override public void setStroke(Stroke s) { if (s != null) stroke = s; }
    @Override public Stroke getStroke() { return stroke; }
    @Override public void setComposite(Composite comp) { if (comp != null) composite = comp; }
    @Override public Composite getComposite() { return composite; }
    @Override public void setBackground(Color col) { background = col; }
    @Override public Color getBackground() { return background; }
    @Override public void setRenderingHint(RenderingHints.Key key, Object value) { hints.put(key, value); }
    @Override public Object getRenderingHint(RenderingHints.Key key) { return hints.get(key); }
    @Override public void setRenderingHints(Map<?, ?> map) { hints.clear(); hints.putAll(map); }
    @Override public void addRenderingHints(Map<?, ?> map) { hints.putAll(map); }
    @Override public RenderingHints getRenderingHints() { return (RenderingHints) hints.clone(); }
    @Override public GraphicsConfiguration getDeviceConfiguration() { return SCRATCH.getDeviceConfiguration(); }

    @Override public void translate(int x, int y) { transform.translate(x, y); }
    @Override public void translate(double x, double y) { transform.translate(x, y); }
    @Override public void rotate(double theta) { transform.rotate(theta); }
    @Override public void rotate(double theta, double x, double y) { transform.rotate(theta, x, y); }
    @Override public void scale(double sx, double sy) { transform.scale(sx, sy); }
    @Override public void shear(double shx, double shy) { transform.shear(shx, shy); }
    @Override public void transform(AffineTransform tx) { transform.concatenate(tx); }
    @Override public void setTransform(AffineTransform tx) { transform = new AffineTransform(tx); }
    @Override public AffineTransform getTransform() { return new AffineTransform(transform); }

    @Override
    public void clip(Shape s) {
        if (s == null) { clip = null; return; }
        Shape device = transform.createTransformedShape(s);
        if (clip == null) { clip = device; return; }
        Area a = new Area(clip);
        a.intersect(new Area(device));
        clip = a;
    }

    @Override public void setClip(Shape s) { clip = s == null ? null : transform.createTransformedShape(s); }
    @Override public void setClip(int x, int y, int w, int h) { setClip(new Rectangle(x, y, w, h)); }
    @Override public void clipRect(int x, int y, int w, int h) { clip(new Rectangle(x, y, w, h)); }

    @Override
    public Shape getClip() {
        if (clip == null) return null;
        try {
            return transform.createInverse().createTransformedShape(clip);
        } catch (NoninvertibleTransformException e) {
            return null;
        }
    }

    @Override
    public Rectangle getClipBounds() {
        Shape s = getClip();
        return s == null ? null : s.getBounds();
    }

    /** For tests: a rectangle in user space as the device rectangle it covers. */
    Rectangle2D device(Rectangle2D r) { return transform.createTransformedShape(r).getBounds2D(); }
}
