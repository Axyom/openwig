package com.bitwig.extension.api.project.track;

import com.bitwig.extension.api.project.Titled;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface TrackOrTrackGroup extends Titled {
    Track asTrack();
}
