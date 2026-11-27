package io.github.axyom.openwigagent;

import io.github.axyom.openwig.McpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One discussion with the user's own Claude Code: a `claude -p` process speaking stream-json, whose only tools are
 * the OpenWig Bridge's MCP tools (with this mod's mod_* tools). The session ID is kept, so a later message (or a
 * Bitwig restart) resumes it.
 */
final class Agent {
    /** What the panel shows: text to add to the transcript. */
    interface View {
        void text(String text);          // streamed assistant text, appended as it comes
        void line(String line);          // a full line (user message, tool call, status)
        void busy(boolean busy);
    }

    private static final String SYSTEM_PROMPT = String.join("\n",
            "You are an assistant inside Bitwig Studio. You change the open project only through the OpenWig tools.",
            "Call bitwig_methods first to see what you can do, then bitwig_call to do it.",
            "To make a Bitwig mod (a change to Bitwig itself): read mod_docs (writing.md, mods.md), write the files with",
            "mod_write_file, run mod_build until it prints PREPARE_OK, then mod_install. Tell the user to restart Bitwig.",
            "Keep replies short: one or two sentences, plain text, no Markdown.");

    private final Path dir;
    private final View view;
    private Process process;
    private OutputStream stdin;
    private String sessionId;
    private boolean started; // the session exists in Claude Code (resume it) or not yet (start it)
    private boolean configWritten; // system.md and mcp.json, written once per Bitwig run

    Agent(Path dir, View view) {
        this.dir = dir;
        this.view = view;
        loadSession();
    }

    synchronized void send(String message) {
        try {
            if (process == null || !process.isAlive()) launch();
            JSONObject input = new JSONObject().put("type", "user").put("message", new JSONObject().put("role", "user")
                    .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", message))));
            stdin.write((input + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            view.busy(true);
        } catch (IOException failure) {
            AgentPanels.LOG.log("agent: could not send", failure);
            view.line("[could not reach Claude Code: " + failure.getMessage() + "]");
            stop();
        }
    }

    /** Stops the running turn by ending the process; the next message resumes the session. */
    synchronized void stop() {
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
        }
        process = null;
        view.busy(false);
    }

    /** Forgets the session: the next message starts a new discussion. */
    synchronized void reset() {
        stop();
        newSession();
        saveSession();
    }

    private void newSession() {
        sessionId = UUID.randomUUID().toString();
        started = false;
    }

    private void launch() throws IOException {
        if (!configWritten) {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("system.md"), SYSTEM_PROMPT);
            JSONObject mcp = new JSONObject().put("mcpServers", new JSONObject().put("openwig",
                    new JSONObject().put("type", "http").put("url", "http://127.0.0.1:" + McpServer.port() + "/mcp")));
            Files.writeString(dir.resolve("mcp.json"), mcp.toString(2));
            configWritten = true;
        }
        boolean resume = started;
        List<String> cmd = new ArrayList<>(List.of("claude", "-p",
                "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
                "--system-prompt-file", "system.md",
                "--tools", "",
                "--mcp-config", "mcp.json", "--strict-mcp-config",
                "--allowedTools", "mcp__openwig__*", "--permission-prompts", "none",
                "--setting-sources", "", "--disable-slash-commands"));
        cmd.addAll(resume ? List.of("--resume", sessionId) : List.of("--session-id", sessionId));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(false);
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> {
            String u = k.toUpperCase();
            return (u.startsWith("ANTHROPIC_") || u.startsWith("CLAUDE_CODE_"))
                    && !u.equals("CLAUDE_CODE_OAUTH_TOKEN") && !u.equals("CLAUDE_CODE_GIT_BASH_PATH");
        });
        env.put("CLAUDE_CODE_DISABLE_CLAUDE_MDS", "1");       // none of the user's CLAUDE.md files
        env.put("CLAUDE_CODE_DISABLE_AUTO_MEMORY", "1");      // nor their memory
        env.put("MCP_TOOL_TIMEOUT", "86400000");              // mod_build can run for minutes
        env.put("CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT", "0");
        env.put("NO_PROXY", "127.0.0.1,localhost");
        process = pb.start();
        stdin = process.getOutputStream();
        if (!started) {
            started = true;
            saveSession();
        }
        Process mine = process;
        daemon("openwig-agent-out", () -> read(mine));
        daemon("openwig-agent-err", () -> eachLine(mine.getErrorStream(), line -> AgentPanels.LOG.log("claude: " + line)));
        AgentPanels.LOG.log("agent: claude started (" + (resume ? "resumed" : "new") + " session)");
    }

    private void read(Process mine) {
        eachLine(mine.getInputStream(), line -> {
            try { handle(new JSONObject(line)); }
            catch (RuntimeException notJson) { AgentPanels.LOG.log("agent: " + line); }
        });
        synchronized (this) {
            if (process == mine) { process = null; view.busy(false); }
        }
    }

    /** Each line of a process stream until it ends. */
    private static void eachLine(InputStream stream, Consumer<String> each) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) each.accept(line);
        } catch (IOException ended) {
            // the process ended
        }
    }

    private void handle(JSONObject m) {
        switch (m.optString("type")) {
            case "stream_event" -> {
                JSONObject e = m.optJSONObject("event");
                String type = e == null ? "" : e.optString("type");
                if (type.equals("content_block_delta")) {
                    JSONObject d = e.optJSONObject("delta");
                    if (d != null && "text_delta".equals(d.optString("type"))) view.text(d.optString("text"));
                } else if (type.equals("content_block_stop")) {
                    view.text("\n");
                }
            }
            case "assistant" -> {
                JSONObject message = m.optJSONObject("message");
                JSONArray blocks = message == null ? null : message.optJSONArray("content");
                if (blocks == null) return;
                for (int i = 0; i < blocks.length(); i++) {
                    JSONObject b = blocks.optJSONObject(i);
                    if (b != null && "tool_use".equals(b.optString("type"))) view.line("  > " + toolLine(b));
                }
            }
            case "result" -> {
                if (m.optBoolean("is_error") || !"success".equals(m.optString("subtype")))
                    view.line("[" + (m.has("result") ? m.optString("result") : m.optString("subtype")) + "]");
                view.busy(false);
            }
            default -> { }
        }
    }

    private static String toolLine(JSONObject block) {
        String name = block.optString("name").replace("mcp__openwig__", "");
        JSONObject input = block.optJSONObject("input");
        if (name.equals("bitwig_call") && input != null) {
            JSONObject p = input.optJSONObject("params");
            return input.optString("method") + (p == null || p.isEmpty() ? "" : " " + p);
        }
        return name;
    }

    private void loadSession() {
        try {
            JSONObject saved = new JSONObject(Files.readString(dir.resolve("session.json")));
            sessionId = saved.getString("session");
            started = saved.optBoolean("started");
        } catch (Exception none) {
            newSession();
        }
    }

    private void saveSession() {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("session.json"), new JSONObject().put("session", sessionId).put("started", started).toString());
        } catch (IOException failure) {
            AgentPanels.LOG.log("agent: could not save the session", failure);
        }
    }

    static void daemon(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
    }

    /** Ends Claude Code when Bitwig quits. */
    void killOnExit() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "openwig-agent-exit"));
    }
}
