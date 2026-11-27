package io.github.axyom.openwig;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MCP server over Streamable HTTP (JSON responses, no SSE stream) on 127.0.0.1.
 * Handwritten on the JDK's HTTP server and Bitwig's org.json: the official MCP Java
 * SDK needs Reactor and its own Jackson, which Bitwig's classpath already ships in
 * another version. Every tool call goes through {@link Bootstrap#respond}, the same
 * dispatch as the JSON-RPC bridge on port 7778. Other mods add their own tools with {@link #addTool}.
 */
public final class McpServer {
    static final String PATH = "/mcp";
    static final List<String> PROTOCOLS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final JSONArray BUILT_IN = builtInTools();
    private static final Object BRIDGE = new Object(); // bridge tools run one at a time, as before
    private static final List<JSONObject> EXTRA_TOOLS = new CopyOnWriteArrayList<>();
    private static final Map<String, Function<JSONObject, JSONObject>> EXTRA = new ConcurrentHashMap<>();
    private static JSONObject catalog;

    private McpServer() {}

    /** The port: system property openwig.mcp.port, else env OPENWIG_MCP_PORT, else 7779. */
    public static int port() {
        String value = System.getProperty("openwig.mcp.port", System.getenv("OPENWIG_MCP_PORT"));
        try { return value == null || value.isBlank() ? 7779 : Integer.parseInt(value.trim()); }
        catch (NumberFormatException invalid) { return 7779; }
    }

    /**
     * Adds a tool for every MCP client, for another mod to call from its own code (in Bitwig's JVM).
     * {@code tool} is the MCP tool entry (name, description, inputSchema); {@code handler} gets the call's
     * arguments and returns a tool result, for example {@link #text}. Handlers run on the server's threads,
     * several at once when a client calls tools in parallel. A name already taken is refused.
     */
    public static synchronized void addTool(JSONObject tool, Function<JSONObject, JSONObject> handler) {
        String name = tool.getString("name");
        if (EXTRA.containsKey(name) || builtIn(name)) throw new IllegalArgumentException("tool name taken: " + name);
        EXTRA.put(name, handler);
        EXTRA_TOOLS.add(tool);
    }

    private static boolean builtIn(String name) {
        for (Object t : BUILT_IN) if (((JSONObject) t).getString("name").equals(name)) return true;
        return false;
    }

    public static void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port()), 0);
        server.createContext(PATH, McpServer::exchange);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "openwig-mcp");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    private static void exchange(HttpExchange http) throws IOException {
        try (http) {
            // Local only: refuse pages from other sites (DNS rebinding) via their Origin header.
            String origin = http.getRequestHeaders().getFirst("Origin");
            if (origin != null && !localOrigin(origin)) { reply(http, 403, null); return; }
            switch (http.getRequestMethod()) {
                case "POST" -> post(http);
                case "DELETE" -> reply(http, 200, null);           // session end: nothing to free
                default -> { http.getResponseHeaders().set("Allow", "POST, DELETE"); reply(http, 405, null); }
            }
        } catch (Throwable failure) {
            try { reply(http, 500, error(null, -32603, String.valueOf(failure))); } catch (Throwable ignored) { }
        }
    }

    private static boolean localOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1"));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static void post(HttpExchange http) throws IOException {
        byte[] body;
        try (InputStream in = http.getRequestBody()) { body = in.readNBytes(MAX_BODY + 1); }
        if (body.length > MAX_BODY) { reply(http, 413, error(null, -32600, "request too large")); return; }
        Object parsed;
        try { parsed = new org.json.JSONTokener(new String(body, StandardCharsets.UTF_8)).nextValue(); }
        catch (RuntimeException invalid) { reply(http, 400, error(null, -32700, "parse error")); return; }
        if (parsed instanceof JSONArray batch) {                  // older clients may batch
            JSONArray out = new JSONArray();
            for (Object item : batch) {
                JSONObject answer = item instanceof JSONObject message ? handle(message, http) : error(null, -32600, "invalid request");
                if (answer != null) out.put(answer);
            }
            if (out.isEmpty()) reply(http, 202, null); else reply(http, 200, out);
            return;
        }
        if (!(parsed instanceof JSONObject message)) { reply(http, 400, error(null, -32600, "invalid request")); return; }
        JSONObject answer = handle(message, http);
        if (answer == null) reply(http, 202, null); else reply(http, 200, answer);
    }

    /** One JSON-RPC message; null for notifications and responses (nothing to send back). */
    static JSONObject handle(JSONObject message, HttpExchange http) {
        if (!message.has("id") || !message.has("method")) return null;
        Object id = message.get("id");
        JSONObject params = message.optJSONObject("params");
        try {
            return switch (message.getString("method")) {
                case "initialize" -> {
                    String wanted = params == null ? "" : params.optString("protocolVersion", "");
                    String version = PROTOCOLS.contains(wanted) ? wanted : PROTOCOLS.get(0);
                    if (http != null) http.getResponseHeaders().set("Mcp-Session-Id", UUID.randomUUID().toString());
                    yield result(id, new JSONObject()
                            .put("protocolVersion", version)
                            .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                            .put("serverInfo", new JSONObject().put("name", "openwig").put("version", "0.1.0-dev.1"))
                            .put("instructions", "Controls the open Bitwig Studio project through OpenWig's bridge. "
                                    + "Call bitwig_methods first to see what can be done, then bitwig_call with a method and its params. "
                                    + "Edits apply to the selected project immediately, each as one undo step in Bitwig."));
                }
                case "ping" -> result(id, new JSONObject());
                case "tools/list" -> result(id, new JSONObject().put("tools", tools()));
                case "tools/call" -> result(id, call(params == null ? new JSONObject() : params));
                default -> error(id, -32601, "method not found: " + message.getString("method"));
            };
        } catch (RuntimeException failure) {
            return error(id, -32603, String.valueOf(failure));
        }
    }

    private static JSONArray tools() {
        JSONArray all = new JSONArray();
        BUILT_IN.forEach(all::put);
        EXTRA_TOOLS.forEach(all::put);
        return all;
    }

    private static JSONArray builtInTools() {
        JSONObject noArgs = new JSONObject().put("type", "object").put("properties", new JSONObject());
        return new JSONArray()
            .put(tool("bitwig_methods",
                "List what OpenWig can do in Bitwig: every bridge method by area, whether it reads or edits, and a one-line description. "
                + "Call this first, then use bitwig_call. Optional 'area' filters to one area (for example device, clip, transport).",
                new JSONObject().put("type", "object").put("properties", new JSONObject()
                    .put("area", new JSONObject().put("type", "string").put("description", "Only this area, e.g. 'track' or 'device'.")))))
            .put(tool("bitwig_call",
                "Run one OpenWig bridge method on the open Bitwig project (names from bitwig_methods) and return its JSON result. "
                + "Tracks are addressed by 'track' (index) or 'path' (titles joined by '/'), devices by 'device' (index). "
                + "If a parameter is missing or wrong, the error says which; adjust and call again.",
                new JSONObject().put("type", "object")
                    .put("properties", new JSONObject()
                        .put("method", new JSONObject().put("type", "string").put("description", "Bridge method, e.g. 'track.create'."))
                        .put("params", new JSONObject().put("type", "object").put("description", "Method parameters.")))
                    .put("required", new JSONArray().put("method"))))
            .put(tool("bitwig_project",
                "Summary of the selected Bitwig project: file, tempo and time signature, tracks with their devices and clip counts, effect tracks, master devices, scenes and cue markers.",
                noArgs))
            .put(tool("bitwig_transport", "Playback state: playing, position in beats, loop on/off and loop region.", noArgs));
    }

    private static JSONObject tool(String name, String description, JSONObject schema) {
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", schema);
    }

    private static JSONObject call(JSONObject params) {
        String name = params.optString("name", "");
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) args = new JSONObject();
        try {
            Function<JSONObject, JSONObject> extra = EXTRA.get(name);
            if (extra != null) return extra.apply(args);
            synchronized (BRIDGE) {
                return switch (name) {
                    case "bitwig_methods" -> text(methods(args.optString("area", "")), false);
                    case "bitwig_call" -> {
                        String method = args.optString("method", "");
                        yield method.isBlank() ? text("'method' is required; see bitwig_methods.", true)
                                : toolResult(bridge(method, args.optJSONObject("params")));
                    }
                    case "bitwig_project" -> project();
                    case "bitwig_transport" -> toolResult(bridge("transport.snapshot", null));
                    default -> text("unknown tool: " + name, true);
                };
            }
        } catch (RuntimeException failure) {
            return text(String.valueOf(failure), true);
        }
    }

    private static JSONObject bridge(String method, JSONObject params) {
        JSONObject request = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method);
        if (params != null) request.put("params", params);
        return Bootstrap.respond(request);
    }

    /** A bridge response as a tool result: its error message, else its result. */
    private static JSONObject toolResult(JSONObject response) {
        return response.has("error") ? text(response.getJSONObject("error").optString("message"), true)
                : text(String.valueOf(response.opt("result")), false);
    }

    private static JSONObject project() {
        JSONObject info = bridge("document.info", null);
        if (info.has("error")) return toolResult(info);
        JSONObject dump = bridge("project.dump", null);
        if (dump.has("error")) return toolResult(dump);
        JSONObject d = dump.getJSONObject("result");
        JSONObject out = new JSONObject().put("project", info.get("result"))
                .put("transport", d.opt("transport")).put("metadata", d.opt("metadata"))
                .put("tracks", summarize(d.optJSONArray("main_tracks")))
                .put("effect_tracks", summarize(d.optJSONArray("effect_tracks")))
                .put("master_devices", deviceNames(d.optJSONObject("master")))
                .put("scenes", d.optInt("scene_count", count(d, "scenes")))
                .put("cue_markers", count(d, "cue_markers"));
        return text(out.toString(), false);
    }

    private static int count(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        return a == null ? 0 : a.length();
    }

    private static JSONArray summarize(JSONArray tracks) {
        JSONArray out = new JSONArray();
        if (tracks == null) return out;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.getJSONObject(i);
            JSONObject item = new JSONObject().put("index", i).put("name", t.opt("name")).put("type", t.opt("track_type"))
                    .put("devices", deviceNames(t));
            JSONArray clips = t.optJSONArray("clips");
            if (clips != null) item.put("clips", clips.length());
            JSONArray children = t.optJSONArray("children");
            if (children != null && !children.isEmpty()) item.put("children", summarize(children));
            out.put(item);
        }
        return out;
    }

    private static JSONArray deviceNames(JSONObject track) {
        JSONArray out = new JSONArray();
        JSONArray devices = track == null ? null : track.optJSONArray("devices");
        if (devices == null) return out;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.getJSONObject(i);
            Object name = device.opt("display_name");
            out.put(name == null ? device.opt("device_name") : name);
        }
        return out;
    }

    static synchronized JSONObject catalog() {
        if (catalog == null) {
            try (InputStream in = McpServer.class.getResourceAsStream("methods.json")) {
                if (in == null) throw new IllegalStateException("methods.json missing from the helper jar");
                catalog = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new IllegalStateException("cannot read methods.json", failure);
            }
        }
        return catalog;
    }

    static String methods(String area) {
        JSONObject all = catalog();
        JSONObject areas = all.getJSONObject("areas");
        StringBuilder out = new StringBuilder();
        String current = null;
        for (Object item : all.getJSONArray("methods")) {
            JSONObject m = (JSONObject) item;
            String name = m.getString("method"), ns = name.substring(0, name.indexOf('.'));
            if (!area.isBlank() && !ns.equals(area.trim())) continue;
            if (!ns.equals(current)) {
                current = ns;
                out.append(out.isEmpty() ? "" : "\n").append("## ").append(ns);
                String about = areas.optString(ns, "");
                if (!about.isEmpty()) out.append(": ").append(about);
                out.append('\n');
            }
            out.append(name).append(" [").append(m.getString("kind")).append("] ").append(m.getString("description")).append('\n');
        }
        return out.isEmpty() ? "no methods in area '" + area + "'; areas: " + String.join(", ", areas.keySet()) : out.toString();
    }

    /** A tool result holding one text block. */
    public static JSONObject text(String value, boolean isError) {
        return new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", value)))
                .put("isError", isError);
    }

    private static JSONObject result(Object id, JSONObject value) {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", value);
    }

    private static JSONObject error(Object id, int code, String message) {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id)
                .put("error", new JSONObject().put("code", code).put("message", message));
    }

    private static void reply(HttpExchange http, int status, Object body) throws IOException {
        if (body == null) { http.sendResponseHeaders(status, -1); return; }
        byte[] bytes = (body instanceof JSONObject json ? json.toString() : String.valueOf(body)).getBytes(StandardCharsets.UTF_8);
        http.getResponseHeaders().set("Content-Type", "application/json");
        http.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = http.getResponseBody()) { out.write(bytes); }
    }
}
