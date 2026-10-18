package com.bitwig.flt.document.api.document;

import com.bitwig.flt.document.core.master.oZU;
import com.bitwig.extension.api.project.Factory;
import com.bitwig.extension.api.project.track.TrackGroup;
import com.bitwig.extension.api.project.ProjectMetadata;
import com.bitwig.extension.api.project.timeline.CueMarkers;

public class ApiProject {
    public ApiProject(oZU project) {}
    public Factory getFactory() { throw new UnsupportedOperationException(); }
    public com.bitwig.extension.api.project.Transport getTransport() { throw new UnsupportedOperationException(); }
    public TrackGroup getTrackGroup() { throw new UnsupportedOperationException(); }
    public CueMarkers getCueMarkers() { throw new UnsupportedOperationException(); }
    public String getMetadataText(ProjectMetadata key) { throw new UnsupportedOperationException(); }
    public void dispose() {}
}
