package com.bitwig.extension.api.project.timeline;
/** Compile-time signatures only; the host supplies implementation and hierarchy. */
public interface Note extends EnduringEvent {
    boolean isMuted();
    void setIsMuted(boolean muted);
    void setChance(double chance);
    NoteExpression getGain();
    NoteExpression getPan();
    NoteExpression getTranspose();
    NoteExpression getFormant();
}
