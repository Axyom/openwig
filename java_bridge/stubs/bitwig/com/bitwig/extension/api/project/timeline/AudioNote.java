package com.bitwig.extension.api.project.timeline;
public interface AudioNote extends EnduringEvent  {
    boolean isMuted();
    void setIsMuted(boolean muted);
    void setFadeInTime(double beats);
    void setFadeOutTime(double beats);
    void setWarpMode(WarpMode mode);
    NoteExpression getGain();
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
