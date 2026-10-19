package com.bitwig.extension.api.project.timeline;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface InstrumentNote extends Note {
    void setIsMuted(boolean muted);
    void setChance(double chance);
    void setVelocitySpread(double spread);
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
