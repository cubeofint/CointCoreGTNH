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

For production verification, deposit known amounts in the personal wallets of
two team members and in the shared wallet. Migrate normally, check all balances
and team sharing, restart the destination, and check again. After completion the
source wallets should be empty. Also check that preflight rejects an existing
destination balance without disconnecting players or changing their coins.
