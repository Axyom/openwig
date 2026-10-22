package com.bitwig.extension.api.project.timeline;
public interface WarpMarkers  {
    void addWarpEvent(double beat, double sourceSeconds);
    int getWarpMarkerCount();
    WarpMarker getWarpMarkerAt(int index);
    boolean areWarpMarkersValid();
}
