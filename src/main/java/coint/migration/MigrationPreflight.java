package coint.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.Gson;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import coint.cluster.ClusterService;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import me.eigenraven.personalspace.world.DimensionConfig;
import serverutils.data.ClaimedChunk;
import serverutils.data.ClaimedChunks;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;
import serverutils.lib.math.ChunkDimPos;

/** Read-only two-node inspection. Does not reserve players, load worlds, or modify claims. */
@EventBusSubscriber
public final class MigrationPreflight {

    private static final Gson JSON = new Gson();
    private static final Map<String, Pending> pending = new HashMap<>();
    private static long nextPoll;

    private MigrationPreflight() {}

    public static final class Report {

        public String id;
        public List<String> problems = new ArrayList<>();

        public boolean ready() {
            return problems.isEmpty();
        }
    }

    private static final class Request {

        MoveRecord move;
        long expires;
        Map<String, Map<String, Integer>> ids;
    }

    private static final class Pending {

        boolean routingDone;

        final Request request;
        final Report source;
        final Consumer<Report> callback;

        Pending(Request request, Report source, Consumer<Report> callback) {
            this.request = request;
            this.source = source;
            this.callback = callback;
        }
    }

    private static Path root() {
        return MigrationStorage.archives()
            .toPath()
            .resolve("preflight");
    }

    public static void reset() {
        pending.clear();
        nextPoll = 0;
    }

    private static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(
            temp,
            JSON.toJson(value)
                .getBytes(StandardCharsets.UTF_8));
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void show(ICommandSender sender, Report report) {
        sender.addChatMessage(
            new ChatComponentText(
                report.ready() ? "§aПроверка обеих нод пройдена. ID: " + report.id
                    : "§cПеренос заблокирован: " + report.problems.size() + " проблем. ID: " + report.id));
        for (int i = 0; i < Math.min(20, report.problems.size()); i++)
            sender.addChatMessage(new ChatComponentText("§e" + report.problems.get(i)));
        if (report.problems.size() > 20) sender.addChatMessage(
            new ChatComponentText("§eПолный отчёт: preflight/" + report.id + "/report.json в общей папке миграций"));
    }

    public static void start(ForgePlayer player, String target, Consumer<Report> callback) throws IOException {
        if (!ClusterService.isRunning() || !CointConfig.cluster.migrationEnabled || !player.hasTeam())
            throw new IOException("Нужны кластер, миграция и команда SU");
        if (!target.matches("[A-Za-z0-9_-]{1,64}") || target.equals(ClusterService.nodeId()))
            throw new IOException("Неверная целевая нода");
        MoveRecord move = new MoveRecord();
        move.id = UUID.randomUUID()
            .toString();
        move.source = ClusterService.nodeId();
        move.target = target;
        move.actor = player.getName();
        move.team = player.team.getId();
        for (ForgePlayer member : player.team.getMembers()) {
            move.players.add(
                member.getId()
                    .toString());
            move.names.add(member.getName());
        }
        move.dimensions.addAll(MigrationExporter.findTeamDimensions(player.team));
        move.stations.addAll(MigrationStations.discover(player.team));
        for (MigrationStations.Station station : move.stations) {
            if (move.dimensions.contains(station.id))
                throw new IOException("Конфликт персоналки и станции " + station.id);
            move.dimensions.add(station.id);
        }
        if (ClaimedChunks.instance == null) throw new IOException("Приваты SU недоступны");
        for (ClaimedChunk c : ClaimedChunks.instance.getTeamChunks(player.team, OptionalInt.empty(), true))
            move.claims.add(new int[] { c.getPos().dim, c.getPos().posX, c.getPos().posZ, c.isLoaded() ? 1 : 0 });
        Request request = new Request();
        request.move = move;
        request.ids = MigrationData.idMap();
        request.expires = System.currentTimeMillis() + 30_000;
        Report report = inspect(move, false);
        write(root().resolve(move.id + "/request.json"), request);
        Pending p = new Pending(request, report, callback);
        pending.put(move.id, p);
        coint.cluster.ClusterMoveHomes.inspect(move, issues -> {
            if (pending.get(move.id) != p) return;
            p.source.problems.addAll(issues);
            p.routingDone = true;
        });
    }

    private static Report inspect(MoveRecord move, boolean target) {
        Set<String> errors = new LinkedHashSet<>();
        String node = ClusterService.nodeId() + ": ";
        if (!CointConfig.cluster.migrationEnabled) errors.add(node + "миграция отключена");
        if (MigrationMove.busy()) errors.add(node + "уже выполняется другая миграция");
        if (move.dimensions.isEmpty()) errors.add(node + "у команды нет доступной персоналки");
        try {
            MigrationGuard.checkConflict(move);
        } catch (Exception e) {
            errors.add(node + e.getMessage());
        }
        try {
            MigrationGlobalData.preflight(move);
        } catch (Exception e) {
            errors.add(node + e.getMessage());
        }
        if (ClaimedChunks.instance == null) errors.add(node + "приваты SU недоступны");
        else {
            if (!target) for (int[] spawn : coint.worldtravel.WorldTravelManager.migrationSpawnPositions())
                for (int[] c : move.claims) if (c[0] == spawn[0] && c[1] == spawn[1] && c[2] == spawn[2]) errors.add(
                    node + "спавн меню путешествий записан на команду: "
                        + MoveRecord.key(c)
                        + ". Передайте общественный приват серверной команде.");
            for (int[] c : move.claims) {
                if (!move.dimensions.contains(c[0]) && !DimensionManager.isDimensionRegistered(c[0]))
                    errors.add(node + "не зарегистрировано дополнительное измерение " + c[0]);
                ClaimedChunk existing = ClaimedChunks.instance.getChunk(new ChunkDimPos(c[1], c[2], c[0]));
                if (existing != null && !existing.getTeam()
                    .getId()
                    .equals(move.team))
                    errors.add(
                        node + "чужой приват "
                            + MoveRecord.key(c)
                            + ", команда "
                            + existing.getTeam()
                                .getId());
                if (target && MigrationGuard.sourceChunk(DimensionManager.getWorld(c[0]), c[1], c[2]))
                    errors.add(node + "чанк уже архивирован прежним переносом: " + MoveRecord.key(c));
            }
            for (ClaimedChunk c : ClaimedChunks.instance.getAllChunks())
                if (!c.isInvalid() && move.dimensions.contains(c.getPos().dim)
                    && !c.getTeam()
                        .getId()
                        .equals(move.team))
                    errors.add(
                        node + "в переносимом мире "
                            + c.getPos().dim
                            + " чужой приват "
                            + c.getPos().posX
                            + ":"
                            + c.getPos().posZ
                            + ", команда "
                            + c.getTeam()
                                .getId());
        }
        for (int dim : move.dimensions) {
            MigrationStations.Station station = MigrationStations.get(move, dim);
            if (station != null) {
                try {
                    MigrationStations.check(move, station, target);
                } catch (Exception e) {
                    errors.add(node + e.getMessage());
                }
            }
            if (target && MigrationGuard.sourceDimension(dim)) errors.add(node + "мир уже архивирован: " + dim);
            if (station == null && target
                && DimensionManager.isDimensionRegistered(dim)
                && DimensionConfig.getForDimension(dim, false) == null)
                errors.add(node + "ID персоналки занят другим типом измерения: " + dim);
            Short uid = station != null ? null
                : coint.player.TeamsManager.get()
                    .getTeamUidForDim(dim);
            ForgeTeam holder = uid == null ? null
                : Universe.get()
                    .getTeam(uid);
            if (holder != null && holder.isValid()
                && !holder.getId()
                    .equals(move.team))
                errors.add(node + "персоналка " + dim + " принадлежит " + holder.getId());
            if (!target && station == null) try {
                if (!MigrationStorage
                    .child(MigrationStorage.session().world, "PERSONAL_DIM_" + dim + "/personalspace_metadata.cfg")
                    .isFile()) errors.add(node + "нет метаданных персоналки " + dim);
            } catch (IOException e) {
                errors.add(node + e.getMessage());
            }
        }
        ForgeTeam team = Universe.get()
            .getTeam(move.team);
        int distance = net.minecraft.server.MinecraftServer.getServer()
            .getConfigurationManager()
            .getViewDistance() + 2;
        for (Object value : net.minecraft.server.MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            net.minecraft.entity.player.EntityPlayerMP p = (net.minecraft.entity.player.EntityPlayerMP) value;
            if (move.players.contains(
                p.getUniqueID()
                    .toString()))
                continue;
            if (move.dimensions.contains(p.dimension))
                errors.add(node + "в переносимом мире находится игрок " + p.getCommandSenderName());
            else for (int[] c : move.claims)
                if (c[0] == p.dimension && Math.abs((long) p.chunkCoordX - c[1]) <= distance
                    && Math.abs((long) p.chunkCoordZ - c[2]) <= distance)
                    errors.add(
                        node + "посторонний игрок "
                            + p.getCommandSenderName()
                            + " рядом с переносимыми чанками измерения "
                            + c[0]);
        }
        if (target) {
            for (String id : move.players) {
                ForgePlayer p = Universe.get()
                    .getPlayer(UUID.fromString(id));
                if (p != null && p.hasTeam()
                    && !p.team.getId()
                        .equals(move.team))
                    errors.add(node + "игрок " + p.getName() + " состоит в другой команде " + p.team.getId());
            }
            if (team != null && team.isValid()) for (ForgePlayer member : team.getMembers()) if (!move.players.contains(
                member.getId()
                    .toString()))
                errors.add(node + "в целевой команде посторонний участник " + member.getName());
        }
        Report report = new Report();
        report.id = move.id;
        report.problems.addAll(errors);
        return report;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClusterService.isRunning()
            || !Universe.loaded()
            || System.currentTimeMillis() < nextPoll) return;
        nextPoll = System.currentTimeMillis() + 1000;
        try {
            if (Files.isDirectory(root())) try (var entries = Files.newDirectoryStream(root())) {
                for (Path dir : entries) {
                    Path file = dir.resolve("request.json"), result = dir.resolve("target.json");
                    if (!Files.isRegularFile(file) || Files.exists(result)) continue;
                    try {
                        Request request = JSON
                            .fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Request.class);
                        if (request == null || request.move == null || request.expires < System.currentTimeMillis())
                            continue;
                        MoveRecord move = request.move;
                        if (!move.id.equals(
                            dir.getFileName()
                                .toString())
                            || !UUID.fromString(move.id)
                                .toString()
                                .equals(move.id))
                            continue;
                        if (!ClusterService.nodeId()
                            .equals(move.target)) continue;
                        Report report = inspect(move, true);
                        if (request.ids == null) report.problems.add("Нет карты ID исходной ноды");
                        else {
                            @SuppressWarnings("unchecked")
                            Map<String, Map<String, Number>> ids = (Map<String, Map<String, Number>>) (Map<?, ?>) request.ids;
                            for (String mismatch : MigrationData.idMismatches(ids))
                                report.problems.add("Карта ID: " + mismatch);
                        }
                        write(result, report);
                    } catch (Exception e) {
                        CointCore.LOG.warn("[MigrationPreflight] Invalid request {}", file, e);
                    }
                }
            }
            for (Pending p : new ArrayList<>(pending.values())) {
                Path result = root().resolve(p.request.move.id + "/target.json");
                if ((!Files.isRegularFile(result) || !p.routingDone) && p.request.expires > System.currentTimeMillis())
                    continue;
                Report report = new Report();
                report.id = p.source.id;
                report.problems.addAll(p.source.problems);
                if (!p.routingDone) report.problems.add("Не получен ответ БД при проверке домашнего сервера");
                if (Files.isRegularFile(result)) {
                    try {
                        Report target = JSON
                            .fromJson(new String(Files.readAllBytes(result), StandardCharsets.UTF_8), Report.class);
                        if (target == null || !report.id.equals(target.id) || target.problems == null)
                            throw new IOException("Повреждён результат проверки");
                        report.problems.addAll(target.problems);
                    } catch (IOException | RuntimeException e) {
                        report.problems.add("Не удалось прочитать ответ цели: " + e.getMessage());
                    }
                } else report.problems
                    .add("Целевая нода не ответила за 30 секунд. Проверьте сервер и общую папку миграций.");
                try {
                    write(root().resolve(report.id + "/report.json"), report);
                } catch (IOException e) {
                    report.problems.add("Не удалось сохранить отчёт: " + e.getMessage());
                }
                pending.remove(report.id);
                p.callback.accept(report);
            }
        } catch (Exception e) {
            CointCore.LOG.error("[MigrationPreflight] Inspection failed", e);
        }
    }
}
