package com.bitwig.extension.api.project.mixer;

/** Compile-time signature only; supplied by the exact host at runtime. */
public interface PluginPreset {
    Device createDeviceInstance();
    void setPluginStateID(PluginStateID id);
}
