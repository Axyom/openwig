package io.github.axyom.openwigagent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * The agent's MCP endpoint: forwards everything to the OpenWig Bridge's MCP server and holds each project edit
 * (a bitwig_call of a method the bridge lists as [edit]) until the user allows or denies it in the panel.
 */
final class GateProxy {
    static final int PORT = Integer.getInteger("openwig.agent.port", 7780);
    static final String UPSTREAM = "http://127.0.0.1:" + Integer.getInteger("openwig.mcp.port", 7779) + "/mcp";
    private static final Pattern READ_LINE = Pattern.compile("^([a-z_]+\\.[a-z_.]+) \\[read\\]", Pattern.MULTILINE);
    private static final Set<String> READ_TOOLS = Set.of("bitwig_methods", "bitwig_project", "bitwig_transport");
    private static volatile Set<String> readMethods = Set.of();
    private static HttpServer server;

    /** Asks the user about one edit: (method, params) to a future that completes with true to allow it. */
    private final BiFunction<String, JSONObject, CompletableFuture<Boolean>> ask;

    GateProxy(BiFunction<String, JSONObject, CompletableFuture<Boolean>> ask) { this.ask = ask; }

    synchronized void start() throws IOException {
        if (server != null) return;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/mcp", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "openwig-agent-gate");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if ("POST".equals(exchange.getRequestMethod())) {
                JSONObject request = parse(body);
                String denial = request == null ? null : gate(request);
                if (denial != null) {
                    JSONObject result = new JSONObject().put("content", List.of(Map.of("type", "text", "text", denial)))
                            .put("isError", true);
                    reply(exchange, 200, "application/json",
                            new JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result).toString());
                    return;
                }
            }
            forward(exchange, body);
        } catch (RuntimeException failure) {
            AgentPanels.LOG.log("gate failed", failure);
            reply(exchange, 500, "text/plain", String.valueOf(failure));
        }
    }

    /** Null to let the request through, else the message a denied or failed edit returns. */
    private String gate(JSONObject request) {
        if (!"tools/call".equals(request.optString("method"))) return null;
        JSONObject params = request.optJSONObject("params");
        String tool = params == null ? "" : params.optString("name");
        if (READ_TOOLS.contains(tool)) return null;
        JSONObject args = params == null ? new JSONObject() : params.optJSONObject("arguments");
        if (args == null) args = new JSONObject();
        String method = tool.equals("bitwig_call") ? args.optString("method") : tool;
        if (tool.equals("bitwig_call") && isRead(method)) return null;
        JSONObject callParams = args.optJSONObject("params");
        try {
            boolean allowed = ask.apply(method, callParams == null ? new JSONObject() : callParams).get();
            return allowed ? null : "The user denied " + method + ". Ask what they want instead.";
        } catch (Exception interrupted) {
            return "No answer for " + method + " (the turn was stopped).";
        }
    }

    private static boolean isRead(String method) {
        if (readMethods.isEmpty()) loadReadMethods();
        return readMethods.contains(method);
    }

    /** The bridge's own method list says which methods only read. */
    private static synchronized void loadReadMethods() {
        if (!readMethods.isEmpty()) return;
        try {
            String session = null;
            JSONObject init = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                    .put("params", new JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", new JSONObject())
                            .put("clientInfo", new JSONObject().put("name", "openwig-agent").put("version", "0")));
            HttpURLConnection c = post(init.toString(), null);
            session = c.getHeaderField("Mcp-Session-Id");
            readAll(c);
            JSONObject call = new JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
                    .put("params", new JSONObject().put("name", "bitwig_methods").put("arguments", new JSONObject()));
            JSONObject response = new JSONObject(json(readAll(post(call.toString(), session))));
            String text = response.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text");
            Set<String> found = new HashSet<>();
            Matcher m = READ_LINE.matcher(text);
            while (m.find()) found.add(m.group(1));
            readMethods = Set.copyOf(found);
            AgentPanels.LOG.log("gate: " + found.size() + " read-only methods pass without asking");
        } catch (Exception failure) {
            AgentPanels.LOG.log("gate could not read the bridge's method list; every call will ask", failure);
        }
    }

    private static HttpURLConnection post(String body, String session) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(UPSTREAM).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json, text/event-stream");
        c.setRequestProperty("MCP-Protocol-Version", "2025-06-18");
        if (session != null) c.setRequestProperty("Mcp-Session-Id", session);
        try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        return c;
    }

    /** A JSON body, or the data lines of an event stream. */
    private static String json(String raw) {
        if (!raw.startsWith("event:") && !raw.startsWith("data:")) return raw;
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\n")) if (line.startsWith("data:")) data.append(line.substring(5).trim());
        return data.toString();
    }

    /** Passes the request to the bridge as it is, headers included, and the answer back. */
    private static void forward(HttpExchange exchange, byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(UPSTREAM).openConnection();
        c.setRequestMethod(exchange.getRequestMethod());
        c.setReadTimeout(0);
        for (Map.Entry<String, List<String>> h : exchange.getRequestHeaders().entrySet()) {
            String name = h.getKey();
            if (name == null || name.equalsIgnoreCase("Host") || name.equalsIgnoreCase("Content-Length")
                    || name.equalsIgnoreCase("Origin") || name.equalsIgnoreCase("Connection")) continue;
            for (String v : h.getValue()) c.addRequestProperty(name, v);
        }
        if (body.length > 0) {
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
        }
        int status = c.getResponseCode();
        for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
            String name = h.getKey();
            if (name == null || name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding")
                    || name.equalsIgnoreCase("Connection")) continue;
            for (String v : h.getValue()) exchange.getResponseHeaders().add(name, v);
        }
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] answer = in == null ? new byte[0] : in.readAllBytes();
        exchange.sendResponseHeaders(status, answer.length == 0 ? -1 : answer.length);
        if (answer.length > 0) try (OutputStream out = exchange.getResponseBody()) { out.write(answer); }
    }

    private static String readAll(HttpURLConnection c) throws IOException {
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static JSONObject parse(byte[] body) {
        try { return new JSONObject(new String(body, StandardCharsets.UTF_8)); }
        catch (RuntimeException notJson) { return null; }
    }

    private static void reply(HttpExchange exchange, int status, String type, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
