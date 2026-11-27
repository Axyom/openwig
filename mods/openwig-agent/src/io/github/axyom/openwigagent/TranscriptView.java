package io.github.axyom.openwigagent;

import bitwig.obf.egG; // a theme colour
import bitwig.obf.ehb; // a theme font (face and size)
import com.bitwig.graphics.iiG; // a font at a size, for one window
import io.github.axyom.openwigagent.Transcript.Entry;
import io.github.axyom.openwigagent.Transcript.Kind;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Agent panel's transcript, painted by the mod: the user's messages in accent bubbles on the right, the agent's
 * replies in bubbles on the left, tool calls and status lines as small text. Newest at the bottom; it follows new
 * text while scrolled to the bottom. Mouse wheel or drag to scroll, drag the bar on the right, click a bubble to copy
 * its text.
 */
final class TranscriptView extends PaintedWidget {
    private static final double PAD = 8, GAP = 6, BUBBLE_PAD_X = 8, BUBBLE_PAD_Y = 6, RADIUS = 7, BAR = 5; // layout units
    private static final double SHARE = 0.86; // a bubble's widest share of the width

    private final Transcript transcript;
    private final Runnable onChange = this::changed; // held here: the transcript holds its listeners weakly
    private final Map<Entry, Layout> layouts = new IdentityHashMap<>();
    private double scroll;          // pixels from the top
    private boolean follow = true;  // stay at the bottom as text arrives
    private double content;         // total height, pixels
    private double laidOutWidth = -1;
    private int laidOutVersion = -1;
    // a press: on a bubble (click to copy), on the bar (drag the thumb), else drag the content
    private Entry pressed;
    private boolean onBar;
    private double moved;
    private Entry flashed;
    private long flashUntil;

    /** One entry laid out at one width: wrapped lines and the bubble's box (content coordinates, pixels). */
    private static final class Layout {
        int version = -1;
        double width = -1;
        List<String> lines = List.of();
        double x, y, w, h;   // the bubble (or the text block)
    }

    private TranscriptView(Transcript transcript) {
        super(200, 300);
        this.transcript = transcript;
    }

    static TranscriptView create(Transcript transcript) {
        TranscriptView view = new TranscriptView(transcript).ready();
        transcript.listen(view.onChange);
        return view;
    }

    private void changed() {
        if (attached()) repaint();
    }

    // ---- painting -----------------------------------------------------------------------------------------------

    private iiG body, small;
    private int textColour, subtleColour, userFill, userStroke, agentFill, toolColour, errorColour, barColour, barActive, flashColour;

    private void theme() {
        body = font(named("Default"));
        small = font(named("Small"));
        if (small == null) small = body;
        textColour = colour("Light Text", textColour());
        subtleColour = colour("Subtle Light Text", 0x78FFFFFF);
        int accent = colour("Accent (default)", 0xFFFF8713); // Bitwig's orange
        userFill = withAlpha(accent, 0.34);
        userStroke = withAlpha(accent, 0.55);
        agentFill = colour("Grey 5", 0xFF494949);
        toolColour = subtleColour;
        errorColour = colour("Error Text", 0xC8FF3232);
        barColour = colour("Modern Scrollbar Handle (inactive)", 0xFF5F5F5F);
        barActive = colour("Modern Scrollbar Handle (active)", 0xFF787878);
        flashColour = withAlpha(accent, 0.7);
    }

    private ehb named(String name) {
        try {
            ehb spec = jm_().jao().beI(name); // the theme's font table, by name
            return spec != null ? spec : fontSpec();
        } catch (RuntimeException e) {
            return fontSpec();
        }
    }

    private int colour(String name, int fallback) {
        try {
            egG c = mum().beI(name); // the theme's colour table, by name
            return c != null ? c.beI(this).ZRB() : fallback; // resolved for this widget, 0xAARRGGBB
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int withAlpha(int argb, double alpha) {
        int a = (int) Math.round(((argb >>> 24) & 0xFF) * alpha);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    @Override
    protected void paint(Canvas c, double width, double height) {
        theme();
        if (body == null) return;
        double u = unit();
        layout(width, u);
        double max = Math.max(0, content - height);
        if (follow) scroll = max;
        scroll = Math.max(0, Math.min(scroll, max));

        c.save();
        c.translate(0, -scroll);
        double top = scroll, bottom = scroll + height;
        long now = System.currentTimeMillis();
        for (Entry e : transcript.entries()) {
            Layout l = layouts.get(e);
            if (l == null || l.y + l.h < top) continue;
            if (l.y > bottom) break;
            draw(c, e, l, u, e == flashed && now < flashUntil);
        }
        c.restore();

        if (content > height) { // the scroll bar
            double bar = BAR * u, track = height - 2 * u;
            double thumb = Math.max(24 * u, track * height / content);
            double at = u + (track - thumb) * (max == 0 ? 0 : scroll / max);
            c.fillRoundRect(width - bar - 2 * u, at, bar, thumb, bar / 2, onBar ? barActive : barColour);
        }
    }

    private void draw(Canvas c, Entry e, Layout l, double u, boolean flash) {
        iiG font = e.kind == Kind.USER || e.kind == Kind.AGENT ? body : small;
        double lineHeight = Canvas.lineHeight(font), ascent = Canvas.ascent(font);
        int ink;
        switch (e.kind) {
            case USER -> {
                c.fillRoundRect(l.x, l.y, l.w, l.h, RADIUS * u, flash ? flashColour : userFill);
                c.strokeRoundRect(l.x, l.y, l.w, l.h, RADIUS * u, Math.max(1, u), userStroke);
                ink = textColour;
            }
            case AGENT -> { c.fillRoundRect(l.x, l.y, l.w, l.h, RADIUS * u, flash ? flashColour : agentFill); ink = textColour; }
            case TOOL -> ink = toolColour;
            default -> ink = e.text.indexOf("[could not") >= 0 || e.text.indexOf("error") >= 0 ? errorColour : subtleColour;
        }
        boolean bubble = e.kind == Kind.USER || e.kind == Kind.AGENT;
        double x = l.x + (bubble ? BUBBLE_PAD_X * u : 0), y = l.y + (bubble ? BUBBLE_PAD_Y * u : 0);
        c.font(font);
        for (String line : l.lines) {
            if (!line.isEmpty()) c.text(x, y + ascent, line, ink);
            y += lineHeight;
        }
    }

    // ---- layout ---------------------------------------------------------------------------------------------------

    /** Wraps what changed and places every entry, top to bottom, at this width. */
    private void layout(double width, double u) {
        if (width == laidOutWidth && transcript.version() == laidOutVersion) return;
        laidOutWidth = width;
        laidOutVersion = transcript.version();
        double inner = Math.max(40 * u, width - 2 * PAD * u - BAR * u);
        double y = PAD * u;
        Map<Entry, Layout> keep = new IdentityHashMap<>();
        for (Entry e : transcript.entries()) {
            Layout l = layouts.get(e);
            if (l == null) l = new Layout();
            boolean bubble = e.kind == Kind.USER || e.kind == Kind.AGENT;
            iiG font = bubble ? body : small;
            double textWidth = bubble ? inner * SHARE - 2 * BUBBLE_PAD_X * u : inner;
            if (l.version != e.version || l.width != textWidth) {
                l.lines = wrap(font, e.text.toString().strip(), textWidth);
                l.version = e.version;
                l.width = textWidth;
            }
            double widest = 0;
            for (String line : l.lines) widest = Math.max(widest, Canvas.advance(font, line));
            double textHeight = l.lines.size() * Canvas.lineHeight(font);
            if (bubble) {
                l.w = Math.min(inner * SHARE, widest + 2 * BUBBLE_PAD_X * u);
                l.h = textHeight + 2 * BUBBLE_PAD_Y * u;
                l.x = e.kind == Kind.USER ? PAD * u + inner - l.w : PAD * u;
            } else {
                l.w = widest;
                l.h = textHeight;
                l.x = e.kind == Kind.NOTE ? PAD * u + Math.max(0, (inner - widest) / 2) : PAD * u + 4 * u;
            }
            l.y = y;
            y += l.h + GAP * u;
            keep.put(e, l);
        }
        layouts.clear();
        layouts.putAll(keep);
        content = y + PAD * u - GAP * u;
    }

    /** Greedy word wrap with the font's own advances; words wider than the line are split. */
    static List<String> wrap(iiG font, String text, double width) {
        List<String> out = new ArrayList<>();
        double space = Canvas.advance(font, " ");
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            double used = 0;
            for (String word : paragraph.split(" ", -1)) {
                double w = Canvas.advance(font, word);
                if (line.length() > 0 && used + space + w > width) {
                    out.add(line.toString());
                    line.setLength(0);
                    used = 0;
                }
                while (w > width && word.length() > 1) { // split a word longer than the line
                    int cut = fit(font, word, width - (line.length() > 0 ? used + space : 0));
                    if (cut == 0) {
                        if (line.length() > 0) { out.add(line.toString()); line.setLength(0); used = 0; continue; }
                        cut = 1;
                    }
                    if (line.length() > 0) line.append(' ');
                    line.append(word, 0, cut);
                    out.add(line.toString());
                    line.setLength(0);
                    used = 0;
                    word = word.substring(cut);
                    w = Canvas.advance(font, word);
                }
                if (line.length() > 0) { line.append(' '); used += space; }
                line.append(word);
                used += w;
            }
            out.add(line.toString());
        }
        return out;
    }

    /** How many leading characters of {@code word} fit in {@code width}. */
    private static int fit(iiG font, String word, double width) {
        int lo = 0, hi = word.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (Canvas.advance(font, word.substring(0, mid)) <= width) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    // ---- input -----------------------------------------------------------------------------------------------------

    private void scrollBy(double pixels) {
        double max = Math.max(0, content - height());
        scroll = Math.max(0, Math.min(max, scroll + pixels));
        follow = scroll >= max - 1;
        repaint();
    }

    @Override
    protected void wheel(double x, double y, int steps, int pixels, boolean horizontal, int modifiers) {
        if (horizontal) return;
        double u = unit();
        scrollBy(steps != 0 ? steps * 3 * 16 * u : pixels); // a wheel turned up gives negative steps: towards the start
    }

    @Override
    protected void press(double x, double y, int button, int clicks, int modifiers) {
        moved = 0;
        pressed = null;
        onBar = content > height() && x >= width() - (BAR + 4) * unit();
        if (onBar) { repaint(); return; }
        double cy = y + scroll;
        for (Entry e : transcript.entries()) {
            Layout l = layouts.get(e);
            if (l != null && cy >= l.y && cy <= l.y + l.h && x >= l.x && x <= l.x + l.w) { pressed = e; break; }
        }
    }

    @Override
    protected void drag(double x, double y, double dx, double dy) {
        moved += Math.abs(dx) + Math.abs(dy);
        if (onBar) {
            double h = height(), u = unit();
            double track = h - 2 * u, thumb = Math.max(24 * u, track * h / content);
            double max = Math.max(0, content - h);
            if (track > thumb) scrollBy(dy * max / (track - thumb));
        } else if (moved > 3 * unit()) {
            scrollBy(-dy); // drag the content, as on a touch screen
        }
    }

    @Override
    protected void release(double x, double y) {
        if (onBar) { onBar = false; repaint(); return; }
        if (pressed != null && moved <= 3 * unit()) copy(pressed);
        pressed = null;
    }

    private void copy(Entry e) {
        String text = e.text.toString().strip();
        try {
            kJ_().GwM().beI(text); // the window's clipboard: set its text
            com.bitwig.flt.app.Hvv.mEL().Anf("Copied to the clipboard"); // Bitwig's on-screen notification
        } catch (RuntimeException failure) {
            AgentPanels.LOG.log("could not copy", failure);
        }
        flashed = e;
        flashUntil = System.currentTimeMillis() + 250;
        repaint();
        drf().beI(this::repaint, 300L); // unflash
    }

    private static bitwig.obf.drf drf() { return bitwig.obf.drf.EO(); } // the application (UI thread timers)
}
