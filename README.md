# BileTools
Tools for making garbage

## Language and localization

Canonical English is defined in the typed Java catalog at `src/main/java/com/volmit/bile/localization/BileMessages.java`; BileTools does not ship a second English message resource. Complete bundles are included for German, Spanish, Finnish, French, Hebrew, Italian, Japanese, Korean, Lithuanian, Dutch, Polish, Portuguese, Russian, Turkish, Vietnamese, Simplified Chinese, and Traditional Chinese. Set `locale` in `language.yml` to select one. Any message entries added to that file are sparse server-specific overrides; omitted entries resolve from the selected bundle and then code-owned English.

## What the hell does this do?
* Any plugin jar files modified while loaded (maven compile or export or even drag/drop) are automatically reloaded. So now all you have to do is smack the run button and bam, its already in the game without reloading, dragging or really doing anything. Psst... Works best with multiple monitors.
* New Plugins are hot-dropped into the server when they are added to the plugins folder
* It's basically plugman also. You can unload load and reload plugins.

## Compatibility

| Runtime | Support | Notes |
|---------|---------|--------|
| **Paper 1.20.1+** | Primary | Public PluginManager load path; hot-unload remains best-effort |
| **Purpur 1.20.1+** | Primary | Paper-family (same load/unload paths) |
| **Leaf** | Primary | Paper-family fork; treated like Paper |
| **Folia 1.20.1+** | Supported | `folia-supported: true`; GlobalRegionScheduler only; hot-reload is best-effort |
| **Canvas** | Supported | Folia fork; same regionized scheduling rules as Folia |
| **Spigot 1.20.1+** | Best-effort | `paper-plugin.yml`-only jars are rejected; dual-descriptor jars load through `plugin.yml` |

* One jar supports Minecraft `1.20.1` through current `26.x` servers
* `plugin.yml` `api-version`: `1.20`
* Production compile floor: Paper and Spigot API `1.20.1` with current `26.x` compatibility compile gates
* Runtime JVM: Java 17 on `1.20.1`; newer servers still require the JVM version mandated by that server (Java 25 on `26.x`)
* Build JVM: Java 25+
* Lifecycle mutations always run on the global/main thread (never on PluginOps / network threads)
* On Folia/Canvas, player sounds/messages that touch entities are routed through the entity scheduler

### Folia / Canvas caveats
* Third-party plugins without `folia-supported: true` may still fail when hot-loaded
* Plugin reload on regionized servers is inherently riskier than on single-threaded Paper/Spigot
* Classic `Bukkit.getScheduler()` is never used on Folia/Canvas (it throws `UnsupportedOperationException`)
* Runtime hot-load preserves required/optional dependency discovery, but cannot recreate Paper's startup provider graph; missing `BEFORE`, `AFTER`, and `OMIT` dependencies are loaded first so the public PluginManager can validate the target plugin

### Watcher filters (`config.yml`)
* `watcher.ignore` — plugin names that auto hot-drop/reload/unload will skip (defaults include LuckPerms, Vault, ProtocolLib, …)
* `watcher.only` — if non-empty, **only** these plugins are auto-managed (allowlist mode)
* Manual `/bile load|unload|reload` always bypasses ignore/only
* `watcher.coalesce-window-ticks` — batch nearby jar changes into one dependency-aware reload flush
* `lifecycle.health-check` — fail reload if plugin is not actually enabled/registered after enable
* `observability.log-timings` — log unload/load/reload phase timings

### [Download](https://github.com/VolmitSoftware/BileTools/releases/)
