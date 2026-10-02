package coint.integration.personalspace;

import java.util.LinkedHashSet;
import java.util.OptionalInt;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import coint.player.TeamsManager;
import me.eigenraven.personalspace.block.PortalTileEntity;
import me.eigenraven.personalspace.world.DimensionConfig;
import serverutils.data.ClaimedChunk;
import serverutils.data.ClaimedChunks;
import serverutils.data.ServerUtilitiesPlayerData;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;
import serverutils.lib.math.BlockDimPos;
import serverutils.lib.math.ChunkDimPos;

public final class PersonalSpaceBinding {

    private PersonalSpaceBinding() {}

    public static boolean isPersonalDimension(int dimId) {
        return dimId > 0 && DimensionConfig.getForDimension(dimId, false) != null;
    }

    public static int getTargetDimension(PortalTileEntity portal) {
        NBTTagCompound tag = new NBTTagCompound();
        portal.writeToNBT(tag);
        int[] target = tag.getIntArray("target");
        return target.length > 0 ? target[0] : 0;
    }

    public static boolean bindFromCreator(EntityPlayerMP player, PortalTileEntity portal) {
        if (player == null || portal == null || portal.getWorldObj() == null) return false;
        World world = portal.getWorldObj();
        if (world.isRemote || world.provider.dimensionId != 0) return false;
        int dimId = getTargetDimension(portal);
        if (!isPersonalDimension(dimId)) return false;

        ForgeTeam team = getClaimedTeam(world, portal.xCoord, portal.yCoord, portal.zCoord);
        if (team == null) {
            ForgePlayer forgePlayer = Universe.get()
                .getPlayer(player);
            if (forgePlayer != null && forgePlayer.hasTeam()) {
                team = forgePlayer.team;
            }
        }
        return TeamsManager.get()
            .bindDimIfFree(team, dimId);
    }

    public static boolean bindFromPortalClaim(PortalTileEntity portal) {
        if (portal == null || portal.getWorldObj() == null) return false;
        World world = portal.getWorldObj();
        if (world.isRemote || world.provider.dimensionId != 0) return false;
        int dimId = getTargetDimension(portal);
        if (!isPersonalDimension(dimId)) return false;
        ForgeTeam team = getClaimedTeam(world, portal.xCoord, portal.yCoord, portal.zCoord);
        return TeamsManager.get()
            .bindDimIfFree(team, dimId);
    }

    public static Set<Integer> findHistoricalPersonalDimensions(ForgeTeam team) {
        Set<Integer> result = new LinkedHashSet<>();
        if (team == null) return result;

        for (ForgePlayer player : Universe.get()
            .getPlayers()) {
            if (!player.hasTeam() || player.team.getUID() != team.getUID()) continue;

            if (player.isOnline()) {
                EntityPlayerMP online = player.getPlayer();
                if (online != null && isPersonalDimension(online.dimension)) {
                    result.add(online.dimension);
                }
            }

            ServerUtilitiesPlayerData data = ServerUtilitiesPlayerData.get(player);
            if (data != null && data.homes != null) {
                for (String home : data.homes.list()) {
                    BlockDimPos pos = data.homes.get(home);
                    if (pos != null && isPersonalDimension(pos.dim)) {
                        result.add(pos.dim);
                    }
                }
            }
        }

        if (ClaimedChunks.instance != null) {
            for (ClaimedChunk claimed : ClaimedChunks.instance.getTeamChunks(team, OptionalInt.empty(), true)) {
                int dimId = claimed.getPos().dim;
                if (isPersonalDimension(dimId)) {
                    result.add(dimId);
                }
            }
        }

        result.addAll(findClaimedPortalDimensions(team));
        return result;
    }

    public static Set<Integer> findClaimedPortalDimensions(ForgeTeam team) {
        Set<Integer> result = new LinkedHashSet<>();
        if (team == null || ClaimedChunks.instance == null) return result;

        WorldServer overworld = MinecraftServer.getServer()
            .worldServerForDimension(0);
        if (overworld == null) return result;

        for (ClaimedChunk claimed : ClaimedChunks.instance.getTeamChunks(team, OptionalInt.of(0), true)) {
            ChunkDimPos pos = claimed.getPos();
            boolean wasLoaded = overworld.theChunkProviderServer.chunkExists(pos.posX, pos.posZ);
            Chunk chunk = overworld.theChunkProviderServer.loadChunk(pos.posX, pos.posZ);
            try {
                for (Object value : chunk.chunkTileEntityMap.values()) {
                    if (value instanceof PortalTileEntity portal) {
                        int dimId = getTargetDimension(portal);
                        if (isPersonalDimension(dimId)) {
                            result.add(dimId);
                        }
                    }
                }
            } finally {
                if (!wasLoaded) {
                    overworld.theChunkProviderServer.unloadChunksIfNotNearSpawn(pos.posX, pos.posZ);
                }
            }
        }
        return result;
    }

    private static ForgeTeam getClaimedTeam(World world, int x, int y, int z) {
        if (ClaimedChunks.instance == null) return null;
        return ClaimedChunks.instance.getChunkTeam(new ChunkDimPos(x, y, z, world.provider.dimensionId));
    }
}
