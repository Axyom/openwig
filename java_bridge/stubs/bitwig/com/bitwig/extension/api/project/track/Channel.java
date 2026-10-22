package com.bitwig.extension.api.project.track;

import com.bitwig.extension.api.Color;
import com.bitwig.extension.api.project.Titled;
import com.bitwig.extension.api.project.mixer.DeviceChain;
import com.bitwig.extension.api.project.mixer.Mixer;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface Channel extends Titled {
    void setIsActive(boolean active);
    boolean isActive();
    DeviceChain deviceChain();
    Mixer mixer();
    void setColor(Color color);
    Color getColor();
}
