package cn.minerealms.rigworks.debug;

import cn.minerealms.assimploader.debug.ClientDebugTools;
import cn.minerealms.assimploader.debug.DebugManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Debug details use INFO so the in-game switch works with the default logging configuration. */
public final class RigWorksDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger("rigworks");

    private RigWorksDiagnostics() {}

    public static boolean enabled() { return ClientDebugTools.enabled() || DebugManager.isEnabled(); }

    public static void debug(String message, Object... arguments) {
        if (enabled()) LOGGER.info("[RigWorks debug] " + message, arguments);
    }
}
