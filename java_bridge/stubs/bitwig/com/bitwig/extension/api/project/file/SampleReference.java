package com.bitwig.extension.api.project.file;

/** Compile-time signatures only; runtime interface is supplied by Bitwig. */
public interface SampleReference {
    java.io.File getLocalFile();
    double getDurationInSeconds();
    int getSampleRate();
    int getChannelCount();
}
