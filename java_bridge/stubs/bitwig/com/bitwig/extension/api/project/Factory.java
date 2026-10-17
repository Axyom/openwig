package com.bitwig.extension.api.project;

import com.bitwig.extension.api.project.timeline.InstrumentNote;
import com.bitwig.extension.api.project.timeline.NoteClip;
import com.bitwig.extension.api.project.mixer.NativeDevice;
import com.bitwig.extension.api.project.track.Track;

public interface Factory {
    Track createAudioTrack();
    NativeDevice createNativeDevice(java.util.UUID id);
    NoteClip createNoteClip(boolean launcher);
    InstrumentNote createInstrumentNote(int channel, int key, double time,
                                        double duration, double velocity, double offVelocity);
}
