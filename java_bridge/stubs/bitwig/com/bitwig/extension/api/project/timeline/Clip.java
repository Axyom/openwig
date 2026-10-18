package com.bitwig.extension.api.project.timeline;

import com.bitwig.extension.api.Color;
import com.bitwig.extension.api.project.Titled;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface Clip extends EnduringEvent, Titled {
    ClipContent getContent();
    void setColor(Color color);
    void setIsMuted(boolean muted);
    void setIsLoopEnabled(boolean enabled);
    void setLoopStart(double start);
    void setLoopDuration(double duration);
    void setPlayStartOffset(double offset);
    Color getColor();
    boolean isMuted();
    boolean isLoopEnabled();
    double getLoopStart();
    double getLoopDuration();
    double getPlayStartOffset();
}
