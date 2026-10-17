package com.bitwig.extension.api.project;

import com.bitwig.extension.api.project.timeline.InstrumentNote;
import com.bitwig.extension.api.project.timeline.NoteClip;

public interface Factory {
    NoteClip createNoteClip(boolean launcher);
    InstrumentNote createInstrumentNote(int channel, int key, double time,
                                        double duration, double velocity, double offVelocity);
}
