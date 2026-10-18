package com.bitwig.extension.api.project.timeline;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface AudioClip extends Clip {
    void setFadeInTime(double fadeInTime);
    void setFadeOutTime(double fadeOutTime);
    double getFadeInTime();
    double getFadeOutTime();
}
