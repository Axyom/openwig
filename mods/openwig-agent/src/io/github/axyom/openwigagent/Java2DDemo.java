package io.github.axyom.openwigagent;

import com.bitwig.graphics.iiG; // a font at a size, for one window
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.function.Supplier;

/**
 * A strip above the transcript that draws one Java2D scene twice: on the left through {@link CanvasGraphics2D}
 * (vector, on Bitwig's GPU surface), on the right through {@link Java2DBitmap} (a BufferedImage uploaded as an image),
 * with what each cost. The scene shows the transcript's message lengths, so it changes as the discussion goes on.
 */
final class Java2DDemo extends PaintedWidget {
    private static final double HEIGHT = 74; // layout units
    private static boolean benchmarked;
    private final Supplier<int[]> data;
    private final Java2DBitmap bitmap = new Java2DBitmap();
    private final Runnable onChange = this::changed;
    private long vectorNanos;
    private int[] shown;

    private Java2DDemo(Supplier<int[]> data) {
        super(200, (long) HEIGHT);
        this.data = data;
    }

    static Java2DDemo create(Transcript transcript, Supplier<int[]> data) {
        Java2DDemo demo = new Java2DDemo(data).ready();
        transcript.listen(demo.onChange);
        return demo;
    }

    private void changed() {
        bitmap.invalidate();
        repaint();
    }

    @Override public com.bitwig.base.geom.uXa nk_() { return new com.bitwig.base.geom.uXa(Long.MAX_VALUE, wGt((long) HEIGHT)); } // fixed height

    @Override
    public void iz_() { // disposed
        bitmap.dispose();
        super.iz_();
    }

    @Override
    protected void paint(Canvas c, double width, double height) {
        double u = unit();
        double gap = 6 * u, half = Math.floor((width - 3 * gap) / 2);
        double sceneH = height - 2 * gap - 14 * u;
        if (half < 20 || sceneH < 10) return;
        int[] values = shown = data.get();
        double sceneW = half / u, sceneHu = sceneH / u; // the scene in layout units

        // (a) vector: Java2D calls become Bitwig paths, at the screen's pixels
        long t0 = System.nanoTime();
        AffineTransform base = new AffineTransform(u, 0, 0, u, gap, gap); // layout units to this widget's pixels
        CanvasGraphics2D g2 = new CanvasGraphics2D(c, base);
        try {
            scene(g2, sceneW, sceneHu, values, "vector");
        } finally {
            g2.dispose();
        }
        vectorNanos = System.nanoTime() - t0;

        // (b) bitmap: the same calls into a BufferedImage, uploaded when the data changes
        bitmap.paint(c, 2 * gap + half, gap, (int) half, (int) Math.ceil(sceneH), u,
                g -> scene(g, sceneW, sceneHu, values, "bitmap"));

        iiG font = font(null);
        if (font != null) {
            c.font(font);
            int ink = textColour() & 0xB0FFFFFF;
            double y = height - gap / 2 - Canvas.descent(font);
            c.text(gap, y, String.format("Graphics2D on Bitwig's surface: %.2f ms", vectorNanos / 1e6), ink);
            c.text(2 * gap + half, y, String.format("BufferedImage: draw %.2f ms, upload %.2f ms",
                    bitmap.drawNanos / 1e6, bitmap.uploadNanos / 1e6), ink);
        }
        if (!benchmarked) {
            benchmarked = true;
            benchmark(c, (int) Math.round(width), u);
        }
    }

    /** A small chart: a framed panel, a gradient area under the message lengths, a dashed average, a dot, labels. */
    static void scene(Graphics2D g, double w, double h, int[] values, String label) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0x2B3A4A), 0, (float) h, new Color(0x1C232B)));
        g.fill(new RoundRectangle2D.Double(0, 0, w, h, 10, 10));
        int n = Math.max(2, values.length);
        double max = 1;
        for (int v : values) max = Math.max(max, v);
        Path2D.Double area = new Path2D.Double();
        Path2D.Double line = new Path2D.Double();
        double px = 6, py = h - 6;
        for (int i = 0; i < n; i++) {
            double v = i < values.length ? values[i] : 0;
            double x = 6 + (w - 12) * i / (n - 1), y = h - 6 - (h - 22) * v / max;
            if (i == 0) { area.moveTo(x, h - 6); area.lineTo(x, y); line.moveTo(x, y); }
            else { // smooth: a quadratic through the midpoint
                double mx = (px + x) / 2, my = (py + y) / 2;
                area.quadTo(px, py, mx, my);
                line.quadTo(px, py, mx, my);
            }
            px = x; py = y;
        }
        area.lineTo(px, py);
        line.lineTo(px, py);
        area.lineTo(px, h - 6);
        area.closePath();
        g.setPaint(new GradientPaint(0, 8, new Color(255, 140, 50, 170), 0, (float) h, new Color(255, 140, 50, 10)));
        g.fill(area);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0xFFB070));
        g.draw(line);
        double avg = 0;
        for (int v : values) avg += v;
        avg = values.length == 0 ? 0 : avg / values.length;
        double ay = h - 6 - (h - 22) * avg / max;
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4f, new float[] {4f, 3f}, 0f));
        g.setColor(new Color(255, 255, 255, 110));
        g.draw(new java.awt.geom.Line2D.Double(6, ay, w - 6, ay));
        g.setColor(new Color(0xFFD7B0));
        g.fill(new Ellipse2D.Double(px - 3, py - 3, 6, 6));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        g.setColor(new Color(255, 255, 255, 200));
        g.drawString("Java2D " + label, 8f, 13f);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
        String count = values.length + (values.length == 1 ? " message" : " messages");
        g.drawString(count, (float) (w - 8 - g.getFontMetrics().stringWidth(count)), 13f);
        g.setComposite(AlphaComposite.SrcOver);
        AffineTransform saved = g.getTransform();
        g.rotate(-Math.PI / 2, w - 4, h - 8);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 7));
        g.setColor(new Color(255, 255, 255, 90));
        g.drawString("length", (float) (w - 4), (float) (h - 8));
        g.setTransform(saved);
    }

    /** Once: what the bitmap path costs for a whole panel (the transcript's size), and what the vector path costs. */
    private void benchmark(Canvas c, int width, double u) {
        int w = Math.max(200, width), h = (int) Math.round(900 * u);
        int[] values = new int[60];
        for (int i = 0; i < values.length; i++) values[i] = 20 + (int) (80 * Math.abs(Math.sin(i * 0.37)));
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        long t0 = System.nanoTime();
        for (int round = 0; round < 3; round++) {
            Graphics2D g = image.createGraphics();
            g.scale(u, u);
            for (int row = 0; row < 8; row++) {
                Graphics2D r = (Graphics2D) g.create();
                r.translate(0, row * 110);
                scene(r, w / u, 100, values, "bench");
                r.dispose();
            }
            g.dispose();
        }
        long t1 = System.nanoTime();
        int[] pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
        com.bitwig.graphics.pQW up = c.image(pixels, w, h);
        long t2 = System.nanoTime();
        up.iz_();
        long t3 = System.nanoTime();
        for (int round = 0; round < 3; round++) {
            c.save();
            c.newPath();
            c.rect(0, 0, 0, 0); // nothing visible: clip everything away
            c.clip();
            for (int row = 0; row < 8; row++) {
                CanvasGraphics2D g = new CanvasGraphics2D(c, new AffineTransform(u, 0, 0, u, 0, row * 110 * u));
                scene(g, w / u, 100, values, "bench");
                g.dispose();
            }
            c.restore();
        }
        long t4 = System.nanoTime();
        AgentPanels.LOG.log(String.format(
                "paint benchmark at %dx%d px (UI scale %.2f): BufferedImage draw %.1f ms, upload %.1f ms; Graphics2D on Bitwig's surface %.1f ms (Java side, per frame)",
                w, h, u, (t1 - t0) / 3e6, (t2 - t1) / 1e6, (t4 - t3) / 3e6));
    }
}
