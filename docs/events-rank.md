# Events role

The SU rank ID is `events`; its visible prefix is dark-blue `§1[Ивенты]`. It supplies
self `/fly`, `/god`, `/nv`, `cointcore.cluster.guest_bypass` for ordinary play on Alpha
and Endgame, and `cointcore.cluster.hub_bypass` for moving beyond the Hub boundary.
Other teams' claim protection, existing epochs and paid ranks keep their usual rules.
It grants neither OP nor an administrative parent rank.

The game mode and WorldEdit rules use a CointCore-supported literal value:

```text
command.gamemode: hub
command.minecraft.gamemode: hub
command.worldedit: hub
worldedit: hub
```

This value applies only to the game mode and WorldEdit permission namespaces. The
resolved SU rank value is checked against the configured Hub ID and a running cluster.
It grants access on Hub and denies it elsewhere, including when WorldEdit's default
provider would grant tools merely because the user is creative. Ordinary `true` and
`false` values retain their original behavior. An independently higher-priority staff
rule keeps its normal authority. WorldEdit is optional; its actor check is installed
only when the mod is present.

No local rank overlays or separate Hub assignments are used: CointCore's existing
access synchronization distributes exactly the same SU definition to every node.
Node checks do not turn the stored `hub` value into a cached global boolean.

## Applying

1. Update CointCore on Alpha, Endgame, Hub and clients at the planned restart.
2. Upload `tools/configure-events-rank.py` to the server. On the access authority,
   normally Alpha, run it with `--rank-file /path/to/serverutilities/server/ranks.txt`.
   Preview is the default; `--apply` appends the reviewed rank after creating an exact
   sibling backup. A conflicting existing `[events]` section is refused.
3. In Alpha's console run `reload serverutilities:ranks`, then
   `ranks add <nickname> events`. This adds a parent without removing existing ranks.
   Reconnect after access synchronization, so the guest state is recalculated.

Check with a non-OP account: blue prefix in chat/tab; self abilities and ordinary
interaction on Alpha and Endgame; `/gamemode 1`, `/gamemode 0` and a small WorldEdit
selection on Hub; denial of game mode changes and WorldEdit commands/tools on the
other nodes. Removing the role and reloading must remove its privileges. An account
with a separate administrator role or OP has that role's independent privileges.

Local verification uses real SU, actual command execution and a disposable Forge
world. With WorldEdit v0.0.8 present it also exercises its real actor permission gate
and a small block edit. These are local checks, not production acceptance tests.
