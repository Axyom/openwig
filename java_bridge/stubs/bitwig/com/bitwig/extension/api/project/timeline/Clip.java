package com.bitwig.extension.api.project.timeline;

import com.bitwig.extension.api.Color;
import com.bitwig.extension.api.project.Titled;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface Clip extends EnduringEvent, Titled {
    ClipContent getContent();
    Color getColor();
    boolean isMuted();
    boolean isLoopEnabled();
    double getLoopStart();
    double getLoopDuration();
    double getPlayStartOffset();
}
