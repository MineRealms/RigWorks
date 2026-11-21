# RigWorks Agent Notes

## Client Runtime Checks

- Use RosettaRemoteDebugBridge for in-game diagnostics and screenshots when validating a client. Read H:\MinecraftMods\RainJava-work\RosettaRemoteDebugBridge\docs\USAGE.md before using its client-debug commands.
- Stage exactly one matching ModernUI, F3Tweaker, and Rosetta JAR from the ignored dev-client-mods/<minecraft>-<loader>/ directory. runClient copies them into the selected game directory; keep these local JARs out of Git.
- Rosetta's client-debug request is initiated through the server bridge and sent to the client. In singleplayer, that server is the integrated server. Rosetta must be loaded on both sides; keep the bridge loopback-bound, use its local token without printing or sharing it, grant only the required permission, and preserve the visible per-session client confirmation. Do not bypass the in-game confirmation.
- Use Rosetta's clientdebug screenshot action for runtime captures. After a model becomes ready, wait at least 10 seconds before taking a screenshot so texture and material loading can settle.
- Test with an isolated game directory and a copied world. Do not open the same save in multiple clients.
- A completed Gradle task does not prove the client opened or reached the test scene. Check the live process, logs/latest.log, and the smoke result. Inspect an actual screenshot before claiming visual validation.
- When the user asks to inspect the running game, leave its window open after checks. Do not stop the client as part of smoke cleanup unless the user requested that behavior.

## Diagnostics

- Log failures with their exception and useful asset, animation, bone, channel, or operation context. A UI error message must not be the only diagnostic. Recoverable source-data errors must identify the affected value and fallback behavior.
- Additional RigWorks diagnostics use RigWorksDiagnostics and the existing AssimpLoader Debug GUI or /assimploader debug on switch. Keep warnings and errors visible when Debug is off. Log event boundaries and rate-limit runtime summaries; do not log per vertex, bone, or frame by default.
