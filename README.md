# BileTools
Tools for making garbage

## What the hell does this do?
* Any plugin jar files modified while loaded (maven compile or export or even drag/drop) are automatically reloaded. So now all you have to do is smack the run button and bam, its already in the game without reloading, dragging or really doing anything. Psst... Works best with multiple monitors.
* New Plugins are hot-dropped into the server when they are added to the plugins folder
* It's basically plugman also. You can unload load and reload plugins.

## Compatibility

| Runtime | Support | Notes |
|---------|---------|--------|
| **Paper** | Primary | Full Paper plugin manager force-load + shims |
| **Purpur** | Primary | Paper-family (same load/unload paths) |
| **Leaf** | Primary | Paper-family fork; treated like Paper |
| **Folia** | Supported | `folia-supported: true`; GlobalRegionScheduler only; hot-reload is best-effort |
| **Canvas** | Supported | Folia fork; same regionized scheduling rules as Folia |
| **Spigot** | Best-effort | No Paper plugin manager; `paper-plugin.yml`-only jars use `plugin.yml` shims. `api-version` is `1.21` so Spigot will load; compiled against Paper 26.2 |

* `plugin.yml` `api-version`: `1.21` (Spigot + Paper loadable)
* Compile target: Paper API `26.2` (see `gradle/libs.versions.toml`)
* Runtime JVM: `Java 25+`
* Lifecycle mutations always run on the global/main thread (never on PluginOps / network threads)
* On Folia/Canvas, player sounds/messages that touch entities are routed through the entity scheduler

### Folia / Canvas caveats
* Third-party plugins without `folia-supported: true` may still fail when hot-loaded
* Plugin reload on regionized servers is inherently riskier than on single-threaded Paper/Spigot
* Classic `Bukkit.getScheduler()` is never used on Folia/Canvas (it throws `UnsupportedOperationException`)

### Watcher filters (`config.yml`)
* `watcher.ignore` — plugin names that auto hot-drop/reload/unload will skip (defaults include LuckPerms, Vault, ProtocolLib, …)
* `watcher.only` — if non-empty, **only** these plugins are auto-managed (allowlist mode)
* Manual `/bile load|unload|reload` always bypasses ignore/only
* `watcher.coalesce-window-ticks` — batch nearby jar changes into one dependency-ordered reload flush
* `lifecycle.health-check` — fail reload if plugin is not actually enabled/registered after enable
* `observability.log-timings` — log unload/load/reload phase timings

### [Download](https://github.com/VolmitSoftware/BileTools/releases/)
