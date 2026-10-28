package com.bitwig.extension.api.project.track;

import java.util.List;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface TrackGroup extends TrackOrTrackGroup {
    List mainTracks();
    List effectTracks();
    void addMainTrack(TrackOrTrackGroup track);
    void addEffectTrack(Track track);
    Track masterTrack();
}
