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
import org.json.JSONObject;

/** Minimal in-process protocol endpoint. Song operations are added only after live validation. */
public final class Bootstrap {
    // Keep the prototype separate from the existing controller until parity is proven.
    private static final int PORT = 7778;
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
            switch (method) {
                case "ping" -> response.put("result", "pong");
                case "host.version" -> response.put("result",
                        new JSONObject().put("version", "6.1.5"));
                case "bridge.capabilities" -> response.put("result",
                        new JSONObject().put("backend", "java").put("song_edit", false));
                default -> response.put("error", new JSONObject()
                        .put("code", -32601).put("message", "method unavailable: " + method));
            }
            send(output, response);
        }
    }

    private static void send(BufferedWriter output, JSONObject value) throws Exception {
        output.write(value.toString());
        output.write('\n');
        output.flush();
    }
}
