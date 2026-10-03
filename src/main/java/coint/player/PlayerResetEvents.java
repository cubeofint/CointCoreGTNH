package coint.player;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import coint.CointCore;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public final class PlayerResetEvents {

    public static final PlayerResetEvents INSTANCE = new PlayerResetEvents();

    private final Map<UUID, PendingReset> pending = new LinkedHashMap<>();

    private PlayerResetEvents() {}

    public void queue(UUID playerId, String playerName) {
        pending.put(playerId, new PendingReset(playerId, playerName));
    }

    public void reset() {
        pending.clear();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending.isEmpty()) {
            return;
        }

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            return;
        }

        Iterator<PendingReset> iterator = pending.values()
            .iterator();
        while (iterator.hasNext()) {
            PendingReset reset = iterator.next();
            EntityPlayerMP online = server.getConfigurationManager()
                .func_152612_a(reset.playerName);

            if (online != null) {
                reset.offlineTicks = 0;
                if (!reset.kickRequested) {
                    online.playerNetServerHandler.kickPlayerFromServer("Ваш прогресс сброшен. Перезайдите на сервер.");
                    reset.kickRequested = true;
                }
                continue;
            }

            reset.kickRequested = false;
            reset.offlineTicks++;
            if (reset.offlineTicks < 2) {
                continue;
            }

            try {
                int deleted = PlayerResetManager.finalizePlayerDataReset(reset.playerId, reset.playerName);
                CointCore.LOG.info(
                    "[PlayerReset] Deleted {} playerdata/stat files for {} ({})",
                    deleted,
                    reset.playerName,
                    reset.playerId);
                iterator.remove();
            } catch (IOException e) {
                reset.failures++;
                if (reset.failures >= 5) {
                    CointCore.LOG.error(
                        "[PlayerReset] Failed to delete playerdata/stat files for {} ({})",
                        reset.playerName,
                        reset.playerId,
                        e);
                    iterator.remove();
                }
            }
        }
    }

    private static final class PendingReset {

        private final UUID playerId;
        private final String playerName;
        private int offlineTicks;
        private int failures;
        private boolean kickRequested;

        private PendingReset(UUID playerId, String playerName) {
            this.playerId = playerId;
            this.playerName = playerName;
        }
    }
}
