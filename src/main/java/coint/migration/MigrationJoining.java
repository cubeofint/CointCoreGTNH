package coint.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.google.gson.Gson;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import coint.cluster.ClusterHomes;
import coint.cluster.ClusterService;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.events.team.ForgeTeamPlayerJoinedEvent;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

/** A legitimate SU join on the destination requests a personal move from the authoritative home. */
@EventBusSubscriber
public final class MigrationJoining {

    private static final Gson JSON = new Gson();
    private static long next;
    private static boolean installing;

    private MigrationJoining() {}

    private static final class Intent {

        String player, source, target, team, owner;
    }

    private static Path directory() {
        return MigrationStorage.archives()
            .toPath()
            .resolve("team-join-requests");
    }

    @SubscribeEvent
    public static void joined(ForgeTeamPlayerJoinedEvent event) {
        if (!installing) request(event.getPlayer());
    }

    public static void request(ForgePlayer player) {
        if (!CointConfig.cluster.migrationEnabled || !ClusterService.isRunning()
            || player == null
            || !player.hasTeam()
            || player.team.getOwner() == null
            || !player.isOnline()) return;
        ForgeTeam team = player.team;
        ForgePlayer owner = team.getOwner();
        String node = ClusterService.nodeId();
        ClusterHomes.lookup(player.getId(), player.getName(), home -> {
            if (home == null || home.equals(node) || !team.isMember(player)) return;
            ClusterHomes.lookup(owner.getId(), owner.getName(), ownerHome -> {
                if (!node.equals(ownerHome) || !team.isMember(player) || !owner.equals(team.getOwner())) return;
                try {
                    Intent r = new Intent();
                    r.player = player.getId()
                        .toString();
                    r.source = home;
                    r.target = node;
                    r.team = team.getId();
                    r.owner = owner.getId()
                        .toString();
                    Files.createDirectories(directory());
                    String id = UUID.randomUUID()
                        .toString();
                    Path temp = directory().resolve(id + ".tmp");
                    Files.write(
                        temp,
                        JSON.toJson(r)
                            .getBytes(StandardCharsets.UTF_8));
                    Files.move(temp, directory().resolve(id + ".json"), StandardCopyOption.ATOMIC_MOVE);
                    if (player.isOnline()) player.getPlayer()
                        .addChatMessage(
                            new ChatComponentText(
                                "§eДля вступления сюда нужно перенести ваши личные данные с " + home
                                    + ". Дождитесь запроса согласия. Старая база остаётся прежней команде."));
                } catch (IOException e) {
                    CointCore.LOG.error("[TeamJoin] Cannot request player move", e);
                }
            });
        });
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !CointConfig.cluster.migrationEnabled
            || !ClusterService.isRunning()
            || !Universe.loaded()
            || System.currentTimeMillis() < next) return;
        next = System.currentTimeMillis() + 3000;
        if (!Files.isDirectory(directory())) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory(), "*.json")) {
            for (Path file : files) {
                Intent r = JSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Intent.class);
                if (!ClusterService.nodeId()
                    .equals(r.source)) continue;
                ForgePlayer player = Universe.get()
                    .getPlayer(UUID.fromString(r.player));
                // Consume before requesting: restart must never create a second move silently.
                Files.move(file, file.resolveSibling(file.getFileName() + ".handled"), StandardCopyOption.ATOMIC_MOVE);
                if (player == null) {
                    CointCore.LOG.error("[TeamJoin] Source has no profile {}", r.player);
                    continue;
                }
                MigrationConsent.requestJoin(MinecraftServer.getServer(), player, r.target, r.team, r.owner);
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
        if (player == null || !(team.isMember(player) || team.isInvited(player)))
            throw new IOException("Приглашение/членство в целевой команде отозвано");
        return team;
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
        ForgeTeam team = target(move);
        ForgePlayer player = Universe.get()
            .getPlayer(UUID.fromString(move.players.get(0)));
        if (player == null) throw new IOException("Нет импортированного профиля игрока");
        installing = true;
        try {
            detach(player);
            team.setStatus(player, serverutils.lib.EnumTeamStatus.INVITED);
            if (!team.addMember(player, false)) throw new IOException("Не удалось включить игрока в целевую команду");
            if (cpw.mods.fml.common.Loader.isModLoaded("betterquesting")) QuestData.joinParty(
                player.getId(),
                team.getOwner()
                    .getId());
            player.markDirty();
            team.markDirty();
        } finally {
            installing = false;
        }
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
            old.owner = old.getMembers()
                .stream()
                .filter(
                    p -> !p.getId()
                        .equals(player.getId()))
                .findFirst()
                .orElse(null);
        }
        player.team = Universe.get()
            .getTeam("");
        old.setStatus(player, serverutils.lib.EnumTeamStatus.NONE);
        old.clearCache();
        old.markDirty();
        player.clearCache();
        player.markDirty();
        Universe.get()
            .clearCache();
    }
}
