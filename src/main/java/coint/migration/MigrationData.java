package coint.migration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import coint.CointCore;
import coint.epochsync.EpochEntry;
import coint.integration.serverutilities.RanksManager;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameData;
import me.eigenraven.personalspace.block.PortalTileEntity;
import serverutils.data.ClaimedChunk;
import serverutils.data.ClaimedChunks;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.math.ChunkDimPos;

/**
 * What a move needs beyond the copied files, written into the manifest by the export and read back by the import:
 * the block/item ID map, each player's epoch and Blood Magic network, the team with its claims, the BetterQuesting
 * party and where to put the players in their personal world.
 */
final class MigrationData {

    /** Bumped when the manifest changes in a way an older import cannot read. */
    static final int FORMAT = 2;
    /** The BetterQuesting party, as compressed NBT inside the zip. */
    static final String PARTY_FILE = "cointcore-migration/party.dat";

    private MigrationData() {}

    /** Name to numeric id of every block and item. */
    static Map<String, Map<String, Integer>> idMap() {
        Map<String, Integer> blocks = new TreeMap<>(), items = new TreeMap<>();
        GameData.getBlockRegistry()
            .serializeInto(blocks);
        GameData.getItemRegistry()
            .serializeInto(items);
        Map<String, Map<String, Integer>> map = new TreeMap<>();
        map.put("blocks", blocks);
        map.put("items", items);
        return map;
    }

    /** The ids that differ between the exported map and this server's: empty when blocks and items stay the same. */
    static List<String> idMismatches(Map<String, Map<String, Number>> exported) {
        List<String> wrong = new ArrayList<>();
        Map<String, Map<String, Integer>> here = idMap();
        for (String kind : new String[] { "blocks", "items" }) {
            Map<String, Number> theirs = exported == null ? null : exported.get(kind);
            if (theirs == null) {
                wrong.add("нет карты " + kind);
                continue;
            }
            Map<String, Integer> ours = here.get(kind);
            for (Map.Entry<String, Number> e : theirs.entrySet()) {
                Integer id = ours.get(e.getKey());
                if (id == null || id != e.getValue()
                    .intValue()) {
                    wrong.add(kind + " " + e.getKey() + ": " + e.getValue() + " -> " + id);
                }
            }
        }
        return wrong;
    }

    static void describePlayer(UUID id, String name, Map<String, Object> info) {
        info.put(
            "personalDimensionRewardConsumed",
            coint.player.TeamsManager.get()
                .isPDimRewardConsumed(id));
        RanksManager ranks = RanksManager.get();
        EpochEntry epoch = ranks == null ? null : ranks.getPlayerEpoch(id);
        if (epoch != null) {
            info.put("epoch", epoch.rankName);
        }
        if (Loader.isModLoaded("AWWayofTime")) {
            try {
                info.put("bloodEssence", BloodMagicData.essence(name));
                info.put("bloodOrb", BloodMagicData.orb(name));
            } catch (RuntimeException e) {
                CointCore.LOG.warn("[Migrate] Could not read the Blood Magic network of {}", name, e);
            }
        }
    }

    static void describeTeam(ForgeTeam team, Set<Integer> dims, Map<String, Object> info) {
        info.put(
            "owner",
            team.getOwner() == null ? null
                : team.getOwner()
                    .getId()
                    .toString());
        List<String> members = new ArrayList<>();
        for (ForgePlayer p : team.getMembers()) {
            members.add(
                p.getId()
                    .toString());
        }
        info.put("members", members);
        info.put(
            "color",
            team.getColor()
                .name());
        List<int[]> claims = new ArrayList<>();
        if (ClaimedChunks.instance != null) {
            for (ClaimedChunk c : ClaimedChunks.instance.getTeamChunks(team, OptionalInt.empty(), true)) {
                ChunkDimPos pos = c.getPos();
                if (dims.contains(pos.dim)) {
                    claims.add(new int[] { pos.dim, pos.posX, pos.posZ, c.isLoaded() ? 1 : 0 });
                }
            }
        }
        info.put("claims", claims);
        int[] entry = entry(team, dims);
        if (entry != null) {
            info.put("entry", entry);
        }
    }

    /** Where the team's overworld portal puts a player in its personal world: {dim, x, y, z}, or null. */
    private static int[] entry(ForgeTeam team, Set<Integer> dims) {
        WorldServer overworld = MinecraftServer.getServer()
            .worldServerForDimension(0);
        if (overworld == null || ClaimedChunks.instance == null) {
            return null;
        }
        for (ClaimedChunk claimed : ClaimedChunks.instance.getTeamChunks(team, OptionalInt.of(0), true)) {
            ChunkDimPos pos = claimed.getPos();
            boolean wasLoaded = overworld.theChunkProviderServer.chunkExists(pos.posX, pos.posZ);
            Chunk chunk = overworld.theChunkProviderServer.loadChunk(pos.posX, pos.posZ);
            try {
                for (Object value : chunk.chunkTileEntityMap.values()) {
                    if (value instanceof PortalTileEntity portal && dims.contains(portal.targetDimId)) {
                        return new int[] { portal.targetDimId, portal.getTargetTeleportX(), portal.getTargetTeleportY(),
                            portal.getTargetTeleportZ() };
                    }
                }
            } finally {
                if (!wasLoaded) {
                    overworld.theChunkProviderServer.unloadChunksIfNotNearSpawn(pos.posX, pos.posZ);
                }
            }
        }
        return null;
    }

    /** The BetterQuesting party of the first of them who has one, as compressed NBT; null when none. */
    static byte[] partyOf(List<ForgePlayer> players) {
        if (!Loader.isModLoaded("betterquesting")) {
            return null;
        }
        for (ForgePlayer p : players) {
            NBTTagCompound tag = QuestData.partyOf(p.getId());
            if (tag != null) {
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    CompressedStreamTools.writeCompressed(tag, out);
                    return out.toByteArray();
                } catch (IOException e) {
                    CointCore.LOG.warn("[Migrate] Could not write the party", e);
                    return null;
                }
            }
        }
        return null;
    }
}
