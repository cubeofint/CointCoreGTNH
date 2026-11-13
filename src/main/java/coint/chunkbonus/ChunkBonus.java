package coint.chunkbonus;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointCore;
import coint.util.TimeUtil;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.data.ClaimedChunk;
import serverutils.data.ClaimedChunks;
import serverutils.data.ServerUtilitiesTeamData;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

/**
 * Chunk loading bought on the website: extra force-loaded chunks for a while. The site runs
 * {@code /chunkbonus add <nick> <chunks> <days>} over RCON; the chunks are added to the limit of the team the buyer is
 * in (he buys for his team; one who has no team yet gets them once he has one). Purchases add up, each runs out on its
 * own. When one runs out and the team loads more than it may, the extra chunks are unloaded.
 *
 * <p>
 * Kept in {@code cointcore/chunk_bonus.json}. Server thread only.
 */
@EventBusSubscriber
public final class ChunkBonus {

    private static final File FILE = new File("cointcore", "chunk_bonus.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();
    private static final int CHECK_EVERY_TICKS = 20 * 60;

    private static List<Entry> entries;
    private static int ticks;

    private ChunkBonus() {}

    /** One purchase. */
    public static final class Entry {

        /** Lower-case name of the buyer. */
        public String player;
        public int chunks;
        public long addedAt, expiresAt;
        public String note;
    }

    private static List<Entry> entries() {
        if (entries == null) {
            entries = load();
        }
        return entries;
    }

    public static void reset() {
        entries = null;
        ticks = 0;
    }

    public static String migrationSnapshot(java.util.Collection<String> names) {
        Set<String> owners = new HashSet<>();
        for (String name : names) owners.add(name.toLowerCase(Locale.ROOT));
        List<Entry> selected = new ArrayList<>();
        for (Entry entry : entries()) if (owners.contains(entry.player)) selected.add(entry);
        return GSON.toJson(selected);
    }

    public static String migrationNormalized(String json, long now) {
        List<Entry> values = GSON.fromJson(json, new TypeToken<List<Entry>>() {}.getType());
        if (values == null) throw new IllegalArgumentException("Missing chunk bonus snapshot");
        List<String> normalized = new ArrayList<>();
        for (Entry entry : values) if (entry.expiresAt > now) normalized.add(GSON.toJson(entry));
        java.util.Collections.sort(normalized);
        return normalized.toString();
    }

    /** Strict persistence for a move: an I/O error must prevent its completion receipt. */
    public static void migrationRestore(String json, java.util.Collection<String> names, boolean clear)
        throws IOException {
        Set<String> owners = new HashSet<>();
        for (String name : names) owners.add(name.toLowerCase(Locale.ROOT));
        List<Entry> incoming = GSON.fromJson(json, new TypeToken<List<Entry>>() {}.getType());
        if (incoming == null) throw new IOException("Missing chunk bonus snapshot");
        for (Entry entry : incoming)
            if (entry == null || !owners.contains(entry.player) || entry.chunks <= 0 || entry.expiresAt < entry.addedAt)
                throw new IOException("Invalid chunk bonus snapshot");
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries()) if (!owners.contains(entry.player)) result.add(entry);
        if (!clear) result.addAll(incoming);
        Files.createDirectories(
            FILE.toPath()
                .getParent());
        java.nio.file.Path temporary = FILE.toPath()
            .resolveSibling("chunk_bonus.migration-" + java.util.UUID.randomUUID() + ".tmp");
        Files.write(
            temporary,
            GSON.toJson(result)
                .getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, FILE.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        entries = result;
    }

    /** Adds a purchase with a duration in milliseconds. */
    static Entry add(String player, int chunks, long durationMs, String note) {
        Entry e = new Entry();
        e.player = player.toLowerCase(Locale.ROOT);
        e.chunks = chunks;
        e.addedAt = System.currentTimeMillis();
        if (durationMs < 60_000L || durationMs > 3650L * 86_400_000L) {
            throw new IllegalArgumentException("Chunk bonus duration must be between 1 minute and 3650 days");
        }
        e.expiresAt = Math.addExact(e.addedAt, durationMs);
        e.note = note;
        entries().add(e);
        save();
        refresh(e.player);
        CointCore.LOG.info(
            "[ChunkBonus] +{} loaded chunks for {} for {}{}",
            chunks,
            player,
            TimeUtil.formatDuration(durationMs),
            note == null ? "" : " (" + note + ")");
        return e;
    }

    /** Removes the purchases of a player; all of them when {@code index} is negative. How many went. */
    static int remove(String player, int index) {
        String key = player.toLowerCase(Locale.ROOT);
        List<Entry> mine = of(key);
        int removed = 0;
        if (index < 0) {
            removed = mine.size();
            entries().removeAll(mine);
        } else if (index < mine.size()) {
            entries().remove(mine.get(index));
            removed = 1;
        }
        if (removed > 0) {
            save();
            refresh(key);
        }
        return removed;
    }

    /** The purchases of a player still running, oldest first. */
    static List<Entry> of(String player) {
        String key = player.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        List<Entry> result = new ArrayList<>();
        for (Entry e : entries()) {
            if (e.player.equals(key) && e.expiresAt > now) {
                result.add(e);
            }
        }
        return result;
    }

    /** Extra loaded chunks of one player now. */
    public static int bonusOf(String player) {
        if (player == null) {
            return 0;
        }
        long sum = 0;
        for (Entry e : of(player)) {
            sum += e.chunks;
        }
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    /** Extra loaded chunks of a team now: what its members bought. */
    public static int bonusOf(ForgeTeam team) {
        long sum = 0;
        for (ForgePlayer member : team.getMembers()) {
            sum += bonusOf(member.getName());
        }
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks < CHECK_EVERY_TICKS) {
            return;
        }
        ticks = 0;
        long now = System.currentTimeMillis();
        Set<String> ended = new HashSet<>();
        List<Entry> list = entries();
        for (Entry e : list) {
            if (e.expiresAt <= now) {
                ended.add(e.player);
            }
        }
        if (ended.isEmpty()) {
            return;
        }
        list.removeIf(e -> e.expiresAt <= now);
        save();
        for (String player : ended) {
            CointCore.LOG.info("[ChunkBonus] A chunk loading purchase of {} ran out", player);
            refresh(player);
        }
    }

    /** The limit of the player's team is counted again; what it loads beyond it is unloaded. */
    private static void refresh(String player) {
        Universe universe = Universe.loaded() ? Universe.get() : null;
        ForgePlayer forgePlayer = universe == null ? null : universe.getPlayer(player);
        if (forgePlayer == null || forgePlayer.team == null || !forgePlayer.team.isValid()) {
            return;
        }
        ForgeTeam team = forgePlayer.team;
        ServerUtilitiesTeamData data = ServerUtilitiesTeamData.get(team);
        data.clearCache();
        enforce(team, data.getMaxChunkloaderChunks());
    }

    private static void enforce(ForgeTeam team, int max) {
        if (max < 0 || !ClaimedChunks.isActive()) {
            return;
        }
        List<ClaimedChunk> loaded = new ArrayList<>();
        for (ClaimedChunk chunk : ClaimedChunks.instance.getTeamChunks(team, java.util.OptionalInt.empty())) {
            if (chunk.isLoaded()) {
                loaded.add(chunk);
            }
        }
        int extra = loaded.size() - max;
        if (extra <= 0) {
            return;
        }
        for (int i = loaded.size() - 1; i >= 0 && extra > 0; i--, extra--) {
            ClaimedChunks.instance.unloadChunk(
                null,
                loaded.get(i)
                    .getPos());
        }
        int unloaded = loaded.size() - max;
        CointCore.LOG.info("[ChunkBonus] Team {} loaded {} chunks over its limit of {}: unloaded", team, unloaded, max);
        for (ForgePlayer member : team.getMembers()) {
            EntityPlayerMP online = member.getNullablePlayer();
            if (online != null) {
                online.addChatMessage(
                    new ChatComponentText(
                        "§eОплаченная прогрузка чанков закончилась: лимит команды теперь " + max
                            + ", лишние "
                            + unloaded
                            + " чанк(ов) выгружены. Продлить можно на сайте."));
            }
        }
    }

    private static List<Entry> load() {
        if (!FILE.isFile()) {
            return new ArrayList<>();
        }
        try (Reader reader = Files.newBufferedReader(FILE.toPath(), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<List<Entry>>() {}.getType();
            List<Entry> list = GSON.fromJson(reader, type);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (IOException | RuntimeException e) {
            // Put aside, not overwritten: the purchases in it can be put back by hand.
            File aside = new File(FILE.getPath() + ".broken-" + System.currentTimeMillis());
            boolean moved = FILE.renameTo(aside);
            CointCore.LOG.error(
                "[ChunkBonus] Could not read {}{}: starting with no purchases",
                FILE,
                moved ? ", moved to " + aside.getName() : "",
                e);
            return new ArrayList<>();
        }
    }

    private static void save() {
        File dir = FILE.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            CointCore.LOG.error("[ChunkBonus] Could not create {}", dir);
            return;
        }
        File tmp = new File(FILE.getPath() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(entries(), writer);
        } catch (IOException e) {
            CointCore.LOG.error("[ChunkBonus] Could not save {}", FILE, e);
            return;
        }
        try {
            Files
                .move(tmp.toPath(), FILE.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp.toPath(), FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                CointCore.LOG.error("[ChunkBonus] Could not save {}", FILE, e2);
            }
        }
    }
}
