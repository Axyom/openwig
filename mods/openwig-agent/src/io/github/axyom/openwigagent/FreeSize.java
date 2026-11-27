package io.github.axyom.openwigagent;

import bitwig.obf.HLP; // a size policy: a widget's minimum, preferred and maximum along one axis
import bitwig.obf.cjx; // a fixed size (layout units), clamped by a base policy
import bitwig.obf.wQ1; // a widget's own sizes, as a size policy reads them

/**
 * A size policy with no limits of its own, so a fixed size on it is exactly that size whatever the widget's own
 * minimum and maximum are (as Framework: Plug-in Panels sizes its cells).
 */
final class FreeSize implements HLP {
    private static final FreeSize FREE = new FreeSize();

    /** Exactly {@code n} layout units. */
    static HLP exactly(long n) {
        return new cjx(n, FREE);
    }

    @Override public long beI(wQ1 widget) { return 0; }              // preferred
    @Override public long Gef(wQ1 widget) { return 0; }              // minimum
    @Override public long sCA(wQ1 widget) { return Long.MAX_VALUE; } // maximum
}
