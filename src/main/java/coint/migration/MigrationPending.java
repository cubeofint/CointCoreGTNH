package coint.migration;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointCore;
import coint.integration.serverutilities.RanksManager;
import coint.player.TeamsManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.data.ClaimedChunk;
import serverutils.data.ClaimedChunks;
import serverutils.data.ServerUtilitiesTeamData;
import serverutils.events.team.ForgeTeamCreatedEvent;
import serverutils.events.team.ForgeTeamPlayerJoinedEvent;
import serverutils.lib.EnumTeamColor;
import serverutils.lib.EnumTeamStatus;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.TeamType;
import serverutils.lib.data.Universe;
import serverutils.lib.math.ChunkDimPos;

/**
 * The part of an import that needs the players known to ServerUtilities: the team with its members, the claims in the
 * personal worlds, the dimension binding and the epoch ranks. Done at once for those this server has seen, and for
 * the others when they first join. Kept in cointcore/migration-pending.json until every member is placed.
 */
@EventBusSubscriber
public final class MigrationPending {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();
    private static List<Entry> entries;
    /** Joined this tick: handled on the next one, once ServerUtilities has his data. */
    private static final Set<UUID> joined = new HashSet<>();

    private MigrationPending() {}

    /** One imported team. */
    static final class Entry {

        String teamId;
        String title;
        String color;
        String owner;
        List<String> members = new ArrayList<>();
        /** Players still to be placed in the team. */
        List<String> waiting = new ArrayList<>();
        /** Epoch rank per player, removed once given. */
        Map<String, String> epochs = new LinkedHashMap<>();
        Set<String> consumedRewards = new HashSet<>();
        boolean portalRewardClaimed;
        int boundDim;
        /** {dim, x, z, loaded} */
        List<int[]> claims = new ArrayList<>();
        boolean teamDone;

        static Entry from(Map<String, Object> manifest) {
            Entry e = new Entry();
            Map<String, Object> team = MigrationImporter.map(manifest.get("team"));
            for (Map<String, Object> p : MigrationImporter.list(manifest.get("players"))) {
                String uuid = MigrationImporter.str(p.get("uuid"));
                if (p.get("epoch") != null) {
                    e.epochs.put(uuid, MigrationImporter.str(p.get("epoch")));
                }
                e.members.add(uuid);
                if (Boolean.TRUE.equals(p.get("personalDimensionRewardConsumed"))) e.consumedRewards.add(uuid);
            }
            if (team != null) {
                e.teamId = MigrationImporter.str(team.get("id"));
                e.title = MigrationImporter.str(team.get("title"));
                e.color = MigrationImporter.str(team.get("color"));
                e.owner = MigrationImporter.str(team.get("owner"));
                e.boundDim = (int) MigrationImporter.num(team.get("boundPersonalDimension"));
                e.portalRewardClaimed = Boolean.TRUE.equals(team.get("personalPortalRewardClaimed"));
                Object claims = team.get("claims");
                if (claims instanceof List<?>list) {
                    for (Object c : list) {
                        int[] v = MigrationImporter.ints(c);
                        if (v != null && v.length == 4) {
                            e.claims.add(v);
                        }
                    }
                }
            }
            e.waiting.addAll(e.members);
            e.teamDone = e.teamId == null || e.teamId.isEmpty();
            return e;
        }

        boolean finished() {
            return teamDone && waiting.isEmpty() && epochs.isEmpty();
        }
    }

    /** Adds an imported team and places whoever can be placed now: a line on how it went. */
    static String add(Entry entry) {
        load();
        entries.add(entry);
        apply(entry);
        String state = entry.finished() ? "Команда, ранги и приваты настроены."
            : "Ещё не видели этот сервер: " + entry.waiting.size()
                + " игрок(ов), их добавят в команду при первом входе.";
        if (entry.finished()) {
            entries.remove(entry);
        }
        save();
        return state;
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        load();
        if (!entries.isEmpty()) {
            joined.add(event.player.getUniqueID());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || joined.isEmpty()) {
            return;
        }
        joined.clear();
        boolean changed = false;
        for (Iterator<Entry> it = entries.iterator(); it.hasNext();) {
            Entry e = it.next();
            int before = e.waiting.size() + e.epochs.size();
            apply(e);
            changed |= before != e.waiting.size() + e.epochs.size();
            if (e.finished()) {
                it.remove();
                CointCore.LOG.info("[Migrate] Team {} is fully set up", e.teamId);
            }
        }
        if (changed) {
            save();
        }
    }

    private static void apply(Entry e) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        if (e.consumedRewards != null) for (String id : e.consumedRewards) TeamsManager.get()
            .markPDimRewardConsumed(UUID.fromString(id));
        // Epoch ranks: given to whoever ServerUtilities knows.
        RanksManager ranks = RanksManager.get();
        for (Iterator<Map.Entry<String, String>> it = e.epochs.entrySet()
            .iterator(); it.hasNext();) {
            Map.Entry<String, String> rank = it.next();
            UUID uuid = UUID.fromString(rank.getKey());
            if (ranks == null || universe.getPlayer(uuid) == null) {
                continue;
            }
            try {
                ranks.setRank(uuid, rank.getValue());
                it.remove();
            } catch (Exception ex) {
                CointCore.LOG.warn("[Migrate] Could not give epoch {} to {}", rank.getValue(), uuid, ex);
            }
        }
        if (e.teamId == null || e.teamId.isEmpty()) {
            e.waiting.clear();
            return;
        }
        ForgeTeam team = team(universe, e);
        if (team == null) {
            return;
        }
        for (Iterator<String> it = e.waiting.iterator(); it.hasNext();) {
            ForgePlayer p = universe.getPlayer(UUID.fromString(it.next()));
            if (p != null && join(team, p)) {
                it.remove();
            }
        }
        if (!e.teamDone) {
            if (e.portalRewardClaimed) TeamsManager.get()
                .markPDimRewardTeamClaimed(team);
            if (e.boundDim > 0) {
                TeamsManager manager = TeamsManager.get();
                Short holder = manager.getTeamUidForDim(e.boundDim);
                if (holder != null && holder != team.getUID()) {
                    // Another team (or one gone) holds the world here: the binding is ours now.
                    manager.pdBinds.remove(holder);
                    manager.markDirty();
                }
                manager.bindDim(team, e.boundDim);
            }
            claim(team, e.claims);
            e.teamDone = true;
        }
    }

    /** The team as it was exported: found by id, or made with the first member this server knows as owner. */
    private static ForgeTeam team(Universe universe, Entry e) {
        ForgeTeam team = universe.getTeam(e.teamId);
        if (team != null && team.isValid()) {
            ForgePlayer owner = team.getOwner();
            if (owner == null || e.members.contains(
                owner.getId()
                    .toString())) {
                return team;
            }
            // The id is someone else's team here: ours gets another one.
            e.teamId = e.teamId + "_m";
            team = universe.getTeam(e.teamId);
            if (team != null && team.isValid()) {
                return team;
            }
        }
        ForgePlayer owner = e.owner == null || e.owner.isEmpty() ? null : universe.getPlayer(UUID.fromString(e.owner));
        if (owner == null) {
            for (String m : e.members) {
                owner = universe.getPlayer(UUID.fromString(m));
                if (owner != null) {
                    break;
                }
            }
        }
        if (owner == null) {
            return null;
        }
        leave(owner);
        universe.clearCache();
        team = new ForgeTeam(universe, universe.generateTeamUID((short) 0), e.teamId, TeamType.PLAYER);
        EnumTeamColor color = EnumTeamColor.NAME_MAP.get(e.color == null ? "" : e.color.toLowerCase());
        if (color != null) {
            team.setColor(color);
        }
        if (e.title != null && !e.title.isEmpty()) {
            team.setTitle(e.title);
        }
        owner.team = team;
        team.owner = owner;
        universe.addTeam(team);
        new ForgeTeamCreatedEvent(team).post();
        new ForgeTeamPlayerJoinedEvent(owner).post();
        team.markDirty();
        owner.markDirty();
        CointCore.LOG.info("[Migrate] Made team {} for {}", e.teamId, owner.getName());
        return team;
    }

    private static boolean join(ForgeTeam team, ForgePlayer p) {
        if (team.isMember(p)) {
            return true;
        }
        leave(p);
        team.setStatus(p, EnumTeamStatus.INVITED);
        boolean ok = team.addMember(p, false);
        if (!ok) {
            CointCore.LOG.warn("[Migrate] Could not put {} into team {}", p.getName(), team.getId());
        }
        return ok;
    }

    /** Leaves the team he has here (handing it over, or closing it when he is alone in it). */
    private static void leave(ForgePlayer p) {
        if (!p.hasTeam()) {
            return;
        }
        ForgeTeam old = p.team;
        if (old.isOwner(p)) {
            for (ForgePlayer m : old.getMembers()) {
                if (m != p) {
                    old.owner = m;
                    break;
                }
            }
        }
        if (old.isOwner(p)) {
            Universe.get()
                .removeTeam(old);
            p.team = null;
            p.markDirty();
        } else if (!old.removeMember(p)) {
            p.team = null;
            p.markDirty();
        }
        old.markDirty();
    }

    private static void claim(ForgeTeam team, List<int[]> claims) {
        ClaimedChunks chunks = ClaimedChunks.instance;
        if (chunks == null) {
            return;
        }
        ServerUtilitiesTeamData data = ServerUtilitiesTeamData.get(team);
        chunks.processQueue();
        for (int[] c : claims) {
            ChunkDimPos pos = new ChunkDimPos(c[1], c[2], c[0]);
            ClaimedChunk old = chunks.getChunk(pos);
            if (old != null) {
                if (!old.getTeam()
                    .getId()
                    .equals(team.getId())) throw new IllegalStateException("Чанк занят другой командой: " + pos);
                chunks.removeChunk(pos);
            }
            ClaimedChunk chunk = new ClaimedChunk(pos, data);
            chunks.addChunk(chunk);
            if (c[3] != 0) {
                // Chunks are forced in a loaded world only.
                if (net.minecraftforge.common.DimensionManager.getWorld(c[0]) == null) {
                    net.minecraftforge.common.DimensionManager.initDimension(c[0]);
                }
                chunk.setLoaded(true);
            }
        }
        chunks.processQueue();
    }

    // ---------------------------------------------------------------- the file

    private static File file() {
        return new File(MigrationImporter.serverDir(), "cointcore/migration-pending.json");
    }

    private static void load() {
        if (entries != null) {
            return;
        }
        entries = new ArrayList<>();
        File file = file();
        if (!file.isFile()) {
            return;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            List<Entry> read = GSON.fromJson(reader, new TypeToken<List<Entry>>() {}.getType());
            if (read != null) {
                entries.addAll(read);
            }
        } catch (IOException | RuntimeException e) {
            CointCore.LOG.error("[Migrate] Could not read {}", file, e);
        }
    }

    private static void save() {
        File file = file();
        try {
            java.nio.file.Files.createDirectories(
                file.toPath()
                    .getParent());
            java.nio.file.Path temporary = file.toPath()
                .resolveSibling(file.getName() + ".tmp-" + UUID.randomUUID());
            try (Writer writer = java.nio.file.Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(entries, writer);
            }
            java.nio.file.Files.move(
                temporary,
                file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist imported team " + file, e);
        }
    }

    /** Forgets what was read when the server stops (another world may be loaded next). */
    public static void reset() {
        entries = null;
        joined.clear();
    }
}
