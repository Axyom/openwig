package io.github.axyom.openwigagent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small Markdown reader for the agent's replies: paragraphs, headings, bullet and numbered lists (nested by
 * indentation), quotes, fenced code blocks and rules; bold, italic, inline code and links inside them. A single
 * newline is a line break, as in chat. Markup that isn't closed yet (a reply still streaming) stays literal.
 */
final class Markdown {
    static final int BOLD = 1, ITALIC = 2, CODE = 4, LINK = 8;

    enum Kind { PARAGRAPH, HEADING, BULLET, NUMBER, QUOTE, CODE, RULE }

    /** Text in one inline style. */
    record Span(String text, int style) { }

    /** One block: its kind, heading level or list depth, list marker, and its lines (each a list of spans). */
    record Block(Kind kind, int level, String marker, List<List<Span>> lines) { }

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$");
    private static final Pattern RULE = Pattern.compile("^ {0,3}([-*_])(?:[ \t]*\\1){2,}[ \t]*$");
    private static final Pattern ITEM = Pattern.compile("^([ \t]*)([-*+]|\\d{1,9}[.)])[ \t]+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}>[ ]?(.*)$");
    private static final String PUNCT = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    private Markdown() { }

    /** Plain text as one paragraph per line group, with no markup read (the user's own messages). */
    static List<Block> plain(String text) {
        List<List<Span>> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) lines.add(List.of(new Span(line, 0)));
        return List.of(new Block(Kind.PARAGRAPH, 0, "", lines));
    }

    static List<Block> parse(String text) {
        String[] lines = text.replace("\r", "").split("\n", -1);
        List<Block> out = new ArrayList<>();
        int i = 0, n = lines.length;
        while (i < n) {
            String line = lines[i];
            if (line.isBlank()) { i++; continue; }
            Matcher m;
            if ((m = FENCE.matcher(line)).matches()) {
                String fence = m.group(1);
                List<List<Span>> code = new ArrayList<>();
                for (i++; i < n && !closesFence(lines[i], fence); i++) code.add(List.of(new Span(detab(lines[i]), CODE)));
                if (i < n) i++; // the closing fence
                if (code.isEmpty()) code.add(List.of(new Span("", CODE)));
                out.add(new Block(Kind.CODE, 0, "", code));
            } else if ((m = HEADING.matcher(line)).matches()) {
                out.add(new Block(Kind.HEADING, m.group(1).length(), "", List.of(inline(m.group(2)))));
                i++;
            } else if (RULE.matcher(line).matches()) {
                out.add(new Block(Kind.RULE, 0, "", List.of()));
                i++;
            } else if ((m = ITEM.matcher(line)).matches()) {
                int indent = width(m.group(1));
                String marker = m.group(2);
                boolean numbered = Character.isDigit(marker.charAt(0));
                List<List<Span>> content = new ArrayList<>();
                content.add(inline(m.group(3)));
                for (i++; i < n; i++) { // indented lines continue the item
                    String next = lines[i];
                    if (next.isBlank() || ITEM.matcher(next).matches() || FENCE.matcher(next).matches()) break;
                    if (width(leading(next)) <= indent) break;
                    content.add(inline(next.strip()));
                }
                out.add(new Block(numbered ? Kind.NUMBER : Kind.BULLET, Math.min(4, indent / 2),
                        numbered ? marker.substring(0, marker.length() - 1) + "." : "•", content));
            } else if (QUOTE.matcher(line).matches()) {
                List<List<Span>> quoted = new ArrayList<>();
                for (; i < n && (m = QUOTE.matcher(lines[i])).matches(); i++) quoted.add(inline(m.group(1)));
                out.add(new Block(Kind.QUOTE, 0, "", quoted));
            } else {
                List<List<Span>> paragraph = new ArrayList<>();
                for (; i < n; i++) {
                    String next = lines[i];
                    if (next.isBlank() || FENCE.matcher(next).matches() || HEADING.matcher(next).matches()
                            || ITEM.matcher(next).matches() || QUOTE.matcher(next).matches()
                            || RULE.matcher(next).matches() && !paragraph.isEmpty()) break;
                    paragraph.add(inline(next.strip()));
                }
                out.add(new Block(Kind.PARAGRAPH, 0, "", paragraph));
            }
        }
        return out;
    }

    private static boolean closesFence(String line, String fence) {
        String t = line.strip();
        if (t.length() < fence.length()) return false;
        for (int k = 0; k < t.length(); k++) if (t.charAt(k) != fence.charAt(0)) return false;
        return true;
    }

    private static String leading(String s) {
        int k = 0;
        while (k < s.length() && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) k++;
        return s.substring(0, k);
    }

    private static int width(String indent) {
        int w = 0;
        for (int k = 0; k < indent.length(); k++) w += indent.charAt(k) == '\t' ? 4 : 1;
        return w;
    }

    private static String detab(String s) {
        return s.indexOf('\t') < 0 ? s : s.replace("\t", "    ");
    }

    // ---- inline ---------------------------------------------------------------------------------------------------

    /** Bold, italic, inline code and links in one line of text; anything unclosed stays as typed. */
    static List<Span> inline(String s) {
        List<Span> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int style = 0, i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && PUNCT.indexOf(s.charAt(i + 1)) >= 0) {
                buf.append(s.charAt(i + 1));
                i += 2;
            } else if (c == '`') {
                int k = run(s, i, '`');
                int close = findRun(s, i + k, '`', k);
                if (close < 0) { buf.append(s, i, i + k); i += k; continue; }
                flush(out, buf, style);
                String code = s.substring(i + k, close);
                if (code.length() >= 2 && code.startsWith(" ") && code.endsWith(" ") && !code.isBlank())
                    code = code.substring(1, code.length() - 1);
                out.add(new Span(code, style | CODE));
                i = close + k;
            } else if (c == '*' || c == '_') {
                int k = run(s, i, c);
                char before = i > 0 ? s.charAt(i - 1) : ' ', after = i + k < n ? s.charAt(i + k) : ' ';
                boolean canOpen = !Character.isWhitespace(after), canClose = !Character.isWhitespace(before);
                if (c == '_' && Character.isLetterOrDigit(before) && Character.isLetterOrDigit(after)) { // snake_case
                    buf.append(s, i, i + k);
                    i += k;
                    continue;
                }
                int used = 0;
                if (canClose && (style & BOLD) != 0 && k - used >= 2) { flush(out, buf, style); style &= ~BOLD; used += 2; }
                if (canClose && (style & ITALIC) != 0 && k - used >= 1) { flush(out, buf, style); style &= ~ITALIC; used += 1; }
                if (used == 0 && canOpen) {
                    if (k >= 2 && closes(s, i + k, c, 2)) { flush(out, buf, style); style |= BOLD; used += 2; }
                    if (k - used >= 1 && closes(s, i + k, c, 1)) { flush(out, buf, style); style |= ITALIC; used += 1; }
                }
                buf.append(s, i + used, i + k); // whatever wasn't markup
                i += k;
            } else if (c == '[') {
                int close = s.indexOf(']', i + 1);
                int end = close > i && close + 1 < n && s.charAt(close + 1) == '(' ? s.indexOf(')', close + 2) : -1;
                if (end < 0) { buf.append(c); i++; continue; }
                flush(out, buf, style);
                out.add(new Span(s.substring(i + 1, close), style | LINK));
                i = end + 1;
            } else {
                buf.append(c);
                i++;
            }
        }
        flush(out, buf, style);
        if (out.isEmpty()) out.add(new Span("", 0));
        return out;
    }

    private static void flush(List<Span> out, StringBuilder buf, int style) {
        if (buf.length() == 0) return;
        out.add(new Span(buf.toString(), style));
        buf.setLength(0);
    }

    private static int run(String s, int i, char c) {
        int k = i;
        while (k < s.length() && s.charAt(k) == c) k++;
        return k - i;
    }

    /** Where a run of exactly {@code k} {@code c} starts at or after {@code from}, or -1. */
    private static int findRun(String s, int from, char c, int k) {
        for (int j = from; j < s.length(); ) {
            if (s.charAt(j) != c) { j++; continue; }
            int r = run(s, j, c);
            if (r == k) return j;
            j += r;
        }
        return -1;
    }

    /** Whether a closing run of at least {@code k} {@code c} (after a non-space) comes later. */
    private static boolean closes(String s, int from, char c, int k) {
        for (int j = from; j < s.length(); ) {
            if (s.charAt(j) != c) { j++; continue; }
            int r = run(s, j, c);
            if (r >= k && j > 0 && !Character.isWhitespace(s.charAt(j - 1))) return true;
            j += r;
        }
        return false;
    }
}
