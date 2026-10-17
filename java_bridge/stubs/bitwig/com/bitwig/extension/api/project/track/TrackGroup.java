package com.bitwig.extension.api.project.track;

import java.util.List;

public interface TrackGroup {
    List mainTracks();
    List effectTracks();
    void addEffectTrack(Track track);
    Track masterTrack();
}
