package com.bitwig.extension.api.project.timeline;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface InstrumentNote extends EnduringEvent {
    int getKey();
    int getChannel();
    double getOnVelocity();
    double getOffVelocity();
    boolean isMuted();
}
