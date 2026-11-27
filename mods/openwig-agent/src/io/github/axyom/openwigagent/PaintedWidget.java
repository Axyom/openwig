package io.github.axyom.openwigagent;

import bitwig.obf.dLf; // a mouse button pressed on the widget
import bitwig.obf.dLg; // a mouse button released
import bitwig.obf.dLk; // the mouse moved (during a press: a drag)
import bitwig.obf.dLl; // a mouse wheel (or trackpad scroll) event
import bitwig.obf.doI; // a widget, as Bitwig types it
import bitwig.obf.doe; // a callback before each display frame
import bitwig.obf.dou; // the window a widget is in
import bitwig.obf.dqZ; // a widget's keyboard focus state
import bitwig.obf.dre; // a display frame
import bitwig.obf.drf; // the application: UI thread, cursors
import bitwig.obf.dsB; // a subscription to a window's display frames
import bitwig.obf.dtt; // what a press does until the button is released (Bitwig asks the widget for one per press)
import bitwig.obf.dty; // what a wheel turn over the widget does
import bitwig.obf.dwF; // a widget
import bitwig.obf.ehb; // a font, as the theme names it (face and size)
import bitwig.obf.eqH; // a wheel event's unit: steps (notches) or pixels
import bitwig.obf.eqo; // a key
import bitwig.obf.eqr; // a key pressed
import bitwig.obf.eqt; // the keys
import bitwig.obf.eqv; // the modifier keys held
import bitwig.obf.eqw; // a mouse button
import com.bitwig.base.geom.Dr3; // horizontal or vertical
import com.bitwig.base.geom.uXa; // a size (width, height), in pixels
import com.bitwig.graphics.iiG; // a font at a size, for one window
import com.bitwig.graphics.ijY; // Bitwig's drawing surface
import com.bitwig.windowing_system.VcC; // a mouse cursor

/**
 * A Bitwig widget that the mod paints itself: Bitwig lays it out (it takes the space its group gives it), asks it to
 * paint into a {@link Canvas}, routes the mouse to it, gives it the keyboard once it has the focus (a click on it
 * takes the focus), shows the cursor it asks for, and ticks its animations once per display frame. Everything runs
 * on Bitwig's UI thread. Subclasses draw in {@link #paint}, call {@link #repaint} when their content changes, and
 * override the input methods they use.
 */
abstract class PaintedWidget extends dwF {
    static final int SHIFT = 1, CONTROL = 2, ALT = 8; // modifier bits, as Bitwig keeps them

    /** Keys a subclass can act on; the shortcut modifier is Ctrl on Windows and Linux, Cmd on macOS. */
    enum Key { COPY, SELECT_ALL, ESCAPE, UP, DOWN, PAGE_UP, PAGE_DOWN, HOME, END, OTHER }

    enum Cursor { POINTER, TEXT, HAND }

    private final long preferredWidth;  // layout units
    private final long preferredHeight;
    private boolean paintFailed;
    private boolean focused;
    private dsB frames;          // the display-frame subscription while animating
    private boolean subscribing;
    private long lastFrame;

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

    /** A wheel turn or trackpad scroll: {@code steps} notches (negative: up), or {@code pixels} for smooth devices. */
    protected void wheel(double x, double y, int steps, int pixels, boolean horizontal, int modifiers) { }

    /** A button pressed at (x, y); {@code clicks} is 1 for a single click, 2 for a double click, 3 for a triple. */
    protected void press(double x, double y, int button, int clicks, int modifiers) { }

    /** The mouse moved with the button held, (x, y) in the widget, (dx, dy) since the last call. */
    protected void drag(double x, double y, double dx, double dy) { }

    protected void release(double x, double y) { }

    /** A key pressed while the widget has the keyboard focus; true when it was used (Bitwig's shortcuts then skip it). */
    protected boolean key(Key key, int modifiers) { return false; }

    /** The cursor over (x, y). */
    protected Cursor cursor(double x, double y) { return Cursor.POINTER; }

    /** Whether a click on the widget takes the keyboard focus. */
    protected boolean focusable() { return false; }

    protected void focusChanged(boolean focused) { }

    /** One display frame of an animation started with {@link #animate}; {@code dt} in seconds. False ends it. */
    protected boolean frame(double dt) { return false; }

    /** Paints again soon (the whole widget). */
    final void repaint() {
        if (TUb()) OCx(); // TUb: attached to a window
    }

    /** Asks Bitwig to lay the widget out again (its preferred size changed). */
    final void relayout() {
        eAJ();
    }

    final boolean attached() { return TUb(); }
    final boolean focused() { return focused || jtI() == dqZ.sCA; } // jtI: the focus state, FOCUSED
    final double width() { return rtK(); }   // pixels
    final double height() { return cBV(); }
    /** Pixels per layout unit (UI scale and panel scale). */
    public final double unit() { return wGt(1000) / 1000.0; }

    /** Takes the keyboard focus (in the nearest focus scope, as a click on a focusable Bitwig widget does). */
    final void requestFocus() {
        jv_();
    }

    /** A theme font for this widget's window (null when not attached); {@code spec} null: the widget's own font. */
    final iiG font(ehb spec) {
        dou window = kJ_(); // the window, when attached
        if (window == null) return null;
        return window.beI(spec != null ? spec : fxI(), this); // cached by the window per face, size and scale
    }

    /** The widget's inherited font spec (its group's, else the theme's default). */
    final ehb fontSpec() { return fxI(); }

    /** The text colour this widget inherits from its panel (so it follows the theme), 0xAARRGGBB. */
    final int textColour() { return mWG().beI(this).ZRB(); }

    /** Puts text on the clipboard of the widget's window. */
    final void clipboard(String text) {
        dou window = kJ_();
        if (window != null) window.GwM().beI(text); // the window's clipboard: set its text
    }

    /** Calls {@link #frame} once per display frame until it returns false. */
    final void animate() {
        if (frames != null || subscribing || !TUb()) return;
        subscribing = true;
        drf.EO().asyncExec(() -> { // after the current paint
            subscribing = false;
            if (frames == null && TUb()) {
                lastFrame = 0;
                frames = dsB.beI(tick, this); // this window's display frames
            }
        });
    }

    private final doe tick = new doe() {
        @Override
        public void aboutToPaint(dre frame) {
            try {
                if (!TUb()) { stopAnimation(); return; }
                long now = System.nanoTime();
                double dt = lastFrame == 0 ? 1 / 60.0 : Math.min(0.1, (now - lastFrame) / 1e9);
                lastFrame = now;
                if (!frame(dt)) stopAnimation();
            } catch (Throwable failure) {
                stopAnimation();
                AgentPanels.LOG.log("animation failed", failure);
            }
        }
    };

    private void stopAnimation() {
        if (frames != null) {
            frames.beI(); // unsubscribes
            frames = null;
        }
    }

    // ---- Bitwig's questions --------------------------------------------------------------------------------------

    @Override public uXa iI_() { return new uXa(wGt(preferredWidth), wGt(preferredHeight)); } // preferred, pixels
    @Override public uXa mM_() { return uXa.beI(); } // no minimum
    @Override public uXa nk_() { return uXa.Gef(); } // no maximum: a group can give it any spare space
    @Override public dwF neH() { return this; }      // Bitwig's typed self
    @Override public doI BZt() { return this; }      // Bitwig's typed self
    @Override public boolean ju_() { return focusable(); } // a click focuses it

    @Override public void jl_() { focus(true); }  // took the keyboard focus
    @Override public void jO_() { focus(false); } // lost it

    private void focus(boolean now) {
        if (now == focused) return;
        focused = now;
        try {
            focusChanged(now);
        } catch (Throwable failure) {
            AgentPanels.LOG.log("focus handler failed", failure);
        }
    }

    @Override
    public void iz_() { // disposed
        stopAnimation();
        super.iz_();
    }

    @Override
    public void beI(ijY g) { // paint the content (Bitwig has filled the background and set the clip)
        try {
            paint(new Canvas(g), rtK(), cBV());
        } catch (Throwable failure) {
            if (!paintFailed) AgentPanels.LOG.log(getClass().getSimpleName() + " could not paint", failure);
            paintFailed = true; // once per widget, not once per frame
        }
    }

    /** A key pressed while this widget has the focus (Bitwig asks the focused widget, then its parents). */
    @Override
    public boolean Gef(eqr e) {
        try {
            eqo k = e.BBg();          // the key
            boolean shortcut = e.wGt(); // the platform's shortcut modifier is held
            Key key = k == eqt.sCA && shortcut ? Key.COPY
                    : k == eqt.beI && shortcut ? Key.SELECT_ALL
                    : k == eqt.u2C ? Key.ESCAPE
                    : k == eqt.EQo ? Key.UP
                    : k == eqt.vYz ? Key.DOWN
                    : k == eqt.ERa ? Key.PAGE_UP
                    : k == eqt.qzt ? Key.PAGE_DOWN
                    : k == eqt.XhI ? Key.HOME
                    : k == eqt.Jvg ? Key.END
                    : Key.OTHER;
            return key != Key.OTHER && key(key, e.byf().ymo());
        } catch (Throwable failure) {
            AgentPanels.LOG.log("key handler failed", failure);
            return false;
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

    /**
     * Bitwig asks which handler takes a press at (x, y): a fresh one per press, which also gets its drag and release.
     * It also asks on every mouse move with no button held (clicks 0), for the cursor to show.
     */
    @Override
    public dtt Gef(eqw button, double x, double y, eqv modifiers, int clicks) {
        return new dtt() {
            @Override
            public void beI(dLf e) { // pressed; Gef() counts the clicks before this one
                try {
                    press(e.wLf(), e.dvk(), e.beI().beI(), e.Gef() + 1, e.ymo().ymo());
                } catch (Throwable failure) {
                    AgentPanels.LOG.log("press handler failed", failure);
                }
            }

            @Override
            public boolean jD_() { return true; } // wants the moves while pressed

            @Override
            public boolean jQ_() { return true; } // lets the click focus the widget

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

            @Override
            public VcC Gef(double cx, double cy, eqv mods) { // the cursor over (cx, cy)
                try {
                    return switch (cursor(cx, cy)) {
                        case TEXT -> drf.DIi;  // the text cursor
                        case HAND -> drf.n1g;  // the hand
                        default -> null;       // Bitwig's pointer
                    };
                } catch (Throwable failure) {
                    return null;
                }
            }
        };
    }
}
