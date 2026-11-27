package io.github.axyom.openwigagent;

import io.github.axyom.openwigagent.Markdown.Block;
import io.github.axyom.openwigagent.Markdown.Kind;
import io.github.axyom.openwigagent.Markdown.Span;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown blocks laid out at a width: lines of styled runs (word wrap, code wrapped by character, hanging list
 * indents), the backgrounds of code blocks, quotes and rules, and the plain text a selection copies. Positions are
 * pixels from the layout's top left. Fonts are measured through {@link Metrics}, so this runs without Bitwig.
 */
final class RichLayout {
    /** Font roles: what a run is drawn with. */
    static final int BODY = 0, BOLD = 1, ITALIC = 2, CODE = 3, H1 = 4, H2 = 5, H3 = 6, SMALL = 7, ROLES = 8;
    /** Background kinds. */
    static final int CODE_BOX = 0, QUOTE_BAR = 1, RULE = 2;
    /** A run's style bit (beside Markdown's): it is part of a fenced code block, which has its own background. */
    static final int CODE_BLOCK = 16;

    interface Metrics {
        double advance(int role, String text);
        double ascent(int role);
        double height(int role);
        double unit(); // pixels per layout unit
    }

    /** Text in one role and inline style, at x on its line. */
    record Run(int role, int style, String text, double x, double w) { }

    record Box(int kind, double x, double y, double w, double h) { }

    static final class Line {
        final List<Run> runs = new ArrayList<>();
        double y, h, ascent;  // top, height, baseline offset
        double left, right;   // text extent (a list marker included)
        double textStart;     // where the line's wrapped text starts (after a list marker)
        int start;            // offset of the line's text in the plain text
        String text = "";     // what the line shows
        String joiner = "";   // what follows it in the plain text
        double[] stops;       // x of every character boundary, measured on demand
        final StringBuilder sb = new StringBuilder();
    }

    final List<Line> lines = new ArrayList<>();
    final List<Box> boxes = new ArrayList<>();
    String plain = "";
    double width, height;

    private final Metrics m;
    private final double max;
    private final StringBuilder plainText = new StringBuilder();
    private Line line;
    private double y;

    private RichLayout(Metrics m, double max) {
        this.m = m;
        this.max = Math.max(1, max);
    }

    /** Lays out {@code blocks} within {@code maxWidth}; {@code base} is the role of plain text (BODY or SMALL). */
    static RichLayout of(List<Block> blocks, double maxWidth, int base, Metrics m) {
        RichLayout l = new RichLayout(m, maxWidth);
        double u = m.unit();
        for (int b = 0; b < blocks.size(); b++) {
            Block block = blocks.get(b);
            boolean listItem = block.kind() == Kind.BULLET || block.kind() == Kind.NUMBER;
            if (b > 0) {
                Block prev = blocks.get(b - 1);
                boolean sameList = listItem && (prev.kind() == Kind.BULLET || prev.kind() == Kind.NUMBER);
                l.y += sameList ? 2 * u : 0.55 * m.height(base);
                l.joinLast(sameList ? "\n" : "\n\n");
            }
            l.block(block, base, u);
        }
        l.finishLine();
        l.plain = l.plainText.toString();
        l.height = l.y;
        return l;
    }

    private void block(Block block, int base, double u) {
        switch (block.kind()) {
            case HEADING -> {
                int role = block.level() <= 1 ? H1 : block.level() == 2 ? H2 : H3;
                if (base == SMALL) role = BOLD;
                for (List<Span> hard : block.lines()) flow(hard, 0, 0, role, base, true);
            }
            case BULLET, NUMBER -> {
                double indent = block.level() * 14 * u;
                String marker = block.marker() + " ";
                double markerWidth = Math.max(12 * u, m.advance(base, marker));
                boolean first = true;
                for (List<Span> hard : block.lines()) {
                    if (!first && line != null) joinLast("\n");
                    newLine(first ? indent : indent + markerWidth);
                    if (first) {
                        add(base, 0, marker, indent, m.advance(base, marker));
                        line.right = indent + markerWidth;
                    }
                    line.textStart = indent + markerWidth;
                    flowInto(hard, indent + markerWidth, base, base);
                    first = false;
                }
            }
            case QUOTE -> {
                double top = y, indent = 12 * u;
                for (List<Span> hard : block.lines()) flow(hard, indent, indent, ITALIC, base, true);
                finishLine();
                boxes.add(new Box(QUOTE_BAR, 2 * u, top, 2.5 * u, y - top));
            }
            case CODE -> {
                double pad = 6 * u, top = y;
                y += pad;
                for (List<Span> hard : block.lines()) code(hard.isEmpty() ? "" : hard.get(0).text(), pad);
                finishLine();
                y += pad;
                boxes.add(new Box(CODE_BOX, 0, top, max, y - top));
                width = max;
            }
            case RULE -> {
                finishLine();
                double h = 0.8 * m.height(base);
                boxes.add(new Box(RULE, 0, y + h / 2, max, Math.max(1, u)));
                y += h;
            }
            default -> {
                for (List<Span> hard : block.lines()) flow(hard, 0, 0, base, base, true);
            }
        }
    }

    // ---- lines ----------------------------------------------------------------------------------------------------

    private void newLine(double x) {
        finishLine();
        line = new Line();
        line.y = y;
        line.left = x;
        line.right = x;
        line.textStart = x;
        line.start = plainText.length();
    }

    /** Closes the open line: its height, its text, and its place in the plain text (its joiner comes later). */
    private void finishLine() {
        if (line == null) return;
        double h = 0, a = 0;
        for (Run r : line.runs) { h = Math.max(h, m.height(r.role())); a = Math.max(a, m.ascent(r.role())); }
        if (line.runs.isEmpty()) { h = m.height(BODY); a = m.ascent(BODY); }
        line.h = h;
        line.ascent = a;
        line.text = line.sb.toString();
        plainText.append(line.text);
        lines.add(line);
        width = Math.max(width, line.right);
        y += h;
        line = null;
    }

    /** The joiner of the last finished line (what separates it from the next one when copied). */
    private void joinLast(String joiner) {
        finishLine();
        if (lines.isEmpty()) return;
        Line last = lines.get(lines.size() - 1);
        if (plainText.length() > last.start + last.text.length()) { // already joined (a rule came between): replace
            plainText.setLength(last.start + last.text.length());
        }
        last.joiner = joiner;
        plainText.append(joiner);
    }

    private void add(int role, int style, String text, double x, double w) {
        List<Run> runs = line.runs;
        Run last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
        if (last != null && last.role() == role && last.style() == style && Math.abs(last.x() + last.w() - x) < 0.01) {
            runs.set(runs.size() - 1, new Run(role, style, last.text() + text, last.x(), last.w() + w));
        } else {
            runs.add(new Run(role, style, text, x, w));
        }
        line.sb.append(text);
        line.right = Math.max(line.right, x + w);
    }

    /** One hard line of spans on a fresh line, wrapped at words. */
    private void flow(List<Span> spans, double firstX, double restX, int role, int base, boolean fresh) {
        if (fresh) {
            if (line != null) joinLast("\n");
            newLine(firstX);
        }
        flowInto(spans, restX, role, base);
    }

    /** Spans from the open line's end; wrapped lines start at {@code restX}. */
    private void flowInto(List<Span> spans, double restX, int blockRole, int base) {
        double x = line.right;
        boolean pendingSpace = false;
        int spaceRole = blockRole;
        for (Span span : spans) {
            int role = role(span.style(), blockRole, base);
            String t = span.text();
            int i = 0;
            while (i < t.length()) {
                if (t.charAt(i) == ' ') {
                    pendingSpace = x > lineStart();
                    spaceRole = role;
                    i++;
                    continue;
                }
                int j = i;
                while (j < t.length() && t.charAt(j) != ' ') j++;
                String word = t.substring(i, j);
                i = j;
                double space = pendingSpace ? m.advance(spaceRole, " ") : 0;
                double w = m.advance(role, word);
                if (x > lineStart() && x + space + w > max) { // wrap before the word
                    wrap(pendingSpace ? " " : "", restX);
                    x = restX;
                    space = 0;
                } else if (pendingSpace) {
                    add(spaceRole, span.style(), " ", x, space);
                    x += space;
                }
                pendingSpace = false;
                while (x + w > max && word.length() > 1) { // a word wider than the line: split it
                    int cut = fit(role, word, max - x);
                    if (cut == 0) {
                        if (x > lineStart()) { wrap("", restX); x = restX; continue; }
                        cut = 1;
                    }
                    String piece = word.substring(0, cut);
                    add(role, span.style(), piece, x, m.advance(role, piece));
                    wrap("", restX);
                    x = restX;
                    word = word.substring(cut);
                    w = m.advance(role, word);
                }
                add(role, span.style(), word, x, w);
                x += w;
            }
        }
    }

    /** Where the open line's text starts: a position past it means the line already has text. */
    private double lineStart() {
        return line.textStart;
    }

    private void wrap(String joiner, double nextX) {
        Line done = line;
        finishLine();
        done.joiner = joiner;
        plainText.append(joiner);
        newLine(nextX);
    }

    /** A code line: kept as typed, wrapped at any character. */
    private void code(String text, double pad) {
        if (line != null) joinLast("\n");
        newLine(pad);
        double room = max - 2 * pad;
        String rest = text;
        while (true) {
            int cut = rest.isEmpty() ? 0 : Math.max(1, fit(CODE, rest, room));
            String piece = rest.substring(0, Math.min(cut, rest.length()));
            add(CODE, Markdown.CODE | CODE_BLOCK, piece, pad, m.advance(CODE, piece));
            rest = rest.substring(piece.length());
            if (rest.isEmpty()) break;
            wrap("", pad);
        }
    }

    private int fit(int role, String word, double room) {
        int lo = 0, hi = word.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (m.advance(role, word.substring(0, mid)) <= room) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** The role a span is drawn in: code wins, then bold and italic; headings and small text keep their own. */
    private static int role(int style, int blockRole, int base) {
        if ((style & Markdown.CODE) != 0) return CODE;
        if (blockRole == H1 || blockRole == H2 || blockRole == H3 || blockRole == SMALL) return blockRole;
        if ((style & Markdown.BOLD) != 0) return BOLD;
        if ((style & Markdown.ITALIC) != 0) return ITALIC;
        return blockRole == ITALIC ? ITALIC : base;
    }

    // ---- positions --------------------------------------------------------------------------------------------------

    /** The x of every character boundary of {@code line}, measured once. */
    double[] stops(Line line) {
        if (line.stops != null) return line.stops;
        double[] s = new double[line.text.length() + 1];
        s[0] = line.runs.isEmpty() ? line.left : line.runs.get(0).x();
        int k = 0;
        for (Run r : line.runs) {
            for (int j = 1; j <= r.text().length(); j++) s[k + j] = r.x() + m.advance(r.role(), r.text().substring(0, j));
            k += r.text().length();
        }
        line.stops = s;
        return s;
    }

    /** The line nearest to {@code y} (index), or -1 when there are no lines. */
    int lineAt(double y) {
        if (lines.isEmpty()) return -1;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (y < l.y + l.h) return i;
        }
        return lines.size() - 1;
    }

    /** The plain-text offset nearest to (x, y). */
    int offsetAt(double x, double y) {
        int i = lineAt(y);
        if (i < 0) return 0;
        Line l = lines.get(i);
        double[] s = stops(l);
        int best = 0;
        for (int k = 1; k < s.length; k++) if (Math.abs(s[k] - x) < Math.abs(s[best] - x)) best = k;
        return l.start + best;
    }

    /** Whether (x, y) is over text (for the text cursor). */
    boolean overText(double x, double y) {
        int i = lineAt(y);
        if (i < 0) return false;
        Line l = lines.get(i);
        return y >= l.y && y < l.y + l.h && x >= l.left - 2 && x <= Math.max(l.right, l.left + 8) + 2;
    }

    /** The plain-text range of the word at {@code offset}. */
    int[] wordAt(int offset) {
        int a = Math.max(0, Math.min(offset, plain.length())), b = a;
        while (a > 0 && Character.isLetterOrDigit(plain.charAt(a - 1))) a--;
        while (b < plain.length() && Character.isLetterOrDigit(plain.charAt(b))) b++;
        if (a == b && b < plain.length()) b++;
        return new int[] {a, b};
    }
}
