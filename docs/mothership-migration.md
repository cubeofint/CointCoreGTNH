# AmunRa mothership migration

Team moves (`/migrate move`, quest migration and batch moves) discover AmunRa
ships by owner UUID, including ships without claims. The complete
`DIM_MOTHERSHIP<dimension>` directory and one registry entry travel together.
The source's shared `ARMothershipData.dat` must never overwrite the destination
registry. Ordinary standalone exports retain their existing behavior.

This integration targets AmunRa-GC 0.8.14. It uses reflection because AmunRa is
optional. `addMothership` is client-only; dedicated-server registration updates
both registry indexes, the highest ship ID, the dimension and orbit layout.
`getMotherships()` returns a clone and cannot be used to mutate the registry.

Preflight refuses ships in transit, foreign ownership/claims, occupied ship IDs,
occupied dimension IDs, existing target folders and missing orbit providers.
IDs are preserved, not reassigned. Import and retirement save a registry backup
before changing it. Retirement only removes the transferred entries after the
normal confirmed-import checkpoint.

## Production acceptance

Use matching CointCore builds on all nodes and clients. On a test team first:

1. Check that preflight includes the ship dimension among whole worlds.
2. Transfer an offline team with a stationary ship.
3. On the destination, verify the ship in the planetary menu, arrival, blocks,
   machines, inventories, ownership and permissions. Restart and verify again.
4. Confirm the source no longer lists or loads that ship, and other ships are
   unchanged on both nodes.
5. Confirm occupied IDs and ships in transit reject the move before export.

The isolated dedicated-server probe verifies metadata validation, registration,
serialization, collision rejection, snapshotting, retirement, repeated retirement
and preservation of a neighboring ship. It does not substitute for a full
two-node transfer of player-built ship blocks or a connected-client UI test.

`tools/retry-mothership-batch.py` is a narrowly scoped recovery utility for the
known 1055 pre-export failure. Run without arguments first; `--apply` requires
Alpha and Endgame stopped, checks that export never began, backs up the failed
request and batch, archives that request, and clears only the current request
pointer. The batch index and previously skipped/completed entries remain intact.
