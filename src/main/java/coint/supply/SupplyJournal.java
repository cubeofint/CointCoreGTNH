package coint.supply;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.world.WorldEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointCore;
import coint.cluster.SupplyLink;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * What a provider did to its ME network and when it may say so. A change is reported done only once the world holding
 * it was saved: if the server dies before, the world rolls back and the operation, still claimed by a server that
 * restarted, is done again. Saved but not yet reported changes are kept in a file, so a server that dies right after a
 * save reports them when it starts, instead of doing them twice.
 */
@EventBusSubscriber
public final class SupplyJournal {

    private static final String FILE = "cointcore_supply_journal.txt";

    /** Done here, not yet saved: operation, delivered amount, by dimension of the provider. */
    private static final List<Entry> unsaved = new ArrayList<>();
    /** Saved, not yet reported: operation → delivered amount. */
    private static final Map<UUID, Long> saved = new LinkedHashMap<>();
    private static boolean loaded;

    private SupplyJournal() {}

    /** A change made to the ME network of a provider in {@code dimension}. */
    static void done(UUID op, long delivered, int dimension) {
        unsaved.add(new Entry(op, delivered, dimension));
    }

    /** Already done by this server (saved or not): it must not be done again. */
    static boolean known(UUID op) {
        load();
        if (saved.containsKey(op)) {
            return true;
        }
        for (Entry entry : unsaved) {
            if (entry.op.equals(op)) {
                return true;
            }
        }
        return false;
    }

    /** Claimed again after a restart though it was saved done: just reported again. */
    static void reportAgain(UUID op) {
        Long delivered = saved.get(op);
        if (delivered != null) {
            report(op, delivered);
        }
    }

    /** At start: what was saved done before the server went down is reported now. */
    public static void replay() {
        load();
        for (Map.Entry<UUID, Long> entry : new ArrayList<>(saved.entrySet())) {
            report(entry.getKey(), entry.getValue());
        }
    }

    private static int retryTicks;

    /** Reports that the database didn't take (it was away) are sent again now and then. */
    @SubscribeEvent
    public static void onServerTick(cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent event) {
        if (event.phase != cpw.mods.fml.common.gameevent.TickEvent.Phase.END || ++retryTicks < 600) {
            return;
        }
        retryTicks = 0;
        if (!saved.isEmpty()) {
            for (Map.Entry<UUID, Long> entry : new ArrayList<>(saved.entrySet())) {
                report(entry.getKey(), entry.getValue());
            }
        }
    }

    @SubscribeEvent
    public static void onWorldSave(WorldEvent.Save event) {
        if (event.world.isRemote || unsaved.isEmpty()) {
            return;
        }
        int dimension = event.world.provider.dimensionId;
        List<Entry> now = new ArrayList<>();
        Iterator<Entry> it = unsaved.iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (entry.dimension == dimension) {
                now.add(entry);
                it.remove();
            }
        }
        if (now.isEmpty()) {
            return;
        }
        load();
        for (Entry entry : now) {
            saved.put(entry.op, entry.delivered);
        }
        write();
        for (Entry entry : now) {
            report(entry.op, entry.delivered);
        }
    }

    private static void report(UUID op, long delivered) {
        SupplyLink.applied(op, delivered, () -> {
            if (saved.remove(op) != null) {
                write();
            }
        });
    }

    private static File file() {
        File root = DimensionManager.getCurrentSaveRootDirectory();
        return new File(root != null ? root : new File("."), FILE);
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        File file = file();
        if (!file.isFile()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String[] parts = line.trim()
                    .split(" ");
                if (parts.length == 2) {
                    saved.put(UUID.fromString(parts[0]), Long.parseLong(parts[1]));
                }
            }
        } catch (IOException | RuntimeException e) {
            CointCore.LOG.error("[Supply] Could not read {}: {}", file, e.getMessage());
        }
    }

    private static void write() {
        File file = file();
        File tmp = new File(file.getPath() + ".tmp");
        StringBuilder text = new StringBuilder();
        for (Map.Entry<UUID, Long> entry : saved.entrySet()) {
            text.append(entry.getKey())
                .append(' ')
                .append(entry.getValue())
                .append('\n');
        }
        try {
            Files.write(
                tmp.toPath(),
                text.toString()
                    .getBytes(StandardCharsets.UTF_8));
            Files
                .move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            CointCore.LOG.error("[Supply] Could not write {}: {}", file, e.getMessage());
        }
    }

    /** A new server or world: what was kept is of the old one. */
    public static void reset() {
        unsaved.clear();
        saved.clear();
        loaded = false;
    }

    private static final class Entry {

        final UUID op;
        final long delivered;
        final int dimension;

        Entry(UUID op, long delivered, int dimension) {
            this.op = op;
            this.delivered = delivered;
            this.dimension = dimension;
        }
    }
}
