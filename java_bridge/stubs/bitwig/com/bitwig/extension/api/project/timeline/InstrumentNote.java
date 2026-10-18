package com.bitwig.extension.api.project.timeline;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface InstrumentNote extends EnduringEvent {
    void setIsMuted(boolean muted);
    void setChance(double chance);
    NoteExpression getGain();
    NoteExpression getPan();
    NoteExpression getTranspose();
    NoteExpression getTimbre();
    NoteExpression getPressure();
    int getKey();
    int getChannel();
    double getOnVelocity();
    double getOffVelocity();
    boolean isMuted();
}
