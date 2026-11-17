# Contents of inventories in trade logs

Completed trades still go to `logs/trades.log` and the existing `trade.discordChannelId`. Inventory details are added automatically; no new Discord channel or webhook is needed.

The Discord message includes a short quoted spoiler preview. Click the spoiler to reveal it. The complete text report is attached as `trade-details.txt`, so a large inventory does not get silently cut off at Discord's message length limit. The local trade log also includes the details. Discord mentions are suppressed.

Supported contents:

- Backpack 2.6.18-GTNH external inventories, including nested backpacks. Pickup filters, the phantom workbench grid and saved crafting recipes are excluded.
- AE2 item and fluid cells through the registered cell inventory handlers, preserving long quantities. Partition filters are not counted as stored items.
- Avaritia matter clusters, using each entry's separate count instead of the one-item display stack.
- Serialized item inventories inside NBT, including Avaritiaddons compressed chests, nested containers and standard `ItemStack` lists. Recognized filter, recipe and configuration branches are skipped.

Live trades read copies of the current local inventory data. Cross-server trades use the backpack bundle from the committed trade escrow, never a similarly named or stale backpack cache on the logging node. Inspection never installs or changes a backpack, opens a GUI, extracts from a cell or modifies the offered item's NBT.

Entries show item names, registry IDs, metadata and counts. Repeated references to the same external backpack are identified and do not recurse indefinitely. If a stack contains multiple copies of the same container, the inventory is reported per container. A missing file, unsupported cell handler or malformed inventory is explicitly marked unavailable rather than reported as empty.

Inspection is bounded to 4,096 output lines, 30,000 visited NBT nodes, depth 12 and approximately 500,000 text characters per trade side. Inputs also have size and nesting checks before deep copying; compressed trade data has a 16 MiB NBT allocation budget. Reaching a bound produces an explicit unavailable/incomplete-audit notice. Arbitrary mod-specific storage that is neither serialized item NBT nor a supported external storage API cannot be universally decoded; external shared networks are not part of the item being traded.

## Deployment check

1. On one node, exchange a filled backpack and a compressed chest. Check the parent items and the contained items/counts in Discord and `logs/trades.log`.
2. Exchange a filled backpack between Alpha and Endgame. Check that the report reflects the sent backpack, even if the receiving node has no corresponding local file.
3. Repeat with a filled AE cell and a matter cluster containing more than 64 items of one type.
4. Check a nested backpack and a backpack with only ghost pickup filters or crafting recipes; ghosts must not appear as transferred inventory.
5. Open the attached report for a large inventory and confirm its last entries are present, or that an explicit audit-limit notice is shown.

Discord formatting and multipart payloads can be verified with mocked HTTP locally. Final appearance and file delivery require checking an actual Discord channel after installation.
