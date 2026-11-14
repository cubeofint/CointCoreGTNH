# Cluster donations

All nodes must use the same `accessAuthorityNodeId` (production: `alpha`). Website RCON commands go only to that
authority. Do not duplicate purchases on Endgame. Access synchronization now includes temporary-rank metadata and
the purchased-kit delivery ledger in the existing database snapshot; no new SQL table is required.

## Rank scheduling

`trank give <player> <rank> <duration>` and `trank extend <player> <rank> <duration>` accept purchases for a name that
has not yet joined. An internal pending identity is resolved to the authenticated UUID from `cluster_seen`; it is not
an offline-mode UUID and is never assigned SU permissions. Active purchased time starts at purchase, not first login.
Ambiguous names are rejected during resolution. Known players keep their UUID even when offline.

Donor priority, highest first: singularity, fusion, quantum, plutonium, uranium. Other ranks (including staff and epochs)
are outside this queue. Only the highest available donor status runs. Lower status durations pause, including purchases
made while a higher status is active. Extending a queued status adds to its saved duration. Expiry resumes the next
status, accounting for elapsed time during server downtime. Expiration is checked by the existing minute task.

The authority owns `cointcore/temp_ranks.json`. Before the first rewrite, the original is copied to
`cointcore/temp_ranks.before-donor-queue.json`. Legacy concurrent timers retain their remaining time at conversion;
this does not reconstruct already elapsed time from historical orders. Replicas expose `/trank list` from the shared
snapshot and reject local writes. Their old local files are preserved. An empty list immediately after startup can
mean the first database snapshot has not arrived yet.

Website templates:

```
trank give {player} {rank_name} {duration_months}mo
trank extend {player} {rank_name} {duration_months}mo
```

## Purchased kits

With cluster access synchronization enabled, `kit add` on the authority records purchased claims instead of changing
that node's copy of player NBT. The journal is `cointcore/donation-kit-grants.json`. Credits wait until the player is online
on their home node. Missing kit definitions are logged and leave credits pending; install the same kit definitions on
the playable nodes. Hub/guest visits do not redeem credits. `kit set` retains its local administrative semantics.

Each grant has an ID. Its receipt and the incremented kit counter are written together into player persisted NBT, so
repeated snapshots and later migrations do not add the same credit again. Paid claims also work for kits with a finite
default limit and are consumed before free claims. Keep `cointcore_donation_receipts` when restoring/migrating player
data; deleting receipts independently from the paid balance can replay credits.

An optional fifth argument deduplicates repeated website delivery commands:

```
kit add fusion {player} {duration_months} {transaction_id}:{package_id}
```

The kit name is included in the internal ID, so the same order can grant several different kits. Reusing an ID with a
different player or amount is rejected. The amount must represent the total intended grant for that order/product/kit;
repeating an identical command to represent quantity will be deduplicated. Existing four-argument commands remain
accepted, but separate invocations generate separate IDs. Rank commands do not yet accept an order ID.

This protects kit delivery, not the website wallet. Double-click payment locking/refunds require changes in the website.
Previously delivered local kit counters are not automatically imported into the new ledger: reconcile historical orders
and both player copies before issuing missing credits, to avoid duplicates.

## Rollout and verification

Back up authority rank and donation files, SU files, and player data. Update Alpha, Endgame, Hub, and the distributed
client with the regular JAR. Keep `accessAuthorityNodeId=alpha` consistent and the website on Alpha RCON.

Use test commands/test accounts instead of charging a real wallet:

1. Grant uranium, then fusion. Compare `trank list` on Alpha and Endgame: lower status shows a frozen queue duration.
2. Extend uranium while queued; expire/revoke fusion and check uranium resumes, including after a restart.
3. Check staff/epoch assignments are retained and the active donor prefix/rights appear in chat and Tab.
4. Buy a kit for an Endgame resident while offline. On their next home-node login, the purchased count appears.
5. Retry the same five-argument kit command, reconnect, and restart: no extra credit should appear.
6. Make a purchase for a new name, then join for the first time; identity binding should activate rights/kit delivery.
7. Move a test team after receiving credits and verify receipt/balance preservation on the destination.

Local validation: `tools/tests/donation-tests.gradle` runs scheduling regression tests. The isolated server probe also
checks real SU application, restart persistence, pending identity binding, replica state, and kit delivery/replay.
These do not constitute a live website/MySQL multi-node acceptance test.
