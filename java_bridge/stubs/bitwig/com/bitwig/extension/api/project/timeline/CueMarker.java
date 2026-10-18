package com.bitwig.extension.api.project.timeline;

import com.bitwig.extension.api.Color;
import com.bitwig.extension.api.project.Titled;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface CueMarker extends Titled, Event {
    Color getColor();
    void setColor(Color color);
}
