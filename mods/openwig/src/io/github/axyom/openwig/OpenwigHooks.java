package io.github.axyom.openwig;

import bml.api.At;
import bml.api.Bml;
import bml.api.Hook;
import com.bitwig.flt.app.BitwigStudioMain; // Bitwig's application entry point

/** OpenWig's only hook: start the bridge server when Bitwig starts. */
public final class OpenwigHooks {
    private static final Bml.Mod MOD = Bml.mod("openwig");

    private OpenwigHooks() {}

    @Hook(id = "bitwigstudiomain-main-entry", target = BitwigStudioMain.class,
          method = "main([Ljava/lang/String;)V", at = At.ENTRY)
    public static void startBridge() {
        try {
            Bootstrap.start();
            MOD.log("bridge starting on 127.0.0.1:" + Bootstrap.port());
        } catch (Throwable t) {
            MOD.log("bridge failed to start", t);
        }
        try {
            McpServer.start();
            MOD.log("mcp listening on http://127.0.0.1:" + McpServer.port() + McpServer.PATH);
        } catch (Throwable t) {
            MOD.log("mcp server failed to start", t);
        }
    }
}
