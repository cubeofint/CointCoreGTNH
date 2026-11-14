# Source terrain after team migration

New team moves reset external source chunks only after the destination confirms the import.
Whole personal dimensions and owned space stations are still archived and remain blocked on the source.

The source drains loaded chunks and pending saves, closes region caches, and saves affected region files to
`migrations/retired/<move-id>/external-regions/<dimension>/`. It clears only the selected Anvil location and
timestamp entries. The transfer ZIP also contains the original chunk data. All transferred source claims are removed.

The `source-chunks-reset` receipt contains the archive hash. After `done`, this receipt allows the source to release
external chunk freezes. Terrain is generated on the next ordinary chunk load using the source world's generator;
it is not an immediate bulk generation pass. Generation can have the usual world-generation cost and behaviour.

Existing completed moves without this receipt are not changed. Never manually remove their freezes to repair holes:
the original structures may still be on disk. Never restore a full region backup over a running or subsequently edited
world; a region also contains neighbouring chunks.

## Verification

The standalone regression test `tools/tests/MigrationRegionResetTest.java` exercises negative chunk coordinates,
byte-for-byte preservation of neighbouring records and payloads, repeated erasure, and rejection of malformed or missing
region files. Compile it alongside `MigrationRegionReset.java` and run `coint.migration.MigrationRegionResetTest`.

In-game acceptance on a disposable team:

1. Claim an external chunk, put identifiable blocks and a chest in it, and put a separate marker in a neighbouring chunk.
2. Move the team through the normal Alpha-to-Endgame flow, with everyone away from the external chunks.
3. Confirm the imported structures and chest contents on Endgame.
4. Visit the original coordinates on Alpha: terrain should generate, old structures and claims should be absent.
5. Confirm the neighbouring marker remains. Reconnect and restart Alpha, then check again.
6. Confirm archived personal dimensions and stations do not reopen on Alpha.

Compilation and the isolated region-file regression are not a substitute for this multi-node acceptance test.
