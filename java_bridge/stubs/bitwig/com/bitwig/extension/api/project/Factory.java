package com.bitwig.extension.api.project;

import com.bitwig.extension.api.project.timeline.InstrumentNote;
import com.bitwig.extension.api.project.timeline.NoteClip;
import com.bitwig.extension.api.project.mixer.NativeDevice;
import com.bitwig.extension.api.project.track.Track;

public interface Factory {
    com.bitwig.extension.api.project.parameter.AutomationEvents createDoubleAutomationEvents(
            com.bitwig.extension.api.project.parameter.Unit unit);
    Track createAudioTrack();
    Track createInstrumentTrack();
    Track createHybridTrack();
    com.bitwig.extension.api.project.track.TrackGroup createTrackGroup();
    com.bitwig.extension.api.project.timeline.CueMarker createCueMarker(double time);
    NativeDevice createNativeDevice(java.util.UUID id);
    NoteClip createNoteClip(boolean launcher);
    InstrumentNote createInstrumentNote(int channel, int key, double time,
                                        double duration, double velocity, double offVelocity);
}
