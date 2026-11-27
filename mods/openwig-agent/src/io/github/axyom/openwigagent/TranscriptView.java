package io.github.axyom.openwigagent;

import bitwig.obf.egG; // a theme colour
import bitwig.obf.ehb; // a theme font (face and size)
import com.bitwig.graphics.iiG; // a font at a size, for one window
import io.github.axyom.openwigagent.RichLayout.Box;
import io.github.axyom.openwigagent.RichLayout.Line;
import io.github.axyom.openwigagent.RichLayout.Run;
import io.github.axyom.openwigagent.Transcript.Entry;
import io.github.axyom.openwigagent.Transcript.Kind;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Agent panel's transcript, painted by the mod: the user's messages in accent bubbles on the right, the agent's
 * replies (Markdown: headings, lists, quotes, code) in bubbles on the left, tool calls as one compact line each,
 * status lines centred. Newest at the bottom; it follows new text unless the user scrolled up. Smooth wheel
 * scrolling, drag the bar on the right; drag over text to select it (double click: a word, triple: a message),
 * Ctrl+C copies, Ctrl+A selects everything, Escape clears.
 */
final class TranscriptView extends PaintedWidget implements RichLayout.Metrics {
    private static final double PAD = 8, GAP = 6, BUBBLE_X = 9, BUBBLE_Y = 6, RADIUS = 7, BAR = 5; // layout units
    private static final double SHARE = 0.88; // a bubble's widest share of the width
    private static final double SCROLL_TIME = 0.07; // smooth scrolling's time constant, seconds
    private static final Map<String, ehb> DERIVED = new HashMap<>(); // fonts made from the theme's, by name

    private final Transcript transcript;
    private final Runnable onChange = this::changed; // held here: the transcript holds its listeners weakly
    private final Map<Entry, Item> items = new IdentityHashMap<>();
    private final Map<Entry, Integer> order = new IdentityHashMap<>();
    private final iiG[] fonts = new iiG[RichLayout.ROLES];
    private double scroll, target;   // pixels from the top: shown, and where smooth scrolling is heading
    private boolean follow = true;   // stay at the bottom as text arrives
    private double content;          // total height, pixels
    private double laidOutWidth = -1;
    private int laidOutVersion = -1;
    private iiG laidOutFont;
    private Entry lastUser;
    // selection: from anchor to focus, each an entry and an offset in its plain text
    private Entry anchorEntry, focusEntry;
    private int anchorOffset, focusOffset;
    private boolean selecting, onBar, animating;

    /** One entry laid out at one width, and where it is (content coordinates, pixels). */
    private static final class Item {
        int version = -1;
        double textWidth = -1;
        RichLayout layout;
        String full;           // a tool line's whole text, when the line shows less
        double x, y, w, h;     // the bubble (or the text block)
        double tx, ty;         // where the text starts
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
        Entry newest = transcript.newest();
        if (newest != null && newest.kind == Kind.USER && newest != lastUser) { // the user sent something: show it
            lastUser = newest;
            follow = true;
        }
        if (transcript.entries().isEmpty()) clearSelection();
        repaint();
    }

    // ---- theme ----------------------------------------------------------------------------------------------------

    private int text, subtle, link, userFill, userStroke, agentFill, chip, codeFill, quoteBar, rule, error,
            selection, selectionIdle, bar, barActive;

    private boolean theme() {
        ehb body = named("Default"), bold = named("Bold");
        fonts[RichLayout.BODY] = font(body);
        if (fonts[RichLayout.BODY] == null) return false;
        fonts[RichLayout.BOLD] = font(bold);
        fonts[RichLayout.ITALIC] = font(named("Italic"));
        fonts[RichLayout.SMALL] = font(named("Small"));
        fonts[RichLayout.H3] = fonts[RichLayout.BOLD];
        float size = body.SQL(); // the theme's body size
        fonts[RichLayout.CODE] = font(derived("code", named("Console"), size));
        fonts[RichLayout.H1] = font(derived("h1", bold, size + 3));
        fonts[RichLayout.H2] = font(derived("h2", bold, size + 1.5f));
        text = textColour();
        subtle = alpha(text, 0.55);
        int accent = colour("Accent (default)", 0xFFFF8713); // Bitwig's orange
        int hitech = colour("Accent (hitech)", 0xFF3EBAFF);  // Bitwig's blue
        link = hitech;
        userFill = alpha(accent, 0.34);
        userStroke = alpha(accent, 0.55);
        agentFill = alpha(text, 0.10);   // a veil: lighter than a dark panel, darker than a light one
        chip = 0x38000000;               // inline code
        codeFill = 0x4C000000;           // code blocks
        quoteBar = alpha(text, 0.35);
        rule = alpha(text, 0.25);
        error = colour("Error Text", 0xC8FF3232);
        selection = alpha(hitech, 0.42);
        selectionIdle = alpha(text, 0.22);
        bar = colour("Modern Scrollbar Handle (inactive)", 0xFF5F5F5F);
        barActive = colour("Modern Scrollbar Handle (active)", 0xFF787878);
        return true;
    }

    private ehb named(String name) {
        try {
            ehb spec = jm_().jao().beI(name); // the theme's font table, by name
            return spec != null ? spec : fontSpec();
        } catch (RuntimeException e) {
            return fontSpec();
        }
    }

    /** A theme font's face at another size, made once. */
    private static ehb derived(String role, ehb face, float size) {
        String id = "openwig-agent " + role + " " + face.xDy() + " " + size;
        return DERIVED.computeIfAbsent(id, k -> ehb.beI(k, face.Bqk(), size)); // a font: name, face, size
    }

    private int colour(String name, int fallback) {
        try {
            egG c = mum().beI(name); // the theme's colour table, by name
            return c != null ? c.beI(this).ZRB() : fallback; // resolved for this widget, 0xAARRGGBB
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int alpha(int argb, double alpha) {
        int a = (int) Math.round(((argb >>> 24) & 0xFF) * alpha);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    // ---- RichLayout.Metrics -----------------------------------------------------------------------------------------

    @Override public double advance(int role, String s) { return fonts[role].beI(s).BBg(); } // advanceX
    @Override public double ascent(int role) { return fonts[role].beI().beI(); }
    @Override public double height(int role) { return fonts[role].beI().sCA(); }

    // ---- painting -----------------------------------------------------------------------------------------------------

    @Override
    protected void paint(Canvas c, double width, double height) {
        if (!theme()) return;
        double u = unit();
        layout(width, u);
        double max = Math.max(0, content - height);
        if (follow) {
            target = max;
            if (!animating) scroll = max; // new text: at once; a smooth scroll to the bottom finishes on its own
        }
        scroll = clamp(scroll, max);
        target = clamp(target, max);

        c.save();
        c.translate(0, -scroll);
        double top = scroll, bottom = scroll + height;
        int[] range = selectionRange();
        for (Entry e : transcript.entries()) {
            Item item = items.get(e);
            if (item == null || item.y + item.h < top) continue;
            if (item.y > bottom) break;
            draw(c, e, item, u, range);
        }
        c.restore();

        if (content > height) { // the scroll bar
            double w = BAR * u, track = height - 2 * u;
            double thumb = Math.max(24 * u, track * height / content);
            double at = u + (track - thumb) * (max == 0 ? 0 : scroll / max);
            c.fillRoundRect(width - w - 2 * u, at, w, thumb, w / 2, onBar ? barActive : bar);
        }
    }

    private void draw(Canvas c, Entry e, Item item, double u, int[] range) {
        switch (e.kind) {
            case USER -> {
                c.fillRoundRect(item.x, item.y, item.w, item.h, RADIUS * u, userFill);
                c.strokeRoundRect(item.x, item.y, item.w, item.h, RADIUS * u, Math.max(1, u), userStroke);
            }
            case AGENT -> c.fillRoundRect(item.x, item.y, item.w, item.h, RADIUS * u, agentFill);
            default -> { }
        }
        RichLayout l = item.layout;
        c.save();
        c.translate(item.tx, item.ty);
        for (Box b : l.boxes) {
            switch (b.kind()) {
                case RichLayout.CODE_BOX -> c.fillRoundRect(b.x(), b.y(), b.w(), b.h(), 4 * u, codeFill);
                case RichLayout.QUOTE_BAR -> c.fillRect(b.x(), b.y(), b.w(), b.h(), quoteBar);
                default -> c.fillRect(b.x(), b.y(), b.w(), b.h(), rule);
            }
        }
        int index = order.getOrDefault(e, -1);
        if (range != null && index >= range[0] && index <= range[2]) { // the selection, behind the text
            int a = index == range[0] ? range[1] : 0, b = index == range[2] ? range[3] : l.plain.length();
            highlight(c, l, a, b, focused() ? selection : selectionIdle);
        }
        int ink = e.kind == Kind.TOOL ? subtle
                : e.kind == Kind.NOTE ? (e.text.indexOf("[could not") >= 0 || e.text.indexOf("error") >= 0 ? error : subtle)
                : text;
        for (Line line : l.lines) {
            double base = line.y + line.ascent;
            for (Run r : line.runs) {
                if (r.text().isEmpty()) continue;
                boolean code = (r.style() & Markdown.CODE) != 0;
                if (code && (r.style() & RichLayout.CODE_BLOCK) == 0 && !r.text().isBlank()) {
                    c.fillRoundRect(r.x() - 2 * u, line.y + 0.5 * u, r.w() + 4 * u, line.h - u, 3 * u, chip);
                }
                c.font(fonts[r.role()]);
                int colour = (r.style() & Markdown.LINK) != 0 ? link : ink;
                c.text(r.x(), base, r.text(), colour);
                if ((r.style() & Markdown.LINK) != 0) c.fillRect(r.x(), base + 1.5 * u, r.w(), Math.max(1, u * 0.8), alpha(link, 0.7));
            }
        }
        c.restore();
    }

    /** The selected part [a, b) of one entry's plain text, as rectangles behind its lines. */
    private void highlight(Canvas c, RichLayout l, int a, int b, int colour) {
        if (b <= a) return;
        double u = unit();
        for (Line line : l.lines) {
            int start = line.start, end = start + line.text.length();
            if (b <= start || a > end) continue;
            if (a == end && b <= end) continue;
            double[] stops = l.stops(line);
            int la = Math.max(a, start) - start, lb = Math.min(b, end) - start;
            double x0 = stops[la], x1 = stops[lb];
            if (b > end) x1 += 3 * u; // the selection goes on past this line's end
            c.fillRect(x0, line.y, Math.max(x1 - x0, u), line.h, colour);
        }
    }

    // ---- layout ---------------------------------------------------------------------------------------------------

    /** Lays out what changed and places every entry, top to bottom, at this width. */
    private void layout(double width, double u) {
        iiG font = fonts[RichLayout.BODY];
        if (font != laidOutFont) { items.clear(); laidOutFont = font; laidOutWidth = -1; } // the theme or scale changed
        if (width == laidOutWidth && transcript.version() == laidOutVersion) return;
        laidOutWidth = width;
        laidOutVersion = transcript.version();
        double inner = Math.max(40 * u, width - 2 * PAD * u - BAR * u);
        double y = PAD * u;
        Map<Entry, Item> keep = new IdentityHashMap<>();
        order.clear();
        int index = 0;
        for (Entry e : transcript.entries()) {
            order.put(e, index++);
            Item item = items.get(e);
            if (item == null) item = new Item();
            boolean bubble = e.kind == Kind.USER || e.kind == Kind.AGENT;
            double textWidth = bubble ? inner * SHARE - 2 * BUBBLE_X * u : inner - 4 * u;
            if (item.version != e.version || item.textWidth != textWidth) {
                lay(e, item, textWidth);
                item.version = e.version;
                item.textWidth = textWidth;
            }
            RichLayout l = item.layout;
            if (bubble) {
                item.w = Math.min(inner * SHARE, l.width + 2 * BUBBLE_X * u);
                item.h = l.height + 2 * BUBBLE_Y * u;
                item.x = e.kind == Kind.USER ? PAD * u + inner - item.w : PAD * u;
                item.tx = item.x + BUBBLE_X * u;
                item.ty = y + BUBBLE_Y * u;
            } else {
                item.w = l.width;
                item.h = l.height;
                item.x = e.kind == Kind.NOTE ? PAD * u + Math.max(0, (inner - l.width) / 2) : PAD * u + 4 * u;
                item.tx = item.x;
                item.ty = y;
            }
            item.y = y;
            y += item.h + GAP * u * (e.kind == Kind.TOOL ? 0.4 : 1);
            keep.put(e, item);
        }
        items.clear();
        items.putAll(keep);
        content = y + PAD * u - GAP * u;
    }

    private void lay(Entry e, Item item, double textWidth) {
        String s = e.text.toString().strip();
        item.full = null;
        switch (e.kind) {
            case AGENT -> item.layout = RichLayout.of(Markdown.parse(s), textWidth, RichLayout.BODY, this);
            case USER -> item.layout = RichLayout.of(Markdown.plain(s), textWidth, RichLayout.BODY, this);
            case TOOL -> { // one line: the call, cut to the width
                String shown = "› " + s;
                if (advance(RichLayout.SMALL, shown) > textWidth) {
                    int lo = 0, hi = shown.length();
                    while (lo < hi) {
                        int mid = (lo + hi + 1) / 2;
                        if (advance(RichLayout.SMALL, shown.substring(0, mid) + "…") <= textWidth) lo = mid; else hi = mid - 1;
                    }
                    shown = shown.substring(0, lo) + "…";
                    item.full = "› " + s;
                }
                item.layout = RichLayout.of(Markdown.plain(shown), Double.MAX_VALUE / 4, RichLayout.SMALL, this);
            }
            default -> item.layout = RichLayout.of(Markdown.plain(s), textWidth, RichLayout.SMALL, this);
        }
    }

    // ---- selection ------------------------------------------------------------------------------------------------

    /** {startOrder, startOffset, endOrder, endOffset}, or null when nothing is selected. */
    private int[] selectionRange() {
        if (anchorEntry == null || focusEntry == null) return null;
        Integer ao = order.get(anchorEntry), fo = order.get(focusEntry);
        if (ao == null || fo == null) return null;
        int a = clampOffset(anchorEntry, anchorOffset), f = clampOffset(focusEntry, focusOffset);
        int[] r = ao < fo || ao.equals(fo) && a <= f ? new int[] {ao, a, fo, f} : new int[] {fo, f, ao, a};
        return r[0] == r[2] && r[1] == r[3] ? null : r;
    }

    private int clampOffset(Entry e, int offset) {
        Item item = items.get(e);
        return item == null ? 0 : Math.max(0, Math.min(offset, item.layout.plain.length()));
    }

    private String selectedText() {
        int[] r = selectionRange();
        if (r == null) return "";
        StringBuilder out = new StringBuilder();
        List<Entry> entries = transcript.entries();
        for (int i = r[0]; i <= r[2] && i < entries.size(); i++) {
            Item item = items.get(entries.get(i));
            if (item == null) continue;
            String plain = item.layout.plain;
            int a = i == r[0] ? r[1] : 0, b = i == r[2] ? r[3] : plain.length();
            if (b <= a) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(item.full != null && a == 0 && b >= plain.length() ? item.full : plain.substring(a, b));
        }
        return out.toString();
    }

    private void clearSelection() {
        anchorEntry = focusEntry = null;
        selecting = false;
    }

    /** The entry and plain-text offset under (x, y) (content coordinates); null when there are no entries. */
    private Object[] hit(double x, double y) {
        Entry found = null;
        double u = unit();
        for (Entry e : transcript.entries()) {
            Item item = items.get(e);
            if (item == null) continue;
            found = e;
            if (y < item.y + item.h + GAP * u / 2) break;
        }
        if (found == null) return null;
        Item item = items.get(found);
        return new Object[] {found, item.layout.offsetAt(x - item.tx, y - item.ty)};
    }

    // ---- input -----------------------------------------------------------------------------------------------------

    @Override protected boolean focusable() { return true; }
    @Override protected void focusChanged(boolean focused) { repaint(); }

    @Override
    protected Cursor cursor(double x, double y) {
        if (content > height() && x >= width() - (BAR + 4) * unit()) return Cursor.POINTER;
        double cy = y + scroll;
        for (Entry e : transcript.entries()) {
            Item item = items.get(e);
            if (item == null || cy < item.y || cy > item.y + item.h) continue;
            return item.layout.overText(x - item.tx, cy - item.ty) ? Cursor.TEXT : Cursor.POINTER;
        }
        return Cursor.POINTER;
    }

    private void scrollTo(double pixels, boolean smooth) {
        double max = Math.max(0, content - height());
        target = clamp(pixels, max);
        follow = target >= max - 1;
        if (!smooth) scroll = target;
        if (smooth && Math.abs(target - scroll) > 0.5) {
            animating = true;
            animate();
        }
        repaint();
    }

    @Override
    protected boolean frame(double dt) { // smooth scrolling towards the target
        double step = (target - scroll) * (1 - Math.exp(-dt / SCROLL_TIME));
        scroll = Math.abs(target - scroll) < 0.5 ? target : scroll + step;
        repaint();
        animating = scroll != target;
        return animating;
    }

    private static double clamp(double v, double max) {
        return Math.max(0, Math.min(max, v));
    }

    @Override
    protected void wheel(double x, double y, int steps, int pixels, boolean horizontal, int modifiers) {
        if (horizontal) return;
        double line = fonts[RichLayout.BODY] == null ? 16 * unit() : height(RichLayout.BODY);
        scrollTo(target + (steps != 0 ? steps * 3 * line : pixels), steps != 0); // a wheel turned up: negative steps
    }

    @Override
    protected void press(double x, double y, int button, int clicks, int modifiers) {
        requestFocus();
        onBar = content > height() && x >= width() - (BAR + 4) * unit();
        if (onBar) { repaint(); return; }
        if (button != 1) return;
        Object[] at = hit(x, y + scroll);
        if (at == null) { clearSelection(); repaint(); return; }
        Entry e = (Entry) at[0];
        int offset = (Integer) at[1];
        if ((modifiers & SHIFT) != 0 && anchorEntry != null) {
            focusEntry = e;
            focusOffset = offset;
        } else if (clicks == 2) { // a word
            int[] word = items.get(e).layout.wordAt(offset);
            anchorEntry = focusEntry = e;
            anchorOffset = word[0];
            focusOffset = word[1];
        } else if (clicks >= 3) { // the whole message
            anchorEntry = focusEntry = e;
            anchorOffset = 0;
            focusOffset = items.get(e).layout.plain.length();
        } else {
            anchorEntry = focusEntry = e;
            anchorOffset = focusOffset = offset;
        }
        selecting = clicks == 1;
        repaint();
    }

    @Override
    protected void drag(double x, double y, double dx, double dy) {
        if (onBar) {
            double h = height(), u = unit();
            double track = h - 2 * u, thumb = Math.max(24 * u, track * h / content);
            double max = Math.max(0, content - h);
            if (track > thumb) scrollTo(target + dy * max / (track - thumb), false);
            return;
        }
        if (!selecting) return;
        if (y < 0) scrollTo(target + y * 0.5, false);             // dragged above: scroll up
        else if (y > height()) scrollTo(target + (y - height()) * 0.5, false);
        Object[] at = hit(x, Math.max(0, Math.min(height(), y)) + scroll);
        if (at != null) {
            focusEntry = (Entry) at[0];
            focusOffset = (Integer) at[1];
        }
        repaint();
    }

    @Override
    protected void release(double x, double y) {
        selecting = false;
        if (onBar) { onBar = false; repaint(); }
    }

    @Override
    protected boolean key(Key key, int modifiers) {
        double page = Math.max(40 * unit(), height() - 40 * unit());
        double line = height(RichLayout.BODY) * 3;
        switch (key) {
            case COPY -> {
                String s = selectedText();
                if (s.isEmpty()) return false; // nothing selected: Bitwig's own Copy
                clipboard(s);
                com.bitwig.flt.app.Hvv.mEL().Anf("Copied to the clipboard"); // Bitwig's on-screen notification
                return true;
            }
            case SELECT_ALL -> {
                List<Entry> entries = transcript.entries();
                if (entries.isEmpty()) return true;
                anchorEntry = entries.get(0);
                anchorOffset = 0;
                focusEntry = entries.get(entries.size() - 1);
                Item last = items.get(focusEntry);
                focusOffset = last == null ? 0 : last.layout.plain.length();
                repaint();
                return true;
            }
            case ESCAPE -> {
                if (selectionRange() == null) return false;
                clearSelection();
                repaint();
                return true;
            }
            case UP -> scrollTo(target - line, true);
            case DOWN -> scrollTo(target + line, true);
            case PAGE_UP -> scrollTo(target - page, true);
            case PAGE_DOWN -> scrollTo(target + page, true);
            case HOME -> scrollTo(0, true);
            case END -> scrollTo(Double.MAX_VALUE, true);
            default -> { return false; }
        }
        return true;
    }
}
