package io.github.axyom.openwigagent;

import bitwig.obf.JW; // a text value a widget shows and edits
import bitwig.obf.MNh; // a value's change event
import bitwig.obf.NVD; // a value listener
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** A text value of the mod's own, shown by Bitwig's text widgets. Changes are announced on the caller's thread. */
final class TextValue implements JW {
    private final CopyOnWriteArrayList<NVD> listeners = new CopyOnWriteArrayList<>();
    private final boolean settable;
    private volatile String text;
    private volatile Consumer<String> onEdit = s -> { };

    TextValue(String initial, boolean settable) {
        this.text = initial == null ? "" : initial;
        this.settable = settable;
    }

    void onEdit(Consumer<String> listener) { this.onEdit = listener; }

    String text() { return text; }

    /** Sets the text from the mod's side and tells the widgets (call on Bitwig's UI thread). */
    void set(String next) {
        String before = text;
        text = next == null ? "" : next;
        if (Objects.equals(before, text)) return;
        MNh event = new MNh(this, before, text); // a change event: old and new text
        for (NVD listener : listeners) listener.eventOccurred(event);
    }

    @Override public Object getValue() { return text; }
    @Override public boolean isSettable() { return settable; }
    @Override public boolean isObservable() { return true; }

    /** The user edited the text in a widget. */
    @Override public void setValue(Object next) {
        if (!settable) return;
        text = next == null ? "" : next.toString();
        onEdit.accept(text);
    }

    @Override public void addListener(NVD listener) { if (listener != null) listeners.addIfAbsent(listener); }
    @Override public void removeListener(NVD listener) { listeners.remove(listener); }
}
