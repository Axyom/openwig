package com.openwig.bridge;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import org.json.JSONObject;
import org.json.JSONArray;

/** Minimal in-process protocol endpoint. Song operations are added only after live validation. */
public final class Bootstrap {
    // Keep the prototype separate from the existing controller until parity is proven.
    private static final int PORT = Integer.getInteger("openwig.bridge.port", 7778);
    private static final int MAX_MESSAGE = 1024 * 1024;
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private Bootstrap() {}

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread server = new Thread(Bootstrap::serve, "openwig-java-bridge");
        server.setDaemon(true);
        server.start();
    }

    private static void serve() {
        try (ServerSocket listener = new ServerSocket(PORT, 1,
                InetAddress.getByName("127.0.0.1"))) {
            while (!Thread.currentThread().isInterrupted()) {
                try (Socket client = listener.accept()) {
                    handle(client);
                } catch (Exception ignored) {
                    // A malformed or disconnected client must not terminate Bitwig.
                }
            }
        } catch (Exception ignored) {
            STARTED.set(false);
        }
    }

    private static void handle(Socket client) throws Exception {
        DataInputStream input = new DataInputStream(new BufferedInputStream(client.getInputStream()));
        BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                client.getOutputStream(), StandardCharsets.UTF_8));
        send(output, new JSONObject().put("jsonrpc", "2.0").put("method", "connected")
                .put("params", new JSONObject().put("bridge", "java")
                        .put("host_version", "6.1.5")));
        while (true) {
            int length;
            try {
                length = input.readInt();
            } catch (java.io.EOFException end) {
                return;
            }
            if (length <= 0 || length > MAX_MESSAGE) {
                return;
            }
            byte[] body = new byte[length];
            input.readFully(body);
            JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (!request.has("id")) {
                continue;
            }
            JSONObject response = new JSONObject().put("jsonrpc", "2.0")
                    .put("id", request.get("id"));
            String method = request.optString("method", "");
            try {
                switch (method) {
                case "ping" -> response.put("result", "pong");
                case "host.version" -> response.put("result",
                        new JSONObject().put("version", "6.1.5"));
                case "bridge.capabilities" -> response.put("result",
                        new JSONObject().put("backend", "java").put("song_edit", false));
                case "document.status" -> response.put("result",
                        new JSONObject().put("active", DirectAccess.hasActiveDocument()));
                case "automation.write", "automation.read", "automation.clear" -> {
                    JSONObject params = request.getJSONObject("params");
                    params.put("clear", method.equals("automation.clear"));
                    params.put("shape", false);
                    if (!method.equals("automation.read")) {
                        onDocumentEvent(() -> DirectAccess.automation(params, true));
                        if (method.equals("automation.write")) {
                            params.put("shape", true);
                            onDocumentEvent(() -> DirectAccess.automation(params, true));
                        }
                    }
                    response.put("result", onDocumentEvent(() -> DirectAccess.automation(params, false)));
                }
                case "document.audio_status", "document.activate_audio", "transport.snapshot", "transport.play", "transport.stop",
                     "transport.set_position", "transport.set_loop", "transport.set_loop_region",
                     "transport.beats_to_seconds" -> {
                    JSONObject params = request.optJSONObject("params");
                    response.put("result", onDocumentEvent(() -> DirectAccess.transport(method, params)));
                }
                case "transport.get_tempo" -> response.put("result",
                        new JSONObject().put("bpm", onDocumentEvent(DirectAccess::readTempo)));
                case "transport.set_tempo" -> {
                    JSONObject params = request.optJSONObject("params");
                    double bpm = params == null ? Double.NaN : params.optDouble("bpm", Double.NaN);
                    if (!Double.isFinite(bpm) || bpm < 20 || bpm > 999) {
                        throw new IllegalArgumentException("bpm must be between 20 and 999");
                    }
                    response.put("result", new JSONObject().put("bpm",
                            onDocumentEvent(() -> {
                                DirectAccess.writeTempo(bpm);
                                return DirectAccess.readTempo();
                            })));
                }
                case "track.count" -> response.put("result",
                        new JSONObject().put("count", onDocumentEvent(DirectAccess::mainTrackCount)));
                case "track.delete" -> {
                    JSONObject params = request.optJSONObject("params");
                    int index = params == null ? -1 : params.optInt("index", -1);
                    if (index < 0) {
                        throw new IllegalArgumentException("main track index is required");
                    }
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.deleteTrack(index))));
                }
                case "project.clear_main_tracks" -> response.put("result",
                        new JSONObject().put("deleted",
                                onDocumentEvent(DirectAccess::clearMainTracks, 60)));
                case "fxtrack.count" -> response.put("result",
                        new JSONObject().put("count", onDocumentEvent(DirectAccess::effectTrackCount)));
                case "fxtrack.create" -> {
                    JSONObject params = request.optJSONObject("params");
                    String name = params == null ? "" : params.optString("name", "");
                    if (name.isBlank()) {
                        throw new IllegalArgumentException("effect track name is required");
                    }
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.createEffectTrack(name))));
                }
                case "fxtrack.delete" -> {
                    JSONObject params = request.optJSONObject("params");
                    int index = params == null ? -1 : params.optInt("index", -1);
                    if (index < 0) {
                        throw new IllegalArgumentException("effect track index is required");
                    }
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.deleteEffectTrack(index))));
                }
                case "project.clear_effect_tracks" -> response.put("result",
                        new JSONObject().put("deleted",
                                onDocumentEvent(DirectAccess::clearEffectTracks, 60)));
                case "master.device_count" -> response.put("result",
                        new JSONObject().put("count", onDocumentEvent(DirectAccess::masterDeviceCount)));
                case "master.insert_factory" -> {
                    JSONObject params = request.optJSONObject("params");
                    String name = params == null ? "" : params.optString("name", "");
                    if (name.isBlank() || name.contains("/") || name.contains("\\") ||
                            name.contains(".")) {
                        throw new IllegalArgumentException("factory device name is required");
                    }
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.insertMasterFactoryDevice(name))));
                }
                case "master.clear" -> response.put("result",
                        new JSONObject().put("deleted",
                                onDocumentEvent(DirectAccess::clearMasterDevices, 60)));
                case "project.clear" -> response.put("result",
                        onDocumentEvent(DirectAccess::clearProject, 120));
                case "track.read", "clip.read", "device.read" -> {
                    JSONObject params = request.getJSONObject("params");
                    if (method.equals("device.read") && params.has("chain")) {
                        response.put("result", onDocumentEvent(() -> DirectAccess.execute(method, params), 120));
                        break;
                    }
                    String path = params.getString("path");
                    int index = method.equals("track.read") ? -1 :
                            params.getInt(method.equals("clip.read") ? "clip" : "device");
                    response.put("result", onDocumentEvent(
                            () -> DirectAccess.readObject(method, path, index), 120));
                }
                case "project.dump" -> response.put("result",
                        onDocumentEvent(DirectAccess::dumpProject, 120));
                case "project.export_dawproject" -> {
                    JSONObject params = request.optJSONObject("params");
                    String path = params == null ? "" : params.optString("path", "");
                    boolean embed = params != null && params.optBoolean("embed_media", false);
                    if (path.isBlank()) {
                        throw new IllegalArgumentException("path is required");
                    }
                    response.put("result", onDocumentEvent(
                            () -> DirectAccess.exportDawProject(path, embed), 300));
                }
                case "automation.enumerate" -> {
                    JSONObject params = request.optJSONObject("params");
                    int track = params == null ? -1 : params.optInt("track", -1);
                    response.put("result", onDocumentEvent(
                            () -> params != null && params.has("path")
                                    ? DirectAccess.automationEnumeratePath(params.getString("path"))
                                    : DirectAccess.automationEnumerate(track), 120));
                }
                case "device.count" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && (params.has("chain") || !params.optString("path", "").isBlank())) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30));
                        break;
                    }
                    int track = params == null ? -1 : params.optInt("track", -1);
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.deviceCount(track))));
                }
                case "device.snapshot" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && (params.has("chain") || !params.optString("path", "").isBlank())) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30).getJSONArray("devices"));
                        break;
                    }
                    int track = params == null ? -1 : params.optInt("track", -1);
                    response.put("result", onDocumentEvent(() -> DirectAccess.deviceSnapshot(track)));
                }
                case "device.set_enabled" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && (params.has("chain") || !params.optString("path", "").isBlank())) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30));
                        break;
                    }
                    int track = params == null ? -1 : params.optInt("track", -1);
                    int index = params == null ? -1 : params.optInt("index", -1);
                    if (track < 0 || index < 0 || !params.has("on")) {
                        throw new IllegalArgumentException("track, device index, and enabled state are required");
                    }
                    response.put("result", onDocumentEvent(() ->
                            DirectAccess.setDeviceEnabled(track, index, params.getBoolean("on"))));
                }
                case "device.insert_factory" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && (params.has("chain") || !params.optString("path", "").isBlank())) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30));
                        break;
                    }
                    int track = params == null ? -1 : params.optInt("track", -1);
                    String name = params == null ? "" : params.optString("name", "");
                    if (track < 0 || name.isBlank() || name.contains("/") ||
                            name.contains("\\") || name.contains(".")) {
                        throw new IllegalArgumentException("track and factory device name are required");
                    }
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.insertFactoryDevice(track, name))));
                }
                case "device.set_parameters" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && (params.has("chain") || !params.optString("path", "").isBlank())) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30));
                        break;
                    }
                    int track = params == null ? -1 : params.optInt("track", -1);
                    int device = params == null ? -1 : params.optInt("device", -1);
                    JSONObject values = params == null ? null : params.optJSONObject("params");
                    String unit = params == null ? null : params.optString("unit", null);
                    if (track < 0 || device < 0 || values == null || values.isEmpty()) {
                        throw new IllegalArgumentException(
                                "track, device index, and a non-empty params object are required");
                    }
                    response.put("result", onDocumentEvent(() ->
                            DirectAccess.setDeviceParameters(track, device, values, unit)));
                }
                case "device.get_parameters" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && (params.has("chain") || !params.optString("path", "").isBlank())) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30));
                        break;
                    }
                    int track = params == null ? -1 : params.optInt("track", -1);
                    int device = params == null ? -1 : params.optInt("device", -1);
                    JSONArray names = params == null ? null : params.optJSONArray("names");
                    String unit = params == null ? null : params.optString("unit", null);
                    if (track < 0 || device < 0 || names == null || names.length() == 0) {
                        throw new IllegalArgumentException(
                                "track, device index, and a non-empty names array are required");
                    }
                    response.put("result", onDocumentEvent(() ->
                            DirectAccess.getDeviceParameters(track, device, names, unit)));
                }
                case "device.rename" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && (params.has("chain") || !params.optString("path", "").isBlank())) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30));
                        break;
                    }
                    int track = params == null ? -1 : params.optInt("track", -1);
                    int device = params == null ? -1 : params.optInt("device", -1);
                    String name = params == null ? "" : params.optString("name", "");
                    if (track < 0 || device < 0 || name.isBlank()) {
                        throw new IllegalArgumentException(
                                "track, device index, and a name are required");
                    }
                    response.put("result", onDocumentEvent(() ->
                            DirectAccess.renameDevice(track, device, name)));
                }
                case "device.set_expanded" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && (params.has("chain") || !params.optString("path", "").isBlank())) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30));
                        break;
                    }
                    int track = params == null ? -1 : params.optInt("track", -1);
                    int device = params == null ? -1 : params.optInt("device", -1);
                    if (track < 0 || device < 0 || params == null || !params.has("on")) {
                        throw new IllegalArgumentException(
                                "track, device index, and expanded state are required");
                    }
                    boolean on = params.getBoolean("on");
                    response.put("result", onDocumentEvent(() ->
                            DirectAccess.setDeviceExpanded(track, device, on)));
                }
                case "device.delete" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && (params.has("chain") || !params.optString("path", "").isBlank())) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30));
                        break;
                    }
                    int track = params == null ? -1 : params.optInt("track", -1);
                    int device = params == null ? -1 : params.optInt("device", -1);
                    if (track < 0 || device < 0) {
                        throw new IllegalArgumentException("track and device index are required");
                    }
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.deleteTrackDevice(track, device))));
                }
                case "device.list_parameters" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && (params.has("chain") || !params.optString("path", "").isBlank())) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 20).getJSONArray("parameters"));
                        break;
                    }
                    int track = params == null ? -1 : params.optInt("track", -1);
                    int device = params == null ? -1 : params.optInt("device", -1);
                    if (track < 0 || device < 0) {
                        throw new IllegalArgumentException("track and device index are required");
                    }
                    response.put("result", onDocumentEvent(() ->
                            DirectAccess.listDeviceParameters(track, device), 20));
                }
                case "track.snapshot" -> {
                    JSONObject params = request.optJSONObject("params");
                    int index = params == null ? -1 : params.optInt("index", -1);
                    if (index < 0) {
                        throw new IllegalArgumentException("main track index is required");
                    }
                    response.put("result", onDocumentEvent(() -> DirectAccess.trackSnapshot(index)));
                }
                case "launcher.settings", "launcher.launch", "launcher.stop", "launcher.return_to_arranger", "launcher.put_notes", "launcher.import_audio", "launcher.copy_arranger", "launcher.clear", "launcher.read", "audio_event.import", "audio_event.update", "audio_event.delete", "note.import", "note.update", "note.delete", "clip.replace_notes", "clip.copy", "clip.split", "clip.update", "clip.delete", "clip.import_audio", "document.info", "document.list", "document.select", "project.save_copy", "project.duplicate_range", "project.insert_time", "project.delete_range", "project.import_dawproject", "project.set_metadata", "cue_marker.create",
                     "scene.update", "track.create_group", "track.set_solo", "track.set_comment",
                     "track.set_active", "track.set_destination", "device.insert_plugin", "device.plugin_parameters", "device.export_plugin_state", "device.plugin_state_status", "device.capture_plugin_state", "device.grid_add", "device.grid_move", "device.grid_delete", "device.grid_connect", "device.grid_read", "device.chain_banks", "device.create_layer" -> {
                    JSONObject params = request.optJSONObject("params");
                    long timeout = method.equals("project.save_copy") ? 600
                            : method.equals("project.import_dawproject") ? 300 : 30;
                    response.put("result", onDocumentEvent(
                            () -> DirectAccess.execute(method, params), timeout));
                }
                case "track.rename", "track.set_volume", "track.set_pan", "track.set_mute",
                     "track.set_monitor", "track.set_color", "track.set_send" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && params.has("path") && !params.optString("path", "").isBlank()) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30));
                        break;
                    }
                    int index = params == null ? -1 : params.optInt("index", -1);
                    if (index < 0 || params == null) {
                        throw new IllegalArgumentException("main track index is required");
                    }
                    String field;
                    if (method.equals("track.rename")) {
                        if (params.optString("name", "").isBlank()) {
                            throw new IllegalArgumentException("track name is required");
                        }
                        field = "name";
                    } else if (method.equals("track.set_mute")) {
                        if (!params.has("on")) {
                            throw new IllegalArgumentException("mute state is required");
                        }
                        field = "mute";
                    } else if (method.equals("track.set_monitor")) {
                        if (params.optString("mode", "").isBlank()) {
                            throw new IllegalArgumentException("monitor mode is required");
                        }
                        field = "monitor";
                    } else if (method.equals("track.set_color")) {
                        if (!params.has("r") || !params.has("g") || !params.has("b")) {
                            throw new IllegalArgumentException("r, g, and b are required");
                        }
                        field = "color";
                    } else if (method.equals("track.set_send")) {
                        if (!params.has("send") || !params.has("value")) {
                            throw new IllegalArgumentException("send index and value are required");
                        }
                        field = "send";
                    } else {
                        double value = params.optDouble("value", Double.NaN);
                        if (!Double.isFinite(value) || value < 0 || value > 1) {
                            throw new IllegalArgumentException("value must be between 0 and 1");
                        }
                        field = method.equals("track.set_volume") ? "volume" : "pan";
                    }
                    response.put("result", onDocumentEvent(
                            () -> DirectAccess.updateTrack(index, field, params)));
                }
                case "track.create" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && (params.optString("type", "").equals("group") ||
                            !params.optString("parent", "").isBlank())) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30));
                        break;
                    }
                    String type = params == null ? "instrument" : params.optString("type", "instrument");
                    int index = params == null ? -1 : params.optInt("index", -1);
                    String name = params == null ? "Openwig Track" :
                            params.optString("name", "Openwig Track");
                    if (!type.equals("instrument") && !type.equals("audio") &&
                            !type.equals("hybrid")) {
                        throw new IllegalArgumentException(
                                "track type must be instrument, audio, or hybrid");
                    }
                    if (index != -1 || name.isBlank()) {
                        throw new IllegalArgumentException(
                                "only append of a named track is supported");
                    }
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.createMainTrack(type, name))));
                }
                case "clip.count" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && !params.optString("path", "").isBlank()) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30));
                        break;
                    }
                    int track = params == null ? -1 : params.optInt("track", -1);
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.clipCount(track))));
                }
                case "clip.note_count" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && !params.optString("path", "").isBlank()) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30));
                        break;
                    }
                    int track = params == null ? -1 : params.optInt("track", -1);
                    int clip = params == null ? -1 : params.optInt("clip", -1);
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.noteCount(track, clip))));
                }
                case "clip.create_arranger_with_notes" -> {
                    JSONObject params = request.optJSONObject("params");
                    if (params != null && !params.optString("path", "").isBlank()) {
                        response.put("result", onDocumentEvent(
                                () -> DirectAccess.execute(method, params), 30));
                        break;
                    }
                    int track = params == null ? -1 : params.optInt("track", -1);
                    double start = params == null ? Double.NaN :
                            params.optDouble("start", Double.NaN);
                    double duration = params == null ? Double.NaN :
                            params.optDouble("duration", Double.NaN);
                    JSONArray notes = params == null ? null : params.optJSONArray("notes");
                    if (track < 0 || !Double.isFinite(start) || start < 0 ||
                            !Double.isFinite(duration) || duration <= 0 ||
                            notes == null || notes.length() > 4096) {
                        throw new IllegalArgumentException("invalid arranger clip request");
                    }
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.addNoteClip(
                                    track, start, duration, notes))));
                }
                default -> response.put("error", new JSONObject()
                        .put("code", -32601).put("message", "method unavailable: " + method));
                }
            } catch (Throwable failure) {
                response.put("error", new JSONObject().put("code", -32000)
                        .put("message", describeFailure(failure)));
            }
            send(output, response);
        }
    }

    private static void send(BufferedWriter output, JSONObject value) throws Exception {
        output.write(value.toString());
        output.write('\n');
        output.flush();
    }

    private static String describeFailure(Throwable failure) {
        StringBuilder details = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!details.isEmpty()) {
                details.append(" caused by ");
            }
            details.append(cause);
            StackTraceElement[] stack = cause.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, 8); i++) {
                details.append(" at ").append(stack[i]);
            }
        }
        return details.toString();
    }

    private static <T> T onDocumentEvent(Callable<T> action) throws Exception {
        return onDocumentEvent(action, 4);
    }

    private static <T> T onDocumentEvent(Callable<T> action, long timeoutSeconds) throws Exception {
        CompletableFuture<T> completed = new CompletableFuture<>();
        DirectAccess.dispatch(() -> {
            try {
                completed.complete(action.call());
            } catch (Throwable failure) {
                completed.completeExceptionally(failure);
            }
        });
        return completed.get(timeoutSeconds, TimeUnit.SECONDS);
    }
}
