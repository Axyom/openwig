package io.github.axyom.openwigagent;

import io.github.axyom.openwig.McpServer;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** The mod_* tools, added to the OpenWig Bridge's MCP server in Bitwig's JVM, so every MCP client gets them. */
final class ModTools {
    private ModTools() {}

    /** A mod tool's body: the call's arguments to the text it returns; an exception becomes an error result. */
    private interface Body { String run(JSONObject args) throws Exception; }

    static void register(ModBuilder mods) {
        add("mod_docs", "Read Patchbay's modder guide (how to write a Bitwig mod: mod.json, @Hook, frameworks). "
                + "Call with no name for the page list; start with writing.md and mods.md.",
                props().put("name", str("page, e.g. writing.md")), a -> mods.docs(a.optString("name")));
        add("mod_write_file", "Write one file of a mod you are making, under its own folder: mod.json, src/<package>/<Class>.java, "
                + "README.md, package/... The mod id is lowercase words joined by -. built_against must be this Bitwig "
                + "(" + mods.bitwigVersion() + ", windows, x64).",
                props().put("mod_id", str("e.g. hello-mod")).put("path", str("relative path, e.g. mod.json")).put("content", str("the whole file")),
                a -> mods.writeFile(a.getString("mod_id"), a.getString("path"), a.getString("content")), "mod_id", "path", "content");
        add("mod_read_file", "Read a file of a mod you are making, or list its files (path empty).",
                props().put("mod_id", str("mod id")).put("path", str("relative path, empty to list")),
                a -> mods.readFile(a.getString("mod_id"), a.optString("path")), "mod_id");
        add("mod_build", "Compile the mod and run Patchbay's offline check. Returns PREPARE_OK or the errors to fix.",
                props().put("mod_id", str("mod id")), a -> mods.build(a.getString("mod_id")), "mod_id");
        add("mod_install", "Install a built mod into Patchbay. It loads when Bitwig restarts.",
                props().put("mod_id", str("mod id")), a -> mods.install(a.getString("mod_id")), "mod_id");
    }

    private static void add(String name, String description, JSONObject properties, Body body, String... required) {
        JSONObject tool = new JSONObject().put("name", name).put("description", description)
                .put("inputSchema", new JSONObject().put("type", "object").put("properties", properties).put("required", new JSONArray(List.of(required))));
        McpServer.addTool(tool, args -> {
            try {
                return McpServer.text(body.run(args), false);
            } catch (Exception failure) {
                return McpServer.text(String.valueOf(failure.getMessage() == null ? failure : failure.getMessage()), true);
            }
        });
    }

    private static JSONObject props() { return new JSONObject(); }
    private static JSONObject str(String description) { return new JSONObject().put("type", "string").put("description", description); }
}
