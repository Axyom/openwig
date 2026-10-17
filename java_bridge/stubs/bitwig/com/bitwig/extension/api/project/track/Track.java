package com.bitwig.extension.api.project.track;

import com.bitwig.extension.api.project.timeline.ClipTimeline;
import com.bitwig.extension.api.project.Titled;
import com.bitwig.extension.api.project.mixer.Mixer;
import com.bitwig.extension.api.project.mixer.DeviceChain;

public interface Track extends Titled {
    ClipTimeline clipTimeline();
    Mixer mixer();
    DeviceChain deviceChain();
}
