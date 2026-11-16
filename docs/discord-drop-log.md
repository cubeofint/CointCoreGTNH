# Discord drop and pickup log

Separate webhook audit, following CointCoreGTO: records items or blocks thrown by a real player and subsequent pickups of those items. Ordinary mined drops, mob loot and fake players are excluded. This is separate from `/blocklog` and does not require MySQL.

Configure `droplog` in `cointcore/cointcore.cfg` on each node that should send records:

```text
droplog {
    B:enabled=true
    S:webhookUrl=https://discord.com/api/webhooks/WEBHOOK_ID/WEBHOOK_TOKEN
    S:webhookName=GTNH Drop Log
    S:ignoredItems <
    >
}
```

Create a webhook in the Discord channel intended for this audit. Keep its URL private. Nodes may use the same webhook; each record includes its originating node, player, action, count, item name and registry ID/metadata, dimension and block coordinates. Pickups also show who threw the item (or that it was the picker's own). `[NBT]` indicates an item with NBT; full NBT contents are not posted.

The new JAR needs a restart. Afterwards these settings support `/coint_reload config`. The log is disabled by default; an empty webhook URL also disables it. It sends directly from each node, independently of the global chat bridge settings.

`ignoredItems` accepts one entry per line:

```text
minecraft:cobblestone
minecraft:dye:1
@examplemod
#dustStone
```

These match a registry ID, ID plus metadata, an entire mod, or an OreDictionary name, respectively.

Cancelled tosses, rejected entity spawns, cancelled pickups and attempts with no inventory space do not produce records. Partial pickups record only the quantity actually removed. Thrower information lives on the dropped entity and survives its NBT save/load; item NBT and vanilla merging remain unchanged. When player drops merge with each other, the original throwers' quantities are preserved. For a stack mixed with ordinary world loot, untracked items are allocated first, so the world loot is not attributed to a player.

Sending runs on a separate thread in batches every three seconds. A bounded queue holds up to 2,000 records; HTTP failures retain queued records for retry and Discord rate limits defer sending. Queue overflow is reported in the server log at most once per minute. The queue is in memory; abrupt shutdown or a prolonged outage can lose unsent records. Webhook posts suppress Discord mentions.

## Check after deployment

1. On Alpha, throw a stack of blocks; another player picks it up. Check both records, counts, coordinates and node in Discord.
2. Repeat on Endgame and Hub if enabled there.
3. Pick up part of a stack with an almost full inventory; the pickup count should match the received quantity. With no room, there should be no pickup record.
4. Let two players' compatible stacks merge, then pick them up; records should retain the two throwers and quantities.
5. Mine a block and collect its ordinary loot: no record. Add an item exclusion and check that its manual drop/pickup is also omitted.

Local verification uses real Forge toss, spawn, pickup and merge paths on an isolated dedicated server, with mocked webhook HTTP. Delivery to a real Discord channel still requires the deployment check above.
