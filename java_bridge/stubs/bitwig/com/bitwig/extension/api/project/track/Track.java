package com.bitwig.extension.api.project.track;

import com.bitwig.extension.api.Color;
import com.bitwig.extension.api.project.timeline.ClipTimeline;
import com.bitwig.extension.api.project.Titled;
import com.bitwig.extension.api.project.mixer.Mixer;
import com.bitwig.extension.api.project.mixer.DeviceChain;

public interface Track extends TrackOrTrackGroup {
    ClipTimeline clipTimeline();
    Mixer mixer();
    DeviceChain deviceChain();
    void setMonitorMode(MonitorMode mode);
    void setColor(Color color);
    Color getColor();
}
