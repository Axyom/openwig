package io.github.axyom.openwigagent;

import bitwig.obf.dLf; // a mouse button pressed on the widget
import bitwig.obf.dLg; // a mouse button released
import bitwig.obf.dLk; // the mouse moved (during a press: a drag)
import bitwig.obf.dLl; // a mouse wheel (or trackpad scroll) event
import bitwig.obf.doI; // a widget, as Bitwig types it
import bitwig.obf.dou; // the window a widget is in
import bitwig.obf.dtt; // what a press does until the button is released (Bitwig asks the widget for one per press)
import bitwig.obf.dty; // what a wheel turn over the widget does
import bitwig.obf.dwF; // a widget
import bitwig.obf.ehb; // a font, as the theme names it (face and size)
import bitwig.obf.eqH; // a wheel event's unit: steps (notches) or pixels
import bitwig.obf.eqv; // the modifier keys held
import bitwig.obf.eqw; // a mouse button
import com.bitwig.base.geom.Dr3; // horizontal or vertical
import com.bitwig.base.geom.uXa; // a size (width, height), in pixels
import com.bitwig.graphics.iiG; // a font at a size, for one window
import com.bitwig.graphics.ijY; // Bitwig's drawing surface

/**
 * A Bitwig widget that the mod paints itself: Bitwig lays it out (it takes the space its group gives it), asks it to
 * paint into a {@link Canvas}, and routes the mouse to it. Everything runs on Bitwig's UI thread. Subclasses draw in
 * {@link #paint}, call {@link #repaint} when their content changes, and override the input methods they use.
 */
abstract class PaintedWidget extends dwF {
    private final long preferredWidth;  // layout units
    private final long preferredHeight;
    private boolean paintFailed;

    PaintedWidget(long preferredWidth, long preferredHeight) {
        this.preferredWidth = preferredWidth;
        this.preferredHeight = preferredHeight;
    }

    /** Bitwig's initialisation, once, after construction. */
    final <T extends PaintedWidget> T ready() {
        I_();
        @SuppressWarnings("unchecked") T self = (T) this;
        return self;
    }

    // ---- what subclasses use -----------------------------------------------------------------------------------

    protected abstract void paint(Canvas canvas, double width, double height);

    /** A wheel turn or trackpad scroll: {@code steps} notches, or {@code pixels} when the device scrolls smoothly. */
    protected void wheel(double x, double y, int steps, int pixels, boolean horizontal, int modifiers) { }

    /** A button pressed at (x, y); {@code clicks} is 1 for a single click, 2 for a double click. */
    protected void press(double x, double y, int button, int clicks, int modifiers) { }

    /** The mouse moved with the button held, (x, y) in the widget, (dx, dy) since the last call. */
    protected void drag(double x, double y, double dx, double dy) { }

    protected void release(double x, double y) { }

    /** Paints again soon (the whole widget). */
    final void repaint() {
        if (TUb()) OCx(); // TUb: attached to a window
    }

    /** Asks Bitwig to lay the widget out again (its preferred size changed). */
    final void relayout() {
        eAJ();
    }

    final boolean attached() { return TUb(); }
    final double width() { return rtK(); }   // pixels
    final double height() { return cBV(); }
    /** Pixels per layout unit (UI scale and panel scale). */
    final double unit() { return wGt(1000) / 1000.0; }

    /** A theme font for this widget's window (null when not attached); {@code spec} null: the widget's own font. */
    final iiG font(ehb spec) {
        dou window = kJ_(); // the window, when attached
        if (window == null) return null;
        return window.beI(spec != null ? spec : fxI(), this); // cached by the window per face, size and scale
    }

    /** The widget's inherited font spec (its group's, else the theme's default). */
    final ehb fontSpec() { return fxI(); }

    /** The theme's text colour for this widget, 0xAARRGGBB. */
    final int textColour() { return mWG().beI(this).ZRB(); }

    // ---- Bitwig's questions --------------------------------------------------------------------------------------

    @Override public uXa iI_() { return new uXa(wGt(preferredWidth), wGt(preferredHeight)); } // preferred, pixels
    @Override public uXa mM_() { return uXa.beI(); } // no minimum
    @Override public uXa nk_() { return uXa.Gef(); } // no maximum: a group can give it any spare space
    @Override public dwF neH() { return this; }      // Bitwig's typed self
    @Override public doI BZt() { return this; }      // Bitwig's typed self

    @Override
    public void beI(ijY g) { // paint the content (Bitwig has filled the background and set the clip)
        try {
            paint(new Canvas(g), rtK(), cBV());
        } catch (Throwable failure) {
            if (!paintFailed) AgentPanels.LOG.log(getClass().getSimpleName() + " could not paint", failure);
            paintFailed = true; // once per widget, not once per frame
        }
    }

    /** Bitwig asks which handler takes a wheel turn at (x, y): this widget's, for both directions. */
    @Override
    public dty beI(double x, double y, Dr3 orientation, eqv modifiers) {
        return new dty(this) {
            @Override
            public void beI(dLl e) {
                try {
                    boolean pixels = e.Gef() == eqH.Gef; // PIXELS (trackpads) or STEPS (wheel notches)
                    wheel(e.wLf(), e.dvk(), pixels ? 0 : e.beI(), pixels ? e.beI() : 0, e.iFu().beI(), e.ymo().ymo());
                } catch (Throwable failure) {
                    AgentPanels.LOG.log("wheel handler failed", failure);
                }
            }
        };
    }

    /** Bitwig asks which handler takes a press at (x, y): a fresh one per press, which also gets its drag and release. */
    @Override
    public dtt Gef(eqw button, double x, double y, eqv modifiers, int clicks) {
        return new dtt() {
            @Override
            public void beI(dLf e) { // pressed
                try {
                    press(e.wLf(), e.dvk(), e.beI().beI(), Math.max(1, e.Gef()), e.ymo().ymo());
                } catch (Throwable failure) {
                    AgentPanels.LOG.log("press handler failed", failure);
                }
            }

            @Override
            public boolean jD_() { return true; } // wants the moves while pressed

            @Override
            public void beI(dLk e) { // moved while pressed
                try {
                    drag(e.wLf(), e.dvk(), e.af(), e.kwH());
                } catch (Throwable failure) {
                    AgentPanels.LOG.log("drag handler failed", failure);
                }
            }

            @Override
            public void beI(dLg e) { // released
                try {
                    release(e.wLf(), e.dvk());
                } catch (Throwable failure) {
                    AgentPanels.LOG.log("release handler failed", failure);
                }
                super.beI(e); // ends the gesture once no button is held
            }
        };
    }
}
