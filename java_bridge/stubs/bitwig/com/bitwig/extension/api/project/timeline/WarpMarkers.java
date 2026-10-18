package com.bitwig.extension.api.project.timeline;
public interface WarpMarkers  {
    int getWarpMarkerCount();
    WarpMarker getWarpMarkerAt(int index);
    boolean areWarpMarkersValid();
}
