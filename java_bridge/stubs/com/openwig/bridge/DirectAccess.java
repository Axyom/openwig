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

    public static int createMainTrack(String kind, String name) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int deleteTrack(int index) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int clearMainTracks() {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int effectTrackCount() {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int createEffectTrack(String name) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int deleteEffectTrack(int index) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int clearEffectTracks() {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static void removeEffectTrack(Object group, Object target) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int masterDeviceCount() {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int insertMasterFactoryDevice(String name) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int clearMasterDevices() {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONObject clearProject() {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static void removeDevice(Object chain, Object device) {
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

    public static Object activeTransportObject() {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONObject transport(String method, JSONObject params) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONObject automation(JSONObject params, boolean write) {
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

    public static JSONObject setDeviceParameters(int trackIndex, int deviceIndex,
                                                 JSONObject params, String unit) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONObject getDeviceParameters(int trackIndex, int deviceIndex,
                                                 JSONArray names, String unit) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONObject renameDevice(int trackIndex, int deviceIndex, String name) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONArray listDeviceParameters(int trackIndex, int deviceIndex) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONObject setDeviceExpanded(int trackIndex, int deviceIndex, boolean on) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int deleteTrackDevice(int trackIndex, int deviceIndex) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONObject setDeviceEnabled(int trackIndex, int deviceIndex, boolean on) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static int insertFactoryDevice(int trackIndex, String name) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONObject readObject(String method, String path, int index) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONArray automationEnumeratePath(String path) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONObject dumpProject() {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONObject exportDawProject(String path, boolean embedMedia) {
        throw new UnsupportedOperationException("build did not install direct adapter");
    }

    public static JSONArray automationEnumerate(int trackIndex) {
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
