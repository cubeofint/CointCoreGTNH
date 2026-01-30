package coint.epochsync;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import serverutils.ServerUtilitiesPermissions;
import serverutils.ranks.PlayerRank;
import serverutils.ranks.Rank;
import serverutils.ranks.Rank.Entry;
import serverutils.ranks.Ranks;

public class SURanksManager {

    private static final Logger log = LogManager.getLogger(SURanksManager.class);

    public static SURanksManager INST;

    public static final String RANK_START = "bravebro";
    public static final String RANK_STONE = "stone";
    public static final String RANK_STEAM = "steam";
    public static final String RANK_LV = "lv";
    public static final String RANK_MV = "mv";
    public static final String RANK_HV = "hv";
    public static final String RANK_EV = "ev";
    public static final String RANK_IV = "iv";
    public static final String RANK_LUV = "luv";
    public static final String RANK_ZPM = "zpm";
    public static final String RANK_UV = "uv";
    public static final String RANK_UHV = "uhv";
    public static final String RANK_UEV = "uev";
    public static final String RANK_UIV = "uiv";
    public static final String RANK_UMV = "umv";
    public static final String RANK_UXV = "uxv";
    public static final String RANK_STARGATEOWNER = "stargateowner";

    private static final Set<String> EPOCH_RANKS = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                RANK_START,
                RANK_STONE,
                RANK_STEAM,
                RANK_LV,
                RANK_MV,
                RANK_HV,
                RANK_EV,
                RANK_IV,
                RANK_LUV,
                RANK_ZPM,
                RANK_UV,
                RANK_UHV,
                RANK_UEV,
                RANK_UIV,
                RANK_UMV,
                RANK_UXV,
                RANK_STARGATEOWNER)));

    public static final String API_URL = System.getenv("API_URL");

    public Ranks ranksInst;
    public static final Map<String, List<Entry>> epochPermissions = new HashMap<>();

    // TODO: Fill for all epochs
    static {
        epochPermissions.put(RANK_START, getEpochPerms(1, 25, 10, 1));
        epochPermissions.put(RANK_STONE, getEpochPerms(2, 35, 15, 2));
    }

    SURanksManager() {
        ranksInst = Ranks.INSTANCE;
    }

    public static void onSUInit() {
        INST = new SURanksManager();
    }

    private static boolean isEpoch(String rank) {
        return EPOCH_RANKS.contains(rank);
    }

    private static List<Entry> getEpochPerms(int priority, int chunks, int forcedChunks, int homes) {
        Entry p = new Entry(Rank.NODE_PRIORITY);
        p.value = String.valueOf(priority);
        Entry c = new Entry(ServerUtilitiesPermissions.CLAIMS_MAX_CHUNKS);
        c.value = String.valueOf(chunks);
        Entry f = new Entry(ServerUtilitiesPermissions.CHUNKLOADER_MAX_CHUNKS);
        f.value = String.valueOf(forcedChunks);
        Entry h = new Entry(ServerUtilitiesPermissions.HOMES_MAX);
        h.value = String.valueOf(homes);

        return Arrays.asList(p, c, f, h);
    }

    public static void apiPost(String endpoint, String jsonData) {
        if (API_URL == null || API_URL.isEmpty()) {
            log.warn("API_URL environment variable is not set, skipping API call to {}", endpoint);
            return;
        }

        String urlString = API_URL + endpoint;

        // async HTTP request using CompletableFuture
        CompletableFuture.runAsync(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setDoOutput(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonData.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = connection.getResponseCode();
                log.debug("API response - Status Code: {}", responseCode);
            } catch (IOException e) {
                log.error("HTTP error: {}", e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    public Map<String, Rank> getRanks() {
        return ranksInst.ranks;
    }

    public void setRank(UUID player, String rank) throws Exception {
        Rank r = ranksInst.getRank(player.toString());
        if (r == null) {
            throw new Exception("Player rank not found");
        }

        for (Rank parent : r.getParents()) {
            if (isEpoch(parent.getId())) {
                r.removeParent(parent);
            }
        }

        Rank p = ranksInst.getRank(rank);
        r.addParent(p);
        r.ranks.save();

        JsonObject data = new JsonObject();
        data.addProperty("player_id", player.toString());
        data.addProperty("rank", rank);

        apiPost("/api/coint-connector/roles/gtnh", data.toString());
    }

    public void syncRanks(boolean onlyRoles) {
        JsonObject data = new JsonObject();

        JsonArray ranks = new JsonArray();
        for (Rank r : ranksInst.ranks.values()) {
            JsonObject rank = new JsonObject();
            rank.addProperty("name", r.getId());
            rank.addProperty("power", r.getPriority());
            ranks.add(rank);
        }
        data.add("ranks", ranks);

        if (!onlyRoles) {
            JsonArray players = new JsonArray();
            for (PlayerRank p : ranksInst.playerRanks.values()) {
                JsonObject player = new JsonObject();
                player.addProperty("player_id", p.uuid.toString());

                boolean hasEpoch = false;
                for (Rank par : p.getActualParents()) {
                    if (isEpoch(par.getId())) {
                        player.addProperty("rank", par.getId());
                        hasEpoch = true;
                    }
                }
                if (!hasEpoch) continue;

                players.add(player);
            }
            data.add("players", players);
        }

        apiPost("/api/coint-connector/roles/sync", data.toString());
    }
}
