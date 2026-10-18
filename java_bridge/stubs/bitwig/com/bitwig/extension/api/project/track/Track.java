package com.bitwig.extension.api.project.track;

import com.bitwig.extension.api.Color;
import com.bitwig.extension.api.project.timeline.ClipTimeline;
import com.bitwig.extension.api.project.Titled;
import com.bitwig.extension.api.project.mixer.Mixer;
import com.bitwig.extension.api.project.mixer.DeviceChain;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface Track extends TrackOrTrackGroup {
    ClipTimeline clipTimeline();
    com.bitwig.extension.api.project.routing.SourceSelector sourceSelector();
    com.bitwig.extension.api.project.routing.DestinationSelector destinationSelector();
    com.bitwig.extension.api.project.routing.TrackAudioSource preFader();
    com.bitwig.extension.api.project.routing.TrackAudioSource postFader();
    com.bitwig.extension.api.project.routing.NoteSource postFxNotes();
    com.bitwig.extension.api.project.timeline.ClipSlot clipSlot(int index);
    Mixer mixer();
    DeviceChain deviceChain();
    void setMonitorMode(MonitorMode mode);
    void setColor(Color color);
    Color getColor();
    TrackType getTrackType();
}
