package coint.commands.tban;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import coint.CointCore;

/**
 * Persistent storage for temp-bans of offline players.
 * Data is stored in config/cointcore/tbans.json as a UUID -> TBanEntry map.
 */
public class TBanStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, TBanEntry>>() {}.getType();

    private static File storageFile;
    private static Map<String, TBanEntry> banMap = new HashMap<>();

    public static void init(File configDir) {
        File dir = new File(configDir, "cointcore");
        if (!dir.exists() && !dir.mkdirs()) {
            CointCore.LOG.error("Failed to create tban storage directory: {}", dir.getAbsolutePath());
        }
        storageFile = new File(dir, "tbans.json");
        load();
    }

    private static void load() {
        if (storageFile == null || !storageFile.exists()) {
            banMap = new HashMap<>();
            return;
        }
        try (FileReader reader = new FileReader(storageFile)) {
            Map<String, TBanEntry> loaded = GSON.fromJson(reader, MAP_TYPE);
            banMap = loaded != null ? loaded : new HashMap<>();
            // Purge expired entries on load
            purgeExpired();
        } catch (IOException e) {
            CointCore.LOG.error("Failed to load tban storage", e);
            banMap = new HashMap<>();
        }
    }

    private static void save() {
        if (storageFile == null) return;
        purgeExpired();
        try (FileWriter writer = new FileWriter(storageFile)) {
            GSON.toJson(banMap, writer);
        } catch (IOException e) {
            CointCore.LOG.error("Failed to save tban storage", e);
        }
    }

    private static void purgeExpired() {
        Iterator<Map.Entry<String, TBanEntry>> it = banMap.entrySet()
            .iterator();
        while (it.hasNext()) {
            TBanEntry entry = it.next()
                .getValue();
            if (System.currentTimeMillis() > entry.expiresAt) {
                it.remove();
            }
        }
    }

    /** Store a ban for the given UUID (used for offline players). */
    public static void store(UUID uuid, TBan tban) {
        TBanEntry entry = new TBanEntry();
        entry.banner = tban.banner;
        entry.reason = tban.reason;
        entry.expiresAt = tban.expiresAt;
        banMap.put(uuid.toString(), entry);
        save();
    }

    /** Remove the stored ban for a UUID. */
    public static void clearBan(UUID uuid) {
        banMap.remove(uuid.toString());
        save();
    }

    /** Returns a TBan for the UUID if one exists and is not expired, otherwise null. */
    public static TBan get(UUID uuid) {
        TBanEntry entry = banMap.get(uuid.toString());
        if (entry == null) return null;
        if (System.currentTimeMillis() > entry.expiresAt) {
            banMap.remove(uuid.toString());
            save();
            return null;
        }
        TBan tban = new TBan();
        tban.banner = entry.banner;
        tban.reason = entry.reason;
        tban.expiresAt = entry.expiresAt;
        return tban;
    }

    /** Returns true if the UUID has an active (non-expired) ban in storage. */
    public static boolean isBanned(UUID uuid) {
        return get(uuid) != null;
    }

    // Simple DTO for JSON serialization
    private static class TBanEntry {

        String banner;
        String reason;
        long expiresAt;
    }
}
