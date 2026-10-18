package com.bitwig.extension.api.project.timeline;
public interface AudioNote extends EnduringEvent  {
    boolean isMuted();
    java.io.File getAudioFile();
    int getSampleRate();
    int getChannelCount();
    double getAudioFileDurationInSeconds();
    WarpMode getWarpMode();
    PreserveTransientsMode getPreserveTransientsMode();
    TransientRate getTransientRate();
    WarpGrainSize getWarpGrainSize();
    WarpTailMode getWarpTailMode();
    boolean getPreviewOnsets();
    boolean getPreserveFormants();
    int getFormantEnvelopeResolution();
    WarpMarkers getWarpEvents();
}
