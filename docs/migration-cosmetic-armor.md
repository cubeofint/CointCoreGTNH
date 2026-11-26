# Cosmetic Armor migration

CosmeticArmorReworked 1.0.7-GTNH saves its four equipment slots and `isSkinArmor` flags in
`World/playerdata/<UUID>.cosarmor`, separately from the vanilla player file. New exports include
these files for every migrating player and declare `cosmeticArmor: 1` in the manifest. This covers
both whole-team moves and personal moves into an invited team. An empty source inventory is explicit,
so an old destination visit cannot leave stale cosmetic items equipped.

The importer validates the slot layout and item IDs before replacing any files. CosmeticArmorReworked
must be available on the destination. The archive still requires the existing matching item-ID map.
The normal replacement backup preserves previous destination equipment. Imported data keeps the
items' damage, enchantments, custom NBT and visibility flags. Referenced external Backpack storage is
included by the existing backpack snapshot scan as well.

The mod's per-player cache is invalidated after installation. Source cosmetics move to the retirement
backup only after the journal confirms import; the saved slots must still match the export snapshot.
Retirement is repeatable and invalidates the source cache, preventing a later save from resurrecting
the old equipment. Legacy exports without cosmetic slots leave those source files untouched.

Updating CointCore fixes future exports. It does not add missing equipment to existing ZIPs or repair
completed moves automatically. For an earlier omission, inspect that player's source `.cosarmor` file
and the destination equipment before restoring anything; do not repeat an entire completed migration.

## Verification

The local isolated Forge probe uses CosmeticArmorReworked 1.0.7-GTNH and Backpack 2.6.18-GTNH from the
client pack. It checks native saves, a real two-player team export/import, all four cosmetic slots,
NBT and visibility flags, explicit empty slots, stale destination cache replacement, previous-equipment
backups, source retirement and repeated retirement, changed-source rejection, malformed/missing slot
data and referenced backpack storage. A separate run without CosmeticArmorReworked checks optional-mod
handling. These checks use disposable local worlds, not the production cluster.

After deploying the same CointCore build to all nodes and clients during a planned restart, test a new
move with cosmetic items equipped. Reconnect to Endgame and check each cosmetic slot and visibility
toggle, then reconnect once more to check persistence. Test the invitation path separately. Confirm
the retired source has no active copy of those slots and that its backup retains the original file.
