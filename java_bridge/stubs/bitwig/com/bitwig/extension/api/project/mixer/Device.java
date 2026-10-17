package com.bitwig.extension.api.project.mixer;

public interface Device {
    String getDisplayName();
    com.bitwig.extension.api.project.parameter.BooleanParameter getEnabled();
}
