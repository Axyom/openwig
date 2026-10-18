package com.bitwig.extension.api.project.mixer;

public interface Device {
    boolean getActive();
    void setActive(boolean active);
    String getDisplayName();
    void setDisplayName(String name);
    void setIsExpanded(boolean expanded);
    com.bitwig.extension.api.project.parameter.BooleanParameter getEnabled();
}
