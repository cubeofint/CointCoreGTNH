package coint.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.google.gson.Gson;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import coint.cluster.ClusterService;
import coint.cluster.MigrationPresence;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

/** Leader-authorized warning and disconnect sequence; the existing move owns all data transfer. */
@EventBusSubscriber
public final class MigrationConsent {

    private static final Gson JSON = new Gson();
    private static final Set<String> notices = new HashSet<>();
    private static boolean querying;
    private static long nextPoll, generation;
    private static String boot = UUID.randomUUID()
        .toString();

    private MigrationConsent() {}

    private static final class Request {

        String id, source, target, team, player, sourceBoot;
        String joinTeam, joinOwner;
        String leader;
        boolean leaderAuthorized;
        List<String> members = new ArrayList<>();
        long expires, countdown;
        String phase = "pending";
    }

    public static void reset() {
        generation++;
        boot = UUID.randomUUID()
            .toString();
        querying = false;
        nextPoll = 0;
        notices.clear();
    }

    private static Path root() {
        return MigrationStorage.archives()
            .toPath()
            .resolve("consent-requests");
    }

    private static Path dir(Request r) {
        return root().resolve(r.id);
    }

    private static void save(Request r) throws IOException {
        Path temp = dir(r).resolve("request.tmp");
        Files.write(
            temp,
            JSON.toJson(r)
                .getBytes(StandardCharsets.UTF_8));
        Files.move(
            temp,
            dir(r).resolve("request.json"),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
    }

    private static Request read(Path file) throws IOException {
        Request r = JSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Request.class);
        if (r == null || !UUID.fromString(r.id)
            .toString()
            .equals(r.id)
            || !file.getParent()
                .getFileName()
                .toString()
                .equals(r.id)
            || r.members == null
            || r.members.isEmpty()
            || r.source == null
            || r.target == null
            || r.team == null
            || r.player == null) throw new IOException("Повреждён запрос согласия");
        for (String id : r.members) UUID.fromString(id);
        return r;
    }

    private static boolean active(Request r) {
        return r.joinTeam == null && r.leaderAuthorized
            && System.currentTimeMillis() < r.expires
            && ("pending".equals(r.phase) || "countdown".equals(r.phase) || "drain".equals(r.phase));
    }

    private static List<Request> requests() throws IOException {
        List<Request> result = new ArrayList<>();
        if (!Files.exists(root())) return result;
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(root())) {
            for (Path entry : entries) {
                Path file = entry.resolve("request.json");
                if (Files.isRegularFile(file)) {
                    Request r = read(file);
                    if (System.currentTimeMillis() < r.expires + 60_000L) result.add(r);
                }
            }
        }
        return result;
    }

    private static void mark(Request r, String player, String kind) throws IOException {
        Path file = dir(r).resolve(player + "." + kind);
        try {
            Files.write(file, new byte[0], StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException ignored) { /* Idempotent per authenticated UUID. */ }
    }

    private static Set<String> members(ForgeTeam team) {
        Set<String> result = new HashSet<>();
        for (ForgePlayer member : team.getMembers()) result.add(
            member.getId()
                .toString());
        return result;
    }

    public static void request(ICommandSender sender, ForgePlayer player, String target) {
        requestJoin(sender, player, target, null, null);
    }

    static void requestJoin(ICommandSender sender, ForgePlayer player, String target, String joinTeam,
        String joinOwner) {
        try {
            if (joinTeam != null) throw new IOException("Перенос отдельного игрока отключён; переносите всю команду");
            if (sender instanceof EntityPlayerMP actor && !coint.commands.CommandMigrate.allowed(sender)
                && (!player.hasTeam() || !player.team.isOwner(
                    Universe.get()
                        .getPlayer(actor))))
                throw new IOException("Перенос запускает только лидер своей команды или администратор");
            if (!CointConfig.cluster.migrationEnabled || !ClusterService.isRunning()
                || (!player.hasTeam() && joinTeam == null))
                throw new IOException("Нужны работающий кластер, миграция и команда SU");
            if (!target.matches("[A-Za-z0-9_-]{1,64}") || target.equals(ClusterService.nodeId()))
                throw new IOException("Неверная целевая нода");
            Request r = new Request();
            r.id = UUID.randomUUID()
                .toString();
            r.source = ClusterService.nodeId();
            r.sourceBoot = boot;
            r.target = target;
            r.team = player.hasTeam() ? player.team.getId() : "unassigned";
            r.joinTeam = joinTeam;
            r.joinOwner = joinOwner;
            r.player = player.getName();
            r.leader = player.hasTeam() && player.team.getOwner() != null ? player.team.getOwner()
                .getId()
                .toString() : null;
            r.leaderAuthorized = true;
            if (joinTeam == null) r.members.addAll(members(player.team));
            else r.members.add(
                player.getId()
                    .toString());
            r.expires = System.currentTimeMillis() + 10 * 60_000L;
            if (joinTeam == null && MigrationExporter.findTeamDimensions(player.team)
                .isEmpty()) throw new IOException("Нет персоналки команды");
            for (Request old : requests()) if (active(old)) for (String id : r.members)
                if (old.members.contains(id)) throw new IOException("У команды уже есть запрос " + old.id);
            for (String id : r.members) {
                String locked = MigrationGuard.loginReason(UUID.fromString(id));
                if (locked != null) throw new IOException(locked);
            }
            Files.createDirectories(dir(r));
            // Local players are required immediately, even before the next DB heartbeat.
            for (Object o : MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList) {
                EntityPlayerMP p = (EntityPlayerMP) o;
                if (r.members.contains(
                    p.getUniqueID()
                        .toString()))
                    mark(
                        r,
                        p.getUniqueID()
                            .toString(),
                        "required");
            }
            save(r);
            sender.addChatMessage(
                new ChatComponentText(
                    "§eПеренос команды " + r.id + " поставлен в очередь. Участников предупредим перед отключением."));
        } catch (IOException | RuntimeException e) {
            sender.addChatMessage(new ChatComponentText("§cЗапрос не создан: " + e.getMessage()));
        }
    }

    public static void answer(EntityPlayerMP player, String id, boolean yes) throws IOException {
        if (!UUID.fromString(id)
            .toString()
            .equals(id)) throw new IOException("Неверный ID");
        Request r = read(
            root().resolve(id)
                .resolve("request.json"));
        String uuid = player.getUniqueID()
            .toString();
        if (!active(r) || !r.members.contains(uuid) || "drain".equals(r.phase))
            throw new IOException("Запрос недоступен");
        if (!uuid.equals(r.leader)) throw new IOException("Отменить перенос может только лидер команды");
        mark(r, uuid, "required");
        mark(r, uuid, yes ? "yes" : "no");
        player.addChatMessage(
            new ChatComponentText(
                yes ? "§aПеренос уже разрешён лидером. Перед отключением будет предупреждение."
                    : "§eЛидер отменил перенос."));
    }

    public static String loginReason(UUID player) {
        if (!CointConfig.cluster.migrationEnabled || player == null) return null;
        try {
            for (Request r : requests()) if (active(r) && r.members.contains(player.toString())) {
                if (!"pending".equals(r.phase)) return "Команда готовится к переносу. Дождитесь завершения.";
                mark(r, player.toString(), "required");
            }
        } catch (IOException | RuntimeException e) {
            return "Не удалось проверить согласование переноса. Повторите вход позже.";
        }
        return null;
    }

    private static boolean agreed(Request r, Set<String> online) throws IOException {
        if (r.leaderAuthorized) return r.leader != null && !Files.exists(dir(r).resolve(r.leader + ".no"));
        boolean all = true;
        for (String id : r.members) {
            if (online.contains(id)) mark(r, id, "required");
            if (Files.exists(dir(r).resolve(id + ".no"))) {
                return false;
            }
            if (Files.exists(dir(r).resolve(id + ".required")) && !Files.exists(dir(r).resolve(id + ".yes")))
                all = false;
        }
        return all;
    }

    private static void announce(Request r, String text) {
        CointCore.LOG.info("[MigrationConsent] {}: {}", r.id, text);
        for (String uuid : r.members) {
            EntityPlayerMP p = MigrationImporter.online(UUID.fromString(uuid));
            if (p != null) p.addChatMessage(new ChatComponentText(text));
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClusterService.isRunning()
            || !CointConfig.cluster.migrationEnabled
            || !Universe.loaded()
            || querying
            || System.currentTimeMillis() < nextPoll) return;
        nextPoll = System.currentTimeMillis() + 1000;
        try {
            List<Request> all = requests();
            for (Request r : all) for (String uuid : r.members) {
                EntityPlayerMP p = MigrationImporter.online(UUID.fromString(uuid));
                if (p == null) continue;
                if (!active(r)) {
                    if (!"dispatched".equals(r.phase) && notices.add(r.id + uuid + ":closed")) p.addChatMessage(
                        new ChatComponentText(
                            "§eЗапрос переноса закрыт: отказ, отмена или истечение срока. Данные не переносятся этим запросом."));
                    continue;
                }
                mark(r, uuid, "required");
                if ("pending".equals(r.phase) && notices.add(r.id + uuid + ":" + r.phase)) {
                    p.addChatMessage(
                        new ChatComponentText(
                            "§eЛидер/администратор запустил перенос всей команды: " + r.source
                                + " → "
                                + r.target
                                + ". Отключение будет после предупреждения."));
                }
                if ("countdown".equals(r.phase) && notices.add(r.id + uuid + ":" + r.countdown)) p.addChatMessage(
                    new ChatComponentText(
                        "§eПеренос всей команды. Отключение для сохранения данных через 15 секунд. Лидер может отменить: /migrate-confirm "
                            + r.id
                            + " no"));
                if ("drain".equals(r.phase) && agreed(r, java.util.Collections.emptySet()))
                    p.playerNetServerHandler.kickPlayerFromServer(
                        "Перенос команды на " + r.target + ". Дождитесь завершения и подключитесь снова.");
            }
            List<Request> owned = new ArrayList<>();
            for (Request r : all) if (active(r) && r.source.equals(ClusterService.nodeId())) owned.add(r);
            if (owned.isEmpty()) return;
            querying = true;
            long session = generation;
            MigrationPresence.online(snapshot -> {
                if (session != generation) return;
                querying = false;
                Set<String> online = snapshot.players;
                for (Request stale : owned) try {
                    Request r = read(dir(stale).resolve("request.json"));
                    if (!active(r)) continue;
                    boolean refused = false;
                    refused = r.leader != null && Files.exists(dir(r).resolve(r.leader + ".no"));
                    if (refused) {
                        r.phase = "cancelled";
                        save(r);
                        announce(r, "§eПеренос отменён участником команды.");
                        continue;
                    }
                    if (!snapshot.nodes.contains(r.target)) {
                        r.phase = "cancelled";
                        save(r);
                        announce(r, "§eЗапрос отменён: целевая нода недоступна.");
                        continue;
                    }
                    if (!boot.equals(r.sourceBoot)) {
                        r.phase = "cancelled";
                        save(r);
                        announce(r, "§eСогласование отменено после перезапуска исходной ноды. Создайте новый запрос.");
                        continue;
                    }
                    ForgeTeam team = Universe.get()
                        .getTeam(r.team);
                    ForgePlayer joining = r.joinTeam == null ? null
                        : Universe.get()
                            .getPlayer(UUID.fromString(r.members.get(0)));
                    boolean changed = r.joinTeam == null
                        ? team == null || !team.isValid() || !members(team).equals(new HashSet<>(r.members))
                        : joining == null || !(joining.hasTeam() ? joining.team.getId() : "unassigned").equals(r.team);
                    if (changed || team == null
                        || team.getOwner() == null
                        || !team.getOwner()
                            .getId()
                            .toString()
                            .equals(r.leader)) {
                        r.phase = "cancelled";
                        save(r);
                        announce(r, "§cПеренос отменён: изменился состав команды.");
                        continue;
                    }
                    if (!agreed(r, online)) {
                        if ("countdown".equals(r.phase) || "drain".equals(r.phase)) {
                            r.phase = "pending";
                            r.countdown = 0;
                            save(r);
                        }
                        continue;
                    }
                    long now = System.currentTimeMillis();
                    if ("pending".equals(r.phase)) {
                        if (MigrationMove.busy()) continue;
                        r.phase = "countdown";
                        r.countdown = now + 15_000;
                        save(r);
                    } else if ("countdown".equals(r.phase) && now >= r.countdown) {
                        r.phase = "drain";
                        save(r);
                    } else if ("drain".equals(r.phase) && java.util.Collections.disjoint(online, r.members)) {
                        boolean localOnline = false;
                        for (String id : r.members)
                            localOnline |= MigrationImporter.online(UUID.fromString(id)) != null;
                        if (localOnline || MigrationMove.busy()) continue;
                        // Durable dispatch marker prevents duplicate launches after a crash. Existing move locks take
                        // over.
                        r.phase = "dispatched";
                        save(r);
                        MigrationMove.start(
                            MinecraftServer.getServer(),
                            Universe.get()
                                .getPlayer(r.player),
                            r.target,
                            r.joinTeam,
                            r.joinOwner);
                        announce(
                            r,
                            "§eОтключение команды завершено. Результат запуска миграции записан в консоли исходного сервера.");
                    }
                } catch (IOException | RuntimeException e) {
                    CointCore.LOG.error("[MigrationConsent] Request failed", e);
                }
            }, error -> {
                if (session == generation) {
                    querying = false;
                    nextPoll = System.currentTimeMillis() + 10_000;
                    CointCore.LOG.warn("[MigrationConsent] Cannot check online players: {}", error);
                }
            });
        } catch (IOException | RuntimeException e) {
            nextPoll = System.currentTimeMillis() + 10_000;
            CointCore.LOG.error("[MigrationConsent] Cannot read requests", e);
        }
    }
}
