package com.bitwig.extension.api.project;

import com.bitwig.extension.api.project.parameter.BooleanParameter;
import com.bitwig.extension.api.project.parameter.DoubleParameter;
import com.bitwig.extension.api.project.timeline.TimeSignature;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface Transport {
    DoubleParameter getTempo();
    void setTimeSignature(TimeSignature signature);
    void setTimeSignature(com.bitwig.extension.api.project.parameter.AutomationEvents events);
    TimeSignature getTimeSignature();
    BooleanParameter getEnableGroove();
    DoubleParameter getShuffleAmount();
    DoubleParameter getAccentAmount();
    DoubleParameter getAccentPhase();
    DoubleParameter getCrossfade();
}
