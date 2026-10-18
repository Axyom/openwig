package com.bitwig.extension.api.project.track;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface TrackOrTrackGroup extends Channel {
    Track asTrack();
}
