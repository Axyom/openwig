package com.bitwig.extension.api.project;

import com.bitwig.extension.api.project.timeline.InstrumentNote;
import com.bitwig.extension.api.project.timeline.NoteClip;
import com.bitwig.extension.api.project.mixer.NativeDevice;
import com.bitwig.extension.api.project.track.Track;

public interface Factory {
    com.bitwig.extension.api.project.timeline.WarpMarkers createWarpEvents();
    com.bitwig.extension.api.project.parameter.AutomationEvents createDoubleAutomationEvents(
            com.bitwig.extension.api.project.parameter.Unit unit);
    Track createAudioTrack();
    Track createInstrumentTrack();
    Track createHybridTrack();
    com.bitwig.extension.api.project.track.TrackGroup createTrackGroup();
    com.bitwig.extension.api.project.timeline.CueMarker createCueMarker(double time);
    NativeDevice createNativeDevice(java.util.UUID id);
    com.bitwig.extension.api.project.mixer.PluginPreset createVst2DevicePreset(String name, int id, int version);
    com.bitwig.extension.api.project.parameter.AutomationEvents createBooleanAutomationEvents();
    com.bitwig.extension.api.project.parameter.AutomationEvents createIntegerAutomationEvents();
    com.bitwig.extension.api.project.timeline.TimeSignature createTimeSignature(int numerator, int denominator, int ticks);
    com.bitwig.extension.api.project.mixer.PluginPreset createVst3DevicePreset(String name, byte[] id);
    com.bitwig.extension.api.project.mixer.PluginPreset createVst3DevicePreset(String name, byte[] id, int role);
    com.bitwig.extension.api.project.mixer.PluginStateID createPluginStateID(String extension);
    java.io.OutputStream createPluginStateOutput(com.bitwig.extension.api.project.mixer.PluginStateID id);
    NoteClip createNoteClip(boolean launcher);
    com.bitwig.extension.api.project.timeline.AudioClip createAudioClip(boolean launcher);
    com.bitwig.extension.api.project.file.SampleReference createSampleReferenceForLocalFile(java.io.File file);
    com.bitwig.extension.api.project.timeline.AudioNote createAudioNote(double time, double duration,
            com.bitwig.extension.api.project.file.SampleReference sample);
    InstrumentNote createInstrumentNote(int channel, int key, double time,
                                        double duration, double velocity, double offVelocity);
}
