# KubeJS Reload Transaction Integration

The standard CLIENT reload path is wrapped by optional pinned-version mixins. The Forge 1.20.1 source remains the baseline; Stonecutter selects the NeoForge 1.21.1 API adapters. This transaction protects RigWorks controller registrations, not all KubeJS side effects.

## Supported API Shapes

| Integration | Forge 1.20.1 | NeoForge 1.21.1 |
| --- | --- | --- |
| Inspected KubeJS | `2001.6.5-build.26` | `2101.7.2-build.381` |
| Plugin | `KubeJSPlugin` base class | `plugin.KubeJSPlugin` interface |
| Bindings | `BindingsEvent.getType()` | `BindingRegistry.type()` |
| Standard client reload invocation | `ScriptManager.reload(ResourceManager)` | `ScriptManager.reload()` |
| Console class | `util.ConsoleJS` | `script.ConsoleJS` |
| Console log parameters | `LogType, Throwable, Object` | `LogType, SourceLine, Throwable, Object` |
| Muted throwable-error hook | `String, Throwable, Pattern` | `String, SourceLine, Throwable, Pattern` |
| Standalone avatar Rhino | Forge Rhino context and `WrapFactory` | `Context(ContextFactory)` with visibility/wrapping overrides |

The 2101 signatures and the actual `KubeJSClient` reload invocation were checked in the locally resolved KubeJS and Rhino JARs with JDK 21 `javap -p -s/-c`. Its `ConsoleJS` throwable overload checks `shouldPrint()` before logging, so intercepting only `log()` would lose muted failures. The hook is placed before that check.

Both loader nodes now pass their parent build and tests. The 2026-11-19 NeoForge isolated client acceptance also passed the actual standard reload entry, transformed redirect and error hooks; its generated fixture was removed on completion. Normal Lusia clients load their authored script without the generated acceptance script. These results do not establish compatibility with uninspected KubeJS versions.

## Forge Bytecode Observations

Local bytecode observations from `kubejs-forge-2001.6.5-build.26.jar`, Maven coordinate `dev.latvian.mods:kubejs-forge:2001.6.5-build.26`, SHA directory `6986aef8f4a918b8df0e2ed9dc82244c5ac740da`. Inspected with `jar tf` and `javap -p -c`. REA tooling was unavailable in this session; these are direct local bytecode observations, not REA runtime evidence.

| Location | Observed behavior |
| --- | --- |
| `ScriptManager.reload(ResourceManager)`, bytecode 0-129 | Clears plugin caches, calls `unload()`, loads directory/resource script definitions, then calls `load()` |
| `ScriptManager.unload()`, 0-21 | Clears packs, calls `ScriptType.unload()`, clears Java class cache |
| `ScriptType.unload()`, 0-87 | Resets console and clears every event handler for that script type |
| `ConsoleJS.resetFile()`, 0-36 | Clears errors/warnings before scheduling log reset |
| `ScriptManager.load()`, 8-33 | Enters a new Rhino context, replaces `context` and `topLevelScope`, updates the current-context thread local |
| `ScriptManager.load()`, 196-218 | Calls plugins' wrappers, bindings and custom Java-to-JS wrapper registration |
| `ScriptManager.load()`, 468-532 | Calls `ScriptFile.load()`; catches `Throwable`, logs it and continues other files |
| `ScriptManager.load()`, 538-566 | Also catches pack-level exceptions, logs and continues |
| `ScriptManager.load()`, 569-646 | Logs success/total counts and captured console errors; returns `void` |
| `ScriptManager.loadFile()`, 0-91 | Preload failures are caught and logged before `load()` begins |
| `KubeJSPlugin` public methods | No public client reload-completed/success callback in this pinned version |

Console error collection is conditional on `capturingErrors`; checking `console.errors.isEmpty()` alone cannot establish success for every configuration. A normal return from `reload()` also cannot establish success because script exceptions were swallowed.

Wrapped `registerBindings(CLIENT)` exposes the binding without clearing published callbacks. Forge 2001 retains its legacy clear-on-bind behavior outside the transaction. KubeJS 2101 binds additional callback contexts after loading, so its binding hook must not clear the registry. Its inspected standard reload entry owns replacement, including a successful empty load; direct third-party manager calls must use the transaction adapter.

## Implemented Path

`KubeJSClient.reloadClientScripts()` calls the manager reload invocation shown in the table. A vanilla Mixin `@Redirect` wraps that exact invocation through the loader-specific `ClientScriptReload.reload` overload, providing `try/finally` transaction lifetime without adding a MixinExtras dependency. A second optional mixin observes CLIENT `ConsoleJS.log(ERROR, ...)` before its optional capture logic and the throwable error overload before its muted-console early return. Escaped exceptions also discard staging.

Registration, selection, unregister and clear operations on the loading thread target an empty staging registry. Successful completion atomically publishes it, including a successful empty script set, and increments revision once. Failed reloads preserve the published callbacks, active selection, revision and existing session state. Nested wrapper calls share staging and commit at the outer boundary. Concurrent live changes on another thread reject a stale commit. `RigWorks.reloadFailure()` reports the latest rejected reload.

The mixin plugin activates only for the inspected KubeJS version of its loader node and has no KubeJS class references, so installations without KubeJS do not resolve optional targets. A different KubeJS version requires verification of its entry point and log method. Direct third-party calls to the manager bypassing KubeJSClient must use `ClientScriptReload.reload` explicitly for transactional behavior; automatic wrapping covers the pinned standard CLIENT entry only.

`CharacterReloadTransactionTest` exercises staging, rollback, empty commit, nested loads, concurrent changes and actual pinned Rhino closures. Its closure test creates two distinct contexts/scopes, causes a syntax failure in the replacement scope and invokes the retained original Java Consumer afterward. `RhinoTestContexts` selects the context-construction and Java-to-JS API of each node so the same behavioral assertions can run for both.

`KubeJSReloadRuntimeProbe.verify()` adds explicit development acceptance through `KubeJSClient.reloadClientScripts()` itself, including its transformed redirect, real manager reload and console hook. It verifies a two-file failure, a preload ERROR, context replacement, retained closure invocation and zero-script commit. It runs only on the client thread with `rigworks.kubejsSmoke=true`; the parent acceptance harness may call it directly. It temporarily substitutes an isolated fixture manager and restores the previous manager and RigWorks controller registry afterward. Forge fixtures use `ScriptSource` in memory. KubeJS 2101 removed that interface, so its fixture constructor reads a temporary JS file into `ScriptFile.lines`; the file is removed immediately afterward. The probe never edits a player's installed scripts. Run in a disposable acceptance client because KubeJS unload clears its own global CLIENT event handlers, which this mod cannot restore.

## Remote Avatar Interpreter

Downloaded `kubejs/rigworks/<avatar>/` behavior stays in a separate session for each owner. It is not injected into KubeJS global `client_scripts`. The 2101 context adapter denies Java class visibility and Java object/class/constructor wrappers through the new Context hooks, retains the 128-frame interpreter limit, and keeps instruction, CPU-time, allocation and wall-time budgets. Regex and generated Java classes remain disabled. The same JS bootstrap, immutable input, typed commands and persistent-state limits are used by both loader nodes.

`AvatarScriptRuntimeTest` covers owner isolation, native functions, mappings, mutable-input rejection, Java escape attempts, startup/tick loops, recursive calls, regex and allocation amplification attempts. These tests must pass on the target Rhino JAR before declaring the interpreter migration complete.

Rhino tail-call optimization can repeatedly create frames without invoking its branch-based instruction observer. On 2101 the overridden stack-depth query charges each frame against the existing budget. On pinned Forge 2001 that query is final, so `LegacyRhinoTailCalls` decodes compiled interpreter instruction boundaries and converts tail calls to ordinary calls before execution, retaining the 128-frame limit. It leaves operands, nested-function names and source positions intact, rejects an unknown layout, and has regression coverage for finite return values, receivers and endless recursion.

The explicit `clientKubeJsSmoke` run uses `run-kubejs-<mc>-<loader>/` by default. Its script payload is captured once for cleanup, and the generated test body is guarded by the presence of its test binding. Ordinary development clients must never load the generated acceptance script. The 2026-11-19 NeoForge runtime check passed canonical demo registration, actual manager reload, retained old closures after failure, muted errors and successful empty reload.

## Broader Integration Options

1. Add an optional KubeJS lifecycle adapter with a wrapper around `ScriptManager.reload(ResourceManager)`. Only handle `ScriptType.CLIENT`. Begin a new registration transaction before unload/preload, retain the currently published controller map, active ID and revision, and guarantee transaction end in `finally` even for escaped initialization exceptions. MixinExtras `@WrapMethod`, if already available in the pinned runtime, can provide this wrapper; otherwise wrap all actual reload entry sites with equivalent `try/finally`. An ordinary RETURN injection alone does not cover exceptions.
2. During the transaction route RigWorks registration, selection, unregister and clear operations to an empty staging registry. Bindings registration must expose the API without clearing the published registry. Do not increment live revision or reset sessions while staging. A successful empty registry is meaningful: deleting all scripts should remove the old controllers.
3. Track errors independently of console capturing. A narrowly filtered hook at `ConsoleJS` error emission, scoped to the current transaction thread and CLIENT console, can mark the transaction failed for all ERROR logs, including preload and script exceptions. Alternatively wrap `ScriptFile.load()` to mark and rethrow while also tracking preload errors. Do not mark server/startup reloads or unrelated later errors. Treat explicit `console.error` during reload as a conservative failure policy, and document it.
4. On the outer wrapper's successful completion with no recorded errors, atomically publish the staged immutable registry and active ID, clear failure diagnostics and advance live revision once. Existing sessions then retire owned pose, processor, IK, morph and physics controls through the normal revision transition. If failed or thrown, discard staging and keep live revision unchanged so the old session state and controls remain intact.
5. `load()` can also be called directly. Cover direct client loads with a nested-aware wrapper: if no outer reload transaction exists, open and finish one there; otherwise let the outer reload own completion. Track transaction ownership and thread identity so reentrant loads cannot accidentally commit an outer transaction.
6. Keep KubeJS references in the optional adapter/mixin only, and guard loading when the dependency is absent. Add tests for two-file reload where the first registers and the second fails, preload failure, zero-script success, direct-load success, thrown binding setup, explicit clear, active-ID replacement, and server/client isolation.

## Retention Boundary

Holding old Java `Consumer` callbacks keeps their original JS function/scope reachable, but KubeJS unload already clears its event handlers and plugin caches and replaces its manager context. A RigWorks transaction can preserve its own callback registry and owned controls; it cannot roll back global JS state, other mods' event handlers, side effects performed by failed scripts, or disposed external objects.

The standalone pinned-Rhino closure test verifies retained function/scope execution after replacement-context syntax failure. The runtime probe covers the full transformed KubeJS manager path when launched by the acceptance harness. Supported retained callbacks should use the tick frame, its state map, immutable definitions and ordinary captured scalar values; references to external event handlers or resources disposed by another mod are outside this guarantee.
