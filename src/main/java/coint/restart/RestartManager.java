package coint.restart;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.ThreadedFileIOBase;

import coint.CointCore;
import coint.network.WorldTravelNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.lib.data.Universe;
import serverutils.task.backup.BackupTask;

public final class RestartManager {

    public static final RestartManager INSTANCE = new RestartManager();

    private enum Phase {
        IDLE,
        COUNTDOWN,
        WAITING_BACKUP,
        BACKUP,
        STOPPING
    }

    private Phase phase = Phase.IDLE;
    private long targetMillis;
    private int lastSecond = -1;
    private boolean joinsLocked;
    private boolean cancelAfterBackup;
    private String backupName = "";
    private File backupFile;
    private long backupStartedAt;

    private RestartManager() {}

    public synchronized String schedule(long seconds) {
        if (phase == Phase.BACKUP || phase == Phase.STOPPING) {
            return "Финальная стадия рестарта уже запущена.";
        }

        targetMillis = System.currentTimeMillis() + Math.max(0L, seconds) * 1000L;
        phase = Phase.COUNTDOWN;
        lastSecond = -1;
        joinsLocked = false;
        cancelAfterBackup = false;
        backupName = "";
        backupFile = null;
        backupStartedAt = 0L;

        int remaining = getRemainingSeconds();
        MinecraftServer server = MinecraftServer.getServer();
        broadcastChat(server, "§c[Restart] §fРучной рестарт запланирован через §e" + formatDuration(remaining) + "§f.");
        broadcastState(true, remaining, "countdown");
        CointCore.LOG.info("[Restart] Scheduled in {} seconds", seconds);
        return "Ручной рестарт запланирован через " + formatDuration(remaining) + ".";
    }

    public synchronized String cancel() {
        if (phase == Phase.IDLE) {
            return "Запланированного рестарта нет.";
        }
        if (phase == Phase.STOPPING) {
            return "Сервер уже завершает работу.";
        }
        if (phase == Phase.BACKUP) {
            cancelAfterBackup = true;
            return "Отмена принята. Текущий бэкап завершится, после чего сервер останется запущенным.";
        }

        MinecraftServer server = MinecraftServer.getServer();
        clear(false);
        broadcastChat(server, "§a[Restart] §fЗапланированный рестарт отменён.");
        broadcastState(false, 0, "");
        return "Рестарт отменён.";
    }

    public synchronized String getStatus() {
        switch (phase) {
            case IDLE:
                return "Рестарт не запланирован.";
            case COUNTDOWN:
                return "До рестарта: " + formatDuration(getRemainingSeconds()) + ".";
            case WAITING_BACKUP:
                return "Рестарт: сохранение завершено, ожидается освобождение системы бэкапов.";
            case BACKUP:
                return cancelAfterBackup ? "Рестарт отменён, ожидается завершение текущего бэкапа."
                    : "Рестарт: создаётся финальный бэкап.";
            case STOPPING:
                return "Резервная копия готова, сервер завершает работу.";
            default:
                return "Рестарт не запланирован.";
        }
    }

    @SubscribeEvent
    public synchronized void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || phase == Phase.IDLE || phase == Phase.STOPPING) {
            return;
        }

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }

        if (phase == Phase.COUNTDOWN) {
            tickCountdown(server);
            return;
        }

        if (phase == Phase.WAITING_BACKUP) {
            if (BackupTask.thread == null) {
                startFinalBackup(server);
            }
            return;
        }

        if (phase == Phase.BACKUP) {
            tickBackup(server);
        }
    }

    @SubscribeEvent
    public synchronized void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP player)) {
            return;
        }
        if (joinsLocked) {
            player.playerNetServerHandler.kickPlayerFromServer("§cИдёт рестарт. Зайдите через пару минут.");
            return;
        }
        if (phase == Phase.COUNTDOWN) {
            WorldTravelNetwork.syncRestartState(player, true, getRemainingSeconds(), "countdown");
        }
    }

    public synchronized void reset() {
        clear(false);
    }

    private void tickCountdown(MinecraftServer server) {
        int remaining = getRemainingSeconds();
        if (remaining != lastSecond) {
            lastSecond = remaining;
            broadcastState(true, remaining, "countdown");
        }

        if (remaining <= 10 && !joinsLocked) {
            joinsLocked = true;
            kickAll(server);
        }

        if (remaining > 0) {
            return;
        }

        broadcastState(true, 0, "restarting");
        try {
            saveEverything(server);
        } catch (Exception e) {
            CointCore.LOG.error("[Restart] Failed to save worlds before backup", e);
            failAndUnlock(server);
            return;
        }

        phase = Phase.WAITING_BACKUP;
        if (BackupTask.thread == null) {
            startFinalBackup(server);
        } else {
            CointCore.LOG.info("[Restart] Waiting for an already running ServerUtilities backup");
        }
    }

    private void startFinalBackup(MinecraftServer server) {
        backupName = "restart-" + new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ROOT).format(new Date());
        backupFile = new File(BackupTask.BACKUP_FOLDER, backupName + ".zip");
        if (backupFile.exists() && !backupFile.delete()) {
            CointCore.LOG.warn("[Restart] Could not remove existing backup file {}", backupFile.getAbsolutePath());
        }

        broadcastState(true, 0, "restarting");
        phase = Phase.BACKUP;
        backupStartedAt = System.currentTimeMillis();
        CointCore.LOG.info("[Restart] Starting final backup {}", backupName);

        try {
            new BackupTask(server, backupName).execute(Universe.get());
        } catch (Throwable t) {
            CointCore.LOG.error("[Restart] Could not start ServerUtilities backup", t);
            failAndUnlock(server);
        }
    }

    private void tickBackup(MinecraftServer server) {
        if (BackupTask.thread != null) {
            return;
        }
        if (System.currentTimeMillis() - backupStartedAt < 1500L) {
            return;
        }

        if (backupFile == null || !backupFile.isFile() || backupFile.length() <= 0L) {
            CointCore.LOG.error("[Restart] Final backup file was not created successfully: {}", backupFile);
            failAndUnlock(server);
            return;
        }

        if (cancelAfterBackup) {
            CointCore.LOG.info("[Restart] Backup completed, restart was cancelled");
            joinsLocked = false;
            phase = Phase.IDLE;
            cancelAfterBackup = false;
            broadcastState(false, 0, "");
            broadcastChat(server, "§a[Restart] §fРестарт отменён. Вход на сервер снова открыт.");
            return;
        }

        phase = Phase.STOPPING;
        broadcastState(true, 0, "restarting");
        CointCore.LOG.info("[Restart] Final backup verified at {}. Stopping server.", backupFile.getAbsolutePath());
        server.initiateShutdown();
    }

    private void failAndUnlock(MinecraftServer server) {
        phase = Phase.IDLE;
        joinsLocked = false;
        cancelAfterBackup = false;
        broadcastState(false, 0, "");
        broadcastChat(server, "§c[Restart] §fРестарт отменён. Сервер продолжает работу.");
    }

    private void kickAll(MinecraftServer server) {
        broadcastState(true, 0, "restarting");
        List<?> players = new ArrayList<>(server.getConfigurationManager().playerEntityList);
        for (Object object : players) {
            if (object instanceof EntityPlayerMP player) {
                player.playerNetServerHandler.kickPlayerFromServer("§cИдёт рестарт. Зайдите через пару минут.");
            }
        }
        CointCore.LOG.info("[Restart] Player logins locked and {} players kicked", players.size());
    }

    private static void saveEverything(MinecraftServer server) throws Exception {
        server.getConfigurationManager()
            .saveAllPlayerData();
        for (WorldServer world : server.worldServers) {
            if (world != null) {
                world.saveAllChunks(true, null);
            }
        }
        ThreadedFileIOBase.threadedIOInstance.waitForFinish();
        CointCore.LOG.info("[Restart] All player data and worlds saved");
    }

    private int getRemainingSeconds() {
        long millis = targetMillis - System.currentTimeMillis();
        if (millis <= 0L) {
            return 0;
        }
        long seconds = (millis + 999L) / 1000L;
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }

    private static String formatDuration(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dч %02dм %02dс", hours, minutes, secs);
        }
        if (minutes > 0) {
            return String.format(Locale.ROOT, "%dм %02dс", minutes, secs);
        }
        return secs + "с";
    }

    private static void broadcastChat(MinecraftServer server, String message) {
        if (server != null && server.getConfigurationManager() != null) {
            server.getConfigurationManager()
                .sendChatMsg(new ChatComponentText(message));
        }
    }

    private static void broadcastState(boolean active, int seconds, String phase) {
        WorldTravelNetwork.broadcastRestartState(active, seconds, phase);
    }

    private void clear(boolean notify) {
        phase = Phase.IDLE;
        targetMillis = 0L;
        lastSecond = -1;
        joinsLocked = false;
        cancelAfterBackup = false;
        backupName = "";
        backupFile = null;
        backupStartedAt = 0L;
        if (notify) {
            broadcastState(false, 0, "");
        }
    }
}
