# Cluster-wide player reset

With the cluster enabled, `/playerreset` can be issued on Alpha, Endgame or Hub. Every registered node participates, including stopped nodes, the old home and the access authority. SQL schema 15 adds a persistent reset journal and an admission barrier shared with login session acquisition.

Update **all nodes** before using this feature. In every node's `cluster` config, use the same destination:

```text
S:playerResetHomeNodeId=alpha
```

This is the internal node ID, not the display name. With the cluster disabled, the existing standalone command remains local.

## Commands

```text
/playerreset <exact-name-or-uuid> all [newLeader]
/playerreset <exact-name-or-uuid> status
/playerreset <exact-name-or-uuid> cancel
```

Omitting `all` still requests a full reset. Names match exactly, ignoring case; ambiguous names require the UUID. The existing `cointcore.command.playerreset`/OP permission applies on the issuing node.

For a shared SU/BQ team owner, specify a successor who is a valid member of every affected local team/party. Other teammates, their progress and their shared base remain intact. A solo player's team and personal dimension follow the existing full-reset deletion behavior. `pdim`, `quests`, `thaum` and `data` remain available as targeted modes; only `all` reassigns the home to Alpha.

The request first blocks entry for the target, disconnects online/pending sessions and waits for logout/save on all nodes. It validates leadership and migration state before any node starts deletion. An existing open trade, pending delivery or unfinished ME operation refuses a new reset so the player can settle the transfer first.

`PREPARE` waits for all nodes. `APPLY` performs the reset, and `DONE` requires every node's durable acknowledgement. Only then is the home changed to Alpha. A stopped node keeps the reset pending until it returns. Errors and restarts do not silently unlock a partially reset account. Use `status` to see each node and its error. Retry is automatic; repeating the original command returns the existing request. `cancel` works only before `APPLY`; cancel and create another request to correct the successor during preparation.

## Data scope

The full reset covers the existing normal player reset data on each node: vanilla playerdata/stats and their caches, inventory/armor/ender inventory/XP/bed/abilities, BQ personal quest progress and party membership, Thaumcraft knowledge/warp/scans/aspects, SU personal data/homes/nickname/rank assignments and temporary ranks, free kit counters, Baubles files/cache and the personal Backpack slot. The new player save contains complete fresh entity NBT at the local overworld spawn, with position and health populated.

Paid kit balances from the previous home and purchase receipts are retained to avoid duplicating or losing pending kit deliveries. Receipts are combined across nodes; paid balances are not summed from stale copies. Shared backpack UUID stores, financial purchase journals, bans and staff OP records are retained. The command does not erase other mods' global/shared datasets such as Blood Magic networks, VendingMachine wallets, global GregTech data or EnderStorage. Migration archives/backups also remain. This is not a universal wipe of every mod's state.

Pending travel and the reset player's synchronized access entries are cleared so they cannot restore the old home or permissions. Unrelated players' access synchronization continues.

Completed journal entries are retained to prevent a later node from restoring pre-reset data. Do not reuse a node ID with an unrelated/restored world or roll back only the database without reconciling acknowledgements. Consistent backups must cover worlds and the cluster database.

## Verification

Local checks use a disposable MySQL database and an isolated Forge server. SQL checks exercise migrations, exact/ambiguous identity, node barriers, disconnect/reconnect, cancellation, home reassignment, a later node, pending deliveries and concurrent login/reset attempts. Forge checks exercise fresh save roundtrip, paid receipts, actual full reset steps, SU/BQ leadership transfer, solo team removal, shared team persistence, personal dimension binding preservation and replay.

Before using on real accounts, verify with disposable accounts across Alpha/Endgame/Hub: reset from Hub, wait for `DONE`, reconnect through Hub and confirm Alpha is home; repeat with a node stopped and restarted; test a shared leader with and without a successor. Actual personal-dimension deletion and failure recovery across multiple live processes still require staging verification. Discord and production servers were not modified by these local checks.
