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
currently rejects moves containing external claims or a single-player team join.
It verifies the archived SHA-256, manifest identity and item ID map, hashes the
already placed personal world and auxiliary player files in the background,
compares dimension configuration values and the adjusted player NBT, and checks
both live and saved wallets against the original snapshot. A changed/missing
file or wallet stops recovery. Only comments/formatting in dimension configs may
differ. Files must not change between verification and finalization.

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
