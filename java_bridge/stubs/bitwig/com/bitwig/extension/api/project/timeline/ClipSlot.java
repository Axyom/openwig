package com.bitwig.extension.api.project.timeline;

public interface ClipSlot {
    Clip getClip();
    void setClip(Clip clip);
    void setHasStopButton(boolean enabled);
    boolean hasStopButton();
}
