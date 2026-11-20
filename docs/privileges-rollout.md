# GTNH privileges: implementation and rollout

The website remains a separate deployment. This change prepares the mod and the Alpha rank policy; it does not
create live store products, change prices, register the contents of `/warp donuts`, or spend currency.
The current website patch, guarded installer, catalog migration/import commands and deployment checklist are in
`tools/site-privileges/README.md`. They target the November 19 source export and are separate from the JAR.

## Rank policy

`tools/configure-gtnh-privileges.cjs` updates only the six donor sections of an existing SU ranks file. Its default
mode prints the changes. `--apply` creates an exact sibling backup, preserves the file's owner/mode, then replaces
the file atomically. Staff, epoch and individual player sections are retained.

| Rank | Additional homes | Additional changes |
| --- | ---: | --- |
| Thorium | 1 | Base player capabilities; no new kit |
| Uranium | 2 | `/charge`, up to 1,000,000 EU or RF, 30 s cooldown |
| Plutonium | 3 | Relevant SU/CointCore command cooldowns become zero |
| Quantum | 4 | Inherits Plutonium and existing Quantum capabilities |
| Fusion | 5 | Cabinet chat color |
| Singularity | 6 | `/maxcharge`, `//dset`, cabinet prefix/name/prefix color; configurable `/expadd` |

Higher donor ranks inherit lower donor rights. Do not add `player` as a parent of Thorium: SU already adds the
default player rank independently. Inheriting it through a donor rank can override the player's epoch homes,
limits and name format. The home bonuses are explicit per donor tier and are not added together.

The XP quota was deferred by the owner. The script preserves an existing `cointcore.expadd.max_levels`, otherwise
it leaves non-OP `/expadd` disabled. An administrator can supply `--expadd-max N` once the quota is decided.

Before applying, find the actual rank file on Alpha:

```sh
find /opt/minecraft/gtnh/gtnh/serverutilities -maxdepth 3 -type f -name ranks.txt
```

Copy the script to `/opt/minecraft/gtnh/configure-gtnh-privileges.cjs`, then preview against that file:

```sh
node /opt/minecraft/gtnh/configure-gtnh-privileges.cjs \
  --rank-file /opt/minecraft/gtnh/gtnh/serverutilities/server/ranks.txt
```

After reviewing the output, repeat with `--apply`. Reload SU ranks on Alpha with `/reload serverutilities:ranks`. Alpha's existing
access synchronization publishes the definitions to Endgame/Hub. Do not run the policy tool independently on replicas.
Test without OP: an OP account cannot establish whether donor permissions, cooldowns or home limits are correct.

## Kits

Existing definitions and counters stay compatible. New commands:

```text
/kit create <name> [maxClaims] [requiredRank]
/kit bind <name> <rank|none>
/kit delete <name>
```

Creation captures the targeted inventory, otherwise the held item. The existing default `maxClaims=-1` remains
paid-credit-only. Binding a kit does not grant extra purchases or make a paid-only kit free. The rank gate controls
free claims; explicit purchased credits remain claimable by lower ranks, including individual kit purchases.

Create/delete/bind run only on the access authority when cluster access is enabled. Kit definitions now travel in
the access snapshot, including nested item NBT and registry names. Recipients validate the whole catalog before
replacing `cointcore/kits.dat`, and preserve the old file as `kits.dat.before-cluster-*` once per server session.
Unknown items reject the replacement. A publisher without the new field leaves the recipient's definitions alone.
Player claim counts, paid balances and donation receipts are not part of this catalog.

## Safe building

`//dset` is a dedicated building command, independent of unrestricted WorldEdit permissions:

```text
//dset pos1
//dset pos2
//dset minecraft:stonebrick:0
//dset undo
//dset cancel
```

A cuboid selected in WorldEdit can also be used when no complete local selection exists. Only the player's own
claimed chunks or unclaimed territory in the team's bound personal dimension are eligible. Foreign claims inside
that dimension remain protected. Guests, unloaded chunks and migration-frozen areas are rejected.

The whole selection is checked before writing. Each write checks the current block and permissions again and
honors Forge break/place protection events. A cancellation or exception during placement restores the original
block. A later conflict stops the operation; already completed positions can be reverted with `undo`.
The block journal records the actual old/new block and metadata only after a committed change, including undo;
cancelled edits and the item held in the player's hand are not recorded as placed blocks.

The default allowlist covers common vanilla building blocks, GregTech concrete and decorative Chisel blocks.
Tile entities, containers, liquids, ores and resource storage blocks are excluded, including resource variants from
Chisel. `building.extraBlocks` adds exact registry names but does not bypass these restrictions. Default limits:
32,768 selected blocks and 256 checked/changed positions per server tick across all active jobs. Undo is bounded
and retains no player/world references after the operation.

## Cabinet cosmetics

Preferences are stored in `cointcore/cosmetics.json` on Alpha and replicated with access state. Chat and tab
check current permissions when displaying them. Losing the required privilege hides the saved customization;
it does not change staff or epoch rights. A personal prefix replaces the donor label where that label is present.

The integration command is console/RCON-only; there is no player `/prefix` or `/namecolor` command:

```text
cointcosmetic <authenticated-uuid> <prefix|prefix_color|name_color|chat_color> <base64-utf8|reset>
```

The cabinet must enforce the authenticated account UUID and entitlement, base64-encode the value, and send the
command only to Alpha. Prefixes allow up to eight printable Unicode code points, without formatting/control codes.
Colors are one legacy color digit `0-9a-f`; each value is encoded as base64, or use literal `reset`.

Permissions: `cointcore.cosmetic.prefix`, `.prefix_color`, `.name_color`, `.chat_color`. The first three belong to
Singularity; Fusion also has chat color. The website patch adds the cabinet UI using the `cointshop` API, with
current server-side permission checks. The legacy `cointcosmetic` adapter above remains an administrator tool.
`cointshop` accepts `catalog`, `status`, `cosmetic`, `purchase`, and `kitpurchase` plus a base64 JSON payload.
It runs operations on the initialized server thread and returns a versioned JSON acknowledgement with the UUID.
Purchase receipts survive expiry and are persisted atomically with rank time; retries may complete kit delivery
without granting time twice. See the website patch for the immutable payload and post-commit outbox integration.

## Real item previews

The client-only `/kitmanifest <kit-id> [held|inventory]` exports a read-only preview from the held item, or the
main inventory. Compressed/Infinity Chest wrappers expand to their contents, preserving quantities, metadata
and nested items. It uses each real client item's tooltip and item renderer. Rendering runs on the render thread,
one icon per frame, and restores framebuffer state. It never calls OpenGL from a server/NEI worker.

Output: `<game>/cointcore/kit-manifests/<id>-<uuid>/manifest-draft.json` and PNGs under `icons/`. Tooltips are
localized to the exporting client's language. Advanced tooltips are enabled; modifier-sensitive extra lines depend
on the keys held during export. Export clean kit templates for the store, not personalized player items.

This is a draft for review/import, not a ready API request: the website must normalize it, calculate its canonical
hash and enforce current limits. `shop:gtnh-kit-import` now provides this process with GTNH-specific scoped
limits, full tooltip spacing and icon validation. Imports wait for administrator review; existing ATM10 API
limits are retained. The website patch also supplies grouped kit UI and optional kit checkboxes.

## Acceptance checks

- Compare registered templates against every donor kit at `/warp donuts`. Bind the intended rank; keep paid-only
  limits for kit products. Check compressed chest contents, not only the wrapper.
- Use a non-OP account at an epoch with several homes. Verify the epoch base plus 1–6 bonus homes at each tier;
  repeat after changing tier, expiry and changing nodes.
- Check Uranium `/charge` twice within 30 seconds; verify the energy delta is at most 1,000,000. Repeat as Plutonium
  with zero cooldown. `/maxcharge` must include armor and baubles as Singularity.
- Verify kit counts before/after claiming on the home node, reconnecting and synchronization. Check that a paid
  individual kit can be claimed without its free-claim rank. Kit creation on a replica must be rejected.
- For `//dset`, test an own claim, an own personal dimension, a foreign claim, an unclaimed overworld chunk,
  a machine/container, an ore/resource block, a cancelled protection event, undo, and a change during execution.
- Verify cabinet colors/prefix in local/remote chat and tab, after reconnect, tier expiry and switching nodes.
- Inspect exported icons/tooltips in-game and compare the resulting website preview before publication.
- Deploy/review the prepared website patch: confirm preserved prices/durations, Thorium at 159 ₽ converted
  to wallet quarks, and Singularity pricing. Its missing price stays a disabled draft. Verify checkout/delivery
  on the actual site before marking those production acceptance items complete.

## Developer verification

```text
node tools/tests/privilege-config.test.cjs
gradlew -I tools/tests/donation-tests.gradle testDonationQueue --offline --no-daemon
gradlew runServer25 -I tools/tests/privileges/probe.gradle --offline --no-configuration-cache --no-daemon
```

The Forge probe runs only in `run/privilege-smoke`, never in a production world. Its test items implement the
EU/RF contracts with limited acceptance per call to check the total charge budget. In-game tests with the actual
pack items, WorldEdit selection, baubles and client icon rendering remain release acceptance checks.
