package coint.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.event.CommandEvent;

import com.google.gson.Gson;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import coint.cluster.ClusterHomes;
import coint.cluster.ClusterService;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.ServerUtilitiesGameRules;
import serverutils.command.team.CmdJoin;
import serverutils.command.team.CmdTeam;
import serverutils.lib.EnumTeamStatus;
import serverutils.lib.command.CommandUtils;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

/** Accept an invitation on Endgame without changing membership before the personal move succeeds. */
@EventBusSubscriber
public final class MigrationJoining {

    private static final Gson JSON = new Gson();
    private static long next;
    private static long generation;
    private static final Set<UUID> lookingUp = new HashSet<>();
    private static final Set<String> notices = new HashSet<>();

    private MigrationJoining() {}

    private static final class Intent {

        String id, player, source, target, team, owner, requestId, error;
        long expires;
    }

    public static void reset() {
        generation++;
        next = 0;
        lookingUp.clear();
        notices.clear();
    }

    private static void tell(EntityPlayerMP player, String text) {
        if (player.playerNetServerHandler != null && !player.isDead) player.addChatMessage(new ChatComponentText(text));
    }

    private static Path directory() {
        return MigrationStorage.archives()
            .toPath()
            .resolve("team-join-requests");
    }

    /** Both SU's team selector and typed /team join go through Forge's command event. */
    public static boolean command(CommandEvent event) {
        if (!CointConfig.cluster.migrationEnabled || !ClusterService.isRunning()
            || !Universe.loaded()
            || !(event.sender instanceof EntityPlayerMP actor)
            || !ClusterService.nodeId()
                .equals(CointConfig.cluster.migrationQuestTargetNode.trim()))
            return false;
        String teamId;
        if (event.command instanceof CmdTeam && event.parameters.length == 2
            && "join".equalsIgnoreCase(event.parameters[0])) teamId = event.parameters[1];
        else if (event.command instanceof CmdJoin && event.parameters.length == 1) teamId = event.parameters[0];
        else return false;
        String known = ClusterHomes.homeOf(actor.getUniqueID());
        if (ClusterService.nodeId()
            .equals(known)) return false;
        event.setCanceled(true);
        if (!lookingUp.add(actor.getUniqueID())) {
            tell(actor, "§eПроверяем приглашение. Дождитесь ответа.");
            return true;
        }
        long session = generation;
        try {
            if (!ServerUtilitiesGameRules.canJoinTeam(actor.worldObj))
                throw new IOException("Вступление в команды отключено на сервере");
            ForgePlayer player = Universe.get()
                .getPlayer(actor);
            ForgeTeam team = CommandUtils.getTeam(actor, teamId);
            if (player == null || team == null || !team.isValid() || team.getOwner() == null || !invited(team, player))
                throw new IOException("Нет действующего приглашения в эту команду");
            ForgePlayer owner = team.getOwner();
            if (!ClusterHomes.lookupPair(player.getId(), player.getName(), owner.getId(), owner.getName(), homes -> {
                if (session != generation) return;
                lookingUp.remove(actor.getUniqueID());
                try {
                    if (!player.isOnline() || player.getPlayer() != actor) return;
                    if (!team.isValid() || !invited(team, player) || team.getOwner() != owner)
                        throw new IOException("Приглашение или владелец команды изменились");
                    if (ClusterService.nodeId()
                        .equals(homes[0])) {
                        new CmdJoin().processCommand(actor, new String[] { team.getId() });
                        return;
                    }
                    if (!CointConfig.cluster.migrationQuestSourceNode.trim()
                        .equals(homes[0])
                        || !ClusterService.nodeId()
                            .equals(homes[1]))
                        throw new IOException("Вступление с переносом доступно из Alpha в команду Endgame");
                    if (MigrationConsent.personalProposal(actor) && MigrationConsent.reopen(actor)) return;
                    enqueue(player, team, homes[0]);
                    tell(
                        actor,
                        "§eПриглашение принято для проверки. Подтвердите перенос личных данных с " + homes[0]
                            + ". База прежней команды остаётся там. Повторно открыть: /migrate ui");
                } catch (IOException | RuntimeException e) {
                    tell(actor, "§cВступление не начато: " + e.getMessage());
                }
            }, error -> {
                if (session != generation) return;
                lookingUp.remove(actor.getUniqueID());
                tell(actor, "§cНе удалось проверить домашний сервер. Повторите приглашение позже.");
            })) throw new IOException("Кластер выключен");
        } catch (IOException | RuntimeException e) {
            lookingUp.remove(actor.getUniqueID());
            tell(actor, "§cВступление не начато: " + e.getMessage());
        }
        return true;
    }

    private static void enqueue(ForgePlayer player, ForgeTeam team, String home) throws IOException {
        String node = ClusterService.nodeId();
        for (Intent old : intents()) if (old.player.equals(
            player.getId()
                .toString())
            && old.expires > System.currentTimeMillis()
            && old.error == null
            && old.requestId == null) return;
        Intent r = new Intent();
        r.id = UUID.randomUUID()
            .toString();
        r.player = player.getId()
            .toString();
        r.source = home;
        r.target = node;
        r.team = team.getId();
        r.owner = team.getOwner()
            .getId()
            .toString();
        r.expires = System.currentTimeMillis() + 10 * 60_000L;
        save(r);
    }

    private static void save(Intent r) throws IOException {
        Files.createDirectories(directory());
        Path temp = directory().resolve(r.id + ".tmp");
        Files.write(
            temp,
            JSON.toJson(r)
                .getBytes(StandardCharsets.UTF_8));
        Files.move(
            temp,
            directory().resolve(r.id + ".json"),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
    }

    private static java.util.List<Intent> intents() throws IOException {
        java.util.List<Intent> result = new java.util.ArrayList<>();
        if (!Files.isDirectory(directory())) return result;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory(), "*.json")) {
            for (Path file : files) {
                Intent r = JSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Intent.class);
                // Older disabled joining requests did not have an expiry or an authenticated invitation ID.
                if (r == null || r.id == null || r.expires <= System.currentTimeMillis()) continue;
                if (!UUID.fromString(r.id)
                    .toString()
                    .equals(r.id)
                    || !file.getFileName()
                        .toString()
                        .equals(r.id + ".json")
                    || r.player == null
                    || r.owner == null
                    || r.team == null
                    || r.team.isEmpty()
                    || !CointConfig.cluster.migrationQuestSourceNode.trim()
                        .equals(r.source)
                    || !CointConfig.cluster.migrationQuestTargetNode.trim()
                        .equals(r.target))
                    throw new IOException("Повреждён запрос вступления " + file.getFileName());
                UUID.fromString(r.player);
                UUID.fromString(r.owner);
                result.add(r);
            }
        }
        return result;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !CointConfig.cluster.migrationEnabled
            || !ClusterService.isRunning()
            || !Universe.loaded()
            || System.currentTimeMillis() < next) return;
        next = System.currentTimeMillis() + 3000;
        try {
            for (Intent r : intents()) {
                if (r.expires < System.currentTimeMillis()) continue;
                if (r.error != null) {
                    EntityPlayerMP actor = MigrationImporter.online(UUID.fromString(r.player));
                    if (actor != null && notices.add(r.id)) tell(actor, "§cВступление не начато: " + r.error);
                    continue;
                }
                if (!ClusterService.nodeId()
                    .equals(r.source) || r.requestId != null) continue;
                ForgePlayer player = Universe.get()
                    .getPlayer(UUID.fromString(r.player));
                if (player == null) {
                    r.error = "На исходной ноде нет профиля игрока";
                } else {
                    try {
                        r.requestId = MigrationConsent
                            .requestJoin(MinecraftServer.getServer(), player, r.target, r.team, r.owner);
                    } catch (IOException e) {
                        r.error = e.getMessage();
                    }
                }
                save(r);
            }
        } catch (IOException | RuntimeException e) {
            next = System.currentTimeMillis() + 30_000;
            CointCore.LOG.error("[TeamJoin] Cannot read requests", e);
        }
    }

    static ForgeTeam checkTarget(MoveRecord move) throws IOException {
        ForgeTeam team = target(move);
        ForgePlayer player = Universe.get()
            .getPlayer(UUID.fromString(move.players.get(0)));
        if (player == null || !(team.isMember(player) || invited(team, player)))
            throw new IOException("Приглашение/членство в целевой команде отозвано");
        if (player.hasTeam() && !team.equalsTeam(player.team))
            throw new IOException("На целевой ноде игрок уже состоит в другой команде: " + player.getName());
        return team;
    }

    private static boolean invited(ForgeTeam team, ForgePlayer player) {
        // Free-to-join teams are not an invitation to migrate personal data to another node.
        return team.isInvited(player) && team.players.get(player) == EnumTeamStatus.INVITED;
    }

    private static ForgeTeam target(MoveRecord move) throws IOException {
        ForgeTeam team = Universe.get()
            .getTeam(move.joinTeam);
        if (team == null || !team.isValid()
            || team.getOwner() == null
            || !team.getOwner()
                .getId()
                .toString()
                .equals(move.joinOwner))
            throw new IOException("Целевая команда или её владелец изменились");
        return team;
    }

    static void install(MoveRecord move) throws IOException {
        ForgeTeam team = checkTarget(move);
        ForgePlayer player = Universe.get()
            .getPlayer(UUID.fromString(move.players.get(0)));
        if (player == null) throw new IOException("Нет импортированного профиля игрока");
        if (!team.isMember(player)) {
            detach(player);
            team.setStatus(player, EnumTeamStatus.INVITED);
            if (!team.addMember(player, false)) throw new IOException("Не удалось включить игрока в целевую команду");
        }
        if (cpw.mods.fml.common.Loader.isModLoaded("betterquesting")) QuestData.joinParty(
            player.getId(),
            team.getOwner()
                .getId());
        player.markDirty();
        team.markDirty();
    }

    static void retireMember(MoveRecord move) throws IOException {
        ForgePlayer player = Universe.get()
            .getPlayer(UUID.fromString(move.players.get(0)));
        if (player != null) detach(player);
        if (cpw.mods.fml.common.Loader.isModLoaded("betterquesting"))
            QuestData.leaveParty(UUID.fromString(move.players.get(0)));
    }

    /** Preserve the old team's claims and dimension even when its last member leaves. */
    private static void detach(ForgePlayer player) {
        if (!player.hasTeam()) return;
        ForgeTeam old = player.team;
        if (old.isOwner(player)) {
            ForgePlayer successor = old.getMembers()
                .stream()
                .filter(
                    p -> !p.getId()
                        .equals(player.getId()))
                .findFirst()
                .orElse(null);
            // SU requires a real owner for a player team to remain valid, including after a restart.
            // Keep the retired source profile as the owner if nobody remains to take over the base.
            if (successor == null) return;
            old.owner = successor;
        }
        player.team = Universe.get()
            .getTeam("");
        old.setStatus(player, EnumTeamStatus.NONE);
        old.clearCache();
        old.markDirty();
        player.clearCache();
        player.markDirty();
        Universe.get()
            .clearCache();
    }
}
