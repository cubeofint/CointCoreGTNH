# VendingMachine migration

VendingMachine 0.4.100 stores personal and shared wallets in GTNHLib's team data
(`World/gtnhteams/<team UUID>.json`), not in the player's inventory or vending tile.
The migration includes a separate `cointcore-migration/vending.dat` snapshot of
the selected teams, their personal/shared wallets, trade history, and pending
notifications. It uses the live GTNHLib API when importing, then verifies the
persisted wallet data by reading it back from disk.

The source supplies the same snapshot to the destination preflight. A shared
wallet containing players outside the moving SU team, existing destination
wallet/history data, or an unsafe team regrouping blocks migration. Empty solo
teams on the destination can be combined to restore a shared wallet. Their old
files are removed and checked so they cannot reappear after a restart. Other
mods' nonempty team data is preserved rather than overwritten during regrouping.

Both game nodes must support this transfer. Preflight and move approval reject
an older destination. After successful import, the destination verifies its saved
wallets against the source snapshot and writes a separate receipt bound to the
archive SHA-256. The source requires that receipt before clearing any balances.

After confirmed import, source wallets are cleared with a retained backup. A
changed source balance blocks cleanup. A repeat of an interrupted cleanup accepts
only the original or already-cleared state backed by that same migration snapshot.
Repeated imports into nonempty wallets are rejected, even when the amounts match:
equal balances do not prove they came from the same operation.

Older archives without a vending snapshot remain importable and do not clear
source vending balances. They cannot restore balances that they never captured.
Legacy pre-GTNHLib wallet files require the player to log into that node once for
VendingMachine's own conversion before migration.

Wallet comparisons normalize JSON compound key order before ordering set-like NBT
lists. `NBTTagCompound.toString()` is not a stable ordering key: Hodgepodge's
fastutil map can iterate identical values differently after a JSON round trip.
Older retained/preflight snapshots are normalized when compared as well. Real
balance, history, membership or notification changes still fail validation.

## Recovering a failed wallet persistence comparison

An import that stopped at `Кошелёк VendingMachine не сохранился` has already
placed worlds and player files; the SU finalization stage may not have run.
Do not replay `/migrate import`, remove freezes, or attest that the whole import
is complete with `move-finish-import`.

After updating both game nodes, the destination **console** can run:

```text
migrate move-recover-wallet <move-id> confirm-reviewed
```

This narrowly scoped recovery requires the original unfinished journal, matching
local freeze/player locks, offline members and unloaded personal dimensions. It
still rejects a single-player team join. External chunks must remain frozen,
unloaded, without nearby players or foreign claims. Their provider save folders
are resolved on the main thread without loading worlds.
It verifies the archived SHA-256, manifest identity and item ID map, hashes the
already placed personal world and auxiliary player files in the background,
compares dimension configuration values and the adjusted player NBT, and checks
both live and saved wallets against the original snapshot. A changed/missing
file or wallet stops recovery. Dimension configs may differ in comments/formatting
and PersonalSpace's five persisted runtime time/weather values: `time.worldTime`,
`time.rainTime`, `time.thunderTime`, `time.raining`, `time.thundering`. Their types,
valid values and presence are still checked; every other key, including generation,
dimension ID and visual settings, must match. Current time/weather values are kept,
never reset to the archive. Forge parses temporary copies so its malformed-config
repair cannot rename or replace the real metadata. Files must not change between
verification and finalization.

Two additional world-local clock differences are accepted after typed NBT checks:
`multipart.dat` may differ only in its sole long `schedTime` tag (ForgeMultipart
1.7.15's scheduler), and `data/villages.dat` may differ in integer `data.Tick`
only when both `data.Villages` lists are empty. Every other tag must match. For
counter differences, a nonempty/changed village list, unknown multipart tags,
wrong types or malformed NBT stop recovery. These rules apply only at those exact paths inside a migrated
dimension, never to similarly named auxiliary player files or chunk data. Current
counter values remain on disk. Binary-different but fully equal typed NBT for
these files is also accepted without modifying it.

For each external chunk, recovery compares the already installed NBT with its
archived snapshot. Region files are opened read-only; missing files/chunks are
never created. Gzip, zlib and Hodgepodge's extended sector counts are supported.
The selected chunk's header and compressed payload are checked again before SU
finalization. Neighbouring chunks may change normally without invalidating the
proof. Recovery never copies an external chunk or replaces a region file.

It backs up the current SU state and pending entries under
`migrations/wallet-recovery/<move-id>-<uuid>/`, then runs the ordinary SU
finalization and save. It never reinstalls worlds, inventories, global balances,
quests or vending coins. Successful verification writes archive-bound
`wallet-recovered`, `vending-imported` and `imported` receipts. The original error
and freezes remain until the administrator runs on the **source**:

```text
migrate move-resume <move-id>
```

Inspect `move-status` for `done` before admitting the team. New ordinary import
failures now retain their original reason in the journal instead of only a
generic failure message.

For production verification, deposit known amounts in the personal wallets of
two team members and in the shared wallet. Migrate normally, check all balances
and team sharing, restart the destination, and check again. After completion the
source wallets should be empty. Also check that preflight rejects an existing
destination balance without disconnecting players or changing their coins.
