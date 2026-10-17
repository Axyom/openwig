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
                case "device.count" -> {
                    JSONObject params = request.optJSONObject("params");
                    int track = params == null ? -1 : params.optInt("track", -1);
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.deviceCount(track))));
                }
                case "device.snapshot" -> {
                    JSONObject params = request.optJSONObject("params");
                    int track = params == null ? -1 : params.optInt("track", -1);
                    response.put("result", onDocumentEvent(() -> DirectAccess.deviceSnapshot(track)));
                }
                case "device.set_enabled" -> {
                    JSONObject params = request.optJSONObject("params");
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
                    int track = params == null ? -1 : params.optInt("track", -1);
                    String name = params == null ? "" : params.optString("name", "");
                    if (track < 0 || name.isBlank() || name.contains("/") ||
                            name.contains("\\") || name.contains(".")) {
                        throw new IllegalArgumentException("track and factory device name are required");
                    }
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.insertFactoryDevice(track, name))));
                }
                case "track.snapshot" -> {
                    JSONObject params = request.optJSONObject("params");
                    int index = params == null ? -1 : params.optInt("index", -1);
                    if (index < 0) {
                        throw new IllegalArgumentException("main track index is required");
                    }
                    response.put("result", onDocumentEvent(() -> DirectAccess.trackSnapshot(index)));
                }
                case "track.rename", "track.set_volume", "track.set_pan", "track.set_mute",
                     "track.set_monitor", "track.set_color", "track.set_send" -> {
                    JSONObject params = request.optJSONObject("params");
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
                    int track = params == null ? -1 : params.optInt("track", -1);
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.clipCount(track))));
                }
                case "clip.note_count" -> {
                    JSONObject params = request.optJSONObject("params");
                    int track = params == null ? -1 : params.optInt("track", -1);
                    int clip = params == null ? -1 : params.optInt("clip", -1);
                    response.put("result", new JSONObject().put("count",
                            onDocumentEvent(() -> DirectAccess.noteCount(track, clip))));
                }
                case "clip.create_arranger_with_notes" -> {
                    JSONObject params = request.optJSONObject("params");
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
