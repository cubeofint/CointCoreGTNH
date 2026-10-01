package coint.worldtravel;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import coint.network.WorldTravelNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public final class WorldTravelServerEvents {

    public static final WorldTravelServerEvents INSTANCE = new WorldTravelServerEvents();

    private static final ConcurrentLinkedQueue<UUID> OPEN_QUEUE = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<TravelRequest> TRAVEL_QUEUE = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<ReturnRequest> RETURN_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Set<UUID> ROLLBACK = Collections.synchronizedSet(new HashSet<>());

    private WorldTravelServerEvents() {}

    public static void enqueueOpen(UUID playerId) {
        if (playerId != null) {
            OPEN_QUEUE.add(playerId);
        }
    }

    public static void enqueueTravel(UUID playerId, String destinationId) {
        if (playerId != null && destinationId != null && destinationId.length() <= 128) {
            TRAVEL_QUEUE.add(new TravelRequest(playerId, destinationId));
        }
    }

    @SubscribeEvent
    public void onDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.player instanceof EntityPlayerMP player) || player.worldObj.isRemote) {
            return;
        }

        UUID playerId = player.getUniqueID();
        if (ROLLBACK.remove(playerId)) {
            return;
        }

        if (WorldTravelManager.canEnterDimension(player, event.toDim)) {
            return;
        }

        String requirement = WorldTravelManager.getDimensionRequirement(event.toDim);
        player.addChatMessage(
            new ChatComponentText(
                requirement == null || requirement.isEmpty() ? "§cДоступ в этот мир закрыт."
                    : "§cДоступ в этот мир закрыт: §f" + requirement));
        RETURN_QUEUE.add(new ReturnRequest(playerId, event.fromDim));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        UUID openPlayerId;
        while ((openPlayerId = OPEN_QUEUE.poll()) != null) {
            EntityPlayerMP player = getPlayer(openPlayerId);
            if (player != null) {
                WorldTravelNetwork.openFor(player);
            }
        }

        TravelRequest travel;
        while ((travel = TRAVEL_QUEUE.poll()) != null) {
            EntityPlayerMP player = getPlayer(travel.playerId);
            if (player != null) {
                WorldTravelManager.teleport(player, travel.destinationId);
            }
        }

        ReturnRequest rollback;
        while ((rollback = RETURN_QUEUE.poll()) != null) {
            EntityPlayerMP player = getPlayer(rollback.playerId);
            if (player == null || WorldTravelManager.canEnterDimension(player, player.dimension)) {
                continue;
            }
            ROLLBACK.add(rollback.playerId);
            WorldTravelManager.returnToDimensionSpawn(player, rollback.dimension);
        }
    }

    private static EntityPlayerMP getPlayer(UUID playerId) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            return null;
        }
        for (Object obj : server.getConfigurationManager().playerEntityList) {
            if (obj instanceof EntityPlayerMP player && playerId.equals(player.getUniqueID())) {
                return player;
            }
        }
        return null;
    }

    private static final class TravelRequest {

        private final UUID playerId;
        private final String destinationId;

        private TravelRequest(UUID playerId, String destinationId) {
            this.playerId = playerId;
            this.destinationId = destinationId;
        }
    }

    private static final class ReturnRequest {

        private final UUID playerId;
        private final int dimension;

        private ReturnRequest(UUID playerId, int dimension) {
            this.playerId = playerId;
            this.dimension = dimension;
        }
    }
}
