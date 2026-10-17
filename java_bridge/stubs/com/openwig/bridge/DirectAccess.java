package com.openwig.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

/** Compile-time signature only. The build replaces this with exact-build bytecode. */
public final class DirectAccess {
    private DirectAccess() {}

    public static boolean hasActiveDocument() {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static double readTempo() {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static void writeTempo(double bpm) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static void dispatch(Runnable task) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int mainTrackCount() {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int createInstrumentTrack(String name) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONObject trackSnapshot(int index) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONObject updateTrack(int index, String field, JSONObject params) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static Object activeDocumentObject() {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static void runInUndo(Object timeline, Runnable action) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int clipCount(int trackIndex) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static java.util.UUID factoryDeviceId(String fileName) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int deviceCount(int trackIndex) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONArray deviceSnapshot(int trackIndex) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONObject setDeviceEnabled(int trackIndex, int deviceIndex, boolean on) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int insertFactoryDevice(int trackIndex, String name) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int noteCount(int trackIndex, int clipIndex) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int addNoteClip(int trackIndex, double start, double duration,
                                  JSONArray notes) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }
}
