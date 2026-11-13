package coint.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.google.gson.Gson;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.cluster.ClusterService;
import coint.epochsync.EpochEntry;
import coint.epochsync.EpochRegistry;
import coint.integration.serverutilities.RanksManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

/** Reviewed plans; one ordinary journalled move at a time. Never retries an uncertain dispatch. */
@EventBusSubscriber
public final class MigrationBatch {

    private static final Gson JSON = new Gson();
    private static Plan active;
    private static long next;

    private static final class Plan {

        String id, source, target, epoch, current, error;
        int index;
        List<Entry> teams = new ArrayList<>();
    }

    private static final class Entry {

        String team, representative, fingerprint;
    }

    private MigrationBatch() {}

    public static void reset() {
        active = null;
        next = 0;
    }

    private static Path file(String id) {
        if (!UUID.fromString(id)
            .toString()
            .equals(id)) throw new IllegalArgumentException("Неверный ID плана");
        return Paths.get("cointcore", "migration-batches", id + ".json");
    }

    private static void save(Plan p) throws IOException {
        Path target = file(p.id);
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(p.id + ".tmp");
        Files.write(
            temp,
            JSON.toJson(p)
                .getBytes(StandardCharsets.UTF_8));
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String fingerprint(ForgeTeam team) {
        List<String> players = new ArrayList<>();
        for (ForgePlayer p : team.getMembers()) players.add(
            p.getId()
                .toString());
        Collections.sort(players);
        return players + ":" + new TreeSet<>(MigrationExporter.findTeamDimensions(team));
    }

    public static void plan(ICommandSender sender, String epochName, String target) throws IOException {
        if (!ClusterService.isRunning() || !coint.CointConfig.cluster.migrationEnabled)
            throw new IOException("Миграция выключена");
        EpochEntry threshold = EpochRegistry.INST.getEpoch(epochName);
        if (threshold == null) throw new IOException("Неизвестная эпоха " + epochName);
        if (!target.matches("[A-Za-z0-9_-]{1,64}") || target.equals(ClusterService.nodeId()))
            throw new IOException("Неверная цель");
        Plan p = new Plan();
        p.id = UUID.randomUUID()
            .toString();
        p.source = ClusterService.nodeId();
        p.target = target;
        p.epoch = epochName;
        for (ForgeTeam team : Universe.get()
            .getTeams()) {
            if (!team.isValid() || team.getOwner() == null
                || MigrationExporter.findTeamDimensions(team)
                    .isEmpty())
                continue;
            boolean eligible = false;
            for (ForgePlayer member : team.getMembers()) {
                EpochEntry epoch = RanksManager.get()
                    .getPlayerEpoch(member.getId());
                if (epoch != null && epoch.priority >= threshold.priority) eligible = true;
            }
            if (!eligible) continue;
            Entry entry = new Entry();
            entry.team = team.getId();
            entry.representative = team.getOwner()
                .getId()
                .toString();
            entry.fingerprint = fingerprint(team);
            p.teams.add(entry);
            sender.addChatMessage(
                new ChatComponentText(
                    "§e" + entry.team
                        + " — "
                        + team.getOwner()
                            .getName()
                        + " "
                        + MigrationExporter.findTeamDimensions(team)));
        }
        p.teams.sort(Comparator.comparing(e -> e.team));
        save(p);
        sender.addChatMessage(
            new ChatComponentText(
                "§aПлан " + p.id + ": " + p.teams.size() + " команд. Запуск: /migrate batch run " + p.id));
    }

    public static void command(ICommandSender sender, String action, String id) throws IOException {
        Plan p = JSON.fromJson(new String(Files.readAllBytes(file(id)), StandardCharsets.UTF_8), Plan.class);
        if (p == null || !id.equals(p.id)
            || !ClusterService.nodeId()
                .equals(p.source))
            throw new IOException("План другой ноды или повреждён");
        if ("run".equals(action)) {
            if (active != null && !active.id.equals(id)) throw new IOException("Уже выполняется другой план");
            if ("dispatching".equals(p.current))
                throw new IOException("Запуск прерван: проверьте team-moves. Автоповтор запрещён");
            p.error = null;
            active = p;
            save(p);
        } else if ("stop".equals(action)) {
            if (active != null && active.id.equals(id)) active = null;
        } else
            if (!"status".equals(action)) throw new IOException("batch plan <epoch> <target> | run/status/stop <id>");
        sender.addChatMessage(
            new ChatComponentText(
                "§eПлан " + id
                    + ": "
                    + p.index
                    + "/"
                    + p.teams.size()
                    + ", перенос: "
                    + p.current
                    + ", ошибка: "
                    + p.error
                    + ". Stop останавливает очередь, но не уже начатый перенос."));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null
            || !ClusterService.isRunning()
            || !coint.CointConfig.cluster.migrationEnabled
            || !Universe.loaded()
            || System.currentTimeMillis() < next) return;
        next = System.currentTimeMillis() + 2000;
        Plan p = active;
        try {
            if (p.current != null) {
                Path move = MigrationStorage.archives()
                    .toPath()
                    .resolve("team-moves")
                    .resolve(p.current);
                if (Files.isRegularFile(move.resolve("done"))) {
                    p.index++;
                    p.current = null;
                    save(p);
                } else if (Files.isRegularFile(move.resolve("error")))
                    throw new IOException("Перенос " + p.current + " остановлен; проверьте /migrate move-status");
                else return;
            }
            if (p.index >= p.teams.size()) {
                active = null;
                return;
            }
            Entry entry = p.teams.get(p.index);
            if (MigrationMove.busy()) return;
            ForgeTeam team = Universe.get()
                .getTeam(entry.team);
            if (team == null || !team.isValid() || !entry.fingerprint.equals(fingerprint(team)))
                throw new IOException("Изменился состав/персоналки команды " + entry.team + "; создайте новый план");
            for (ForgePlayer player : team.getMembers()) if (player.isOnline()) throw new IOException(
                "Команда " + entry.team + " онлайн. Используйте согласование /migrate request либо дождитесь выхода");
            p.current = "dispatching";
            save(p);
            String moveId = MigrationMove.start(
                MinecraftServer.getServer(),
                Universe.get()
                    .getPlayer(UUID.fromString(entry.representative)),
                p.target);
            p.current = moveId;
            save(p);
            if (moveId == null)
                throw new IOException("Не удалось начать перенос " + entry.team + "; причина в консоли");
        } catch (IOException | RuntimeException e) {
            p.error = e.getMessage();
            active = null;
            try {
                save(p);
            } catch (IOException failure) {
                coint.CointCore.LOG.error("[MigrationBatch] Cannot save error", failure);
            }
            coint.CointCore.LOG.error("[MigrationBatch] {} stopped: {}", p.id, p.error);
        }
    }
}
