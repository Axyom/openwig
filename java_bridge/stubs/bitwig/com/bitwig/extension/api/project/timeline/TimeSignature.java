package com.bitwig.extension.api.project.timeline;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface TimeSignature {
    int getNumerator();
    int getDenominator();
    int getTickRate();
}
