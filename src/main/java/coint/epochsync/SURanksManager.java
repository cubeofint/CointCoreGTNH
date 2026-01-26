package coint.epochsync;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import serverutils.ServerUtilitiesPermissions;
import serverutils.ranks.PlayerRank;
import serverutils.ranks.Rank;
import serverutils.ranks.Rank.Entry;
import serverutils.ranks.Ranks;

public class SURanksManager {
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

    public static final String API_URL = System.getenv("API_URL");

    public Ranks ranksInst;
    public static final Map<String, List<Entry>> epochPermissions = new HashMap<>();

    //    TODO: Fill for all epochs
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
        return switch (rank) {
            case "bravebro", "stone", "steam", "lv", "mv", "hv", "ev", "iv", "luv", "zpm", "uv", "uhv", "uev", "uiv",
                 "umv", "uxv", "stargateowner" -> true;
            default -> false;
        };
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

        return List.of(p, c, f, h);
    }

    public static void apiPost(String endpoint, String jsonData) {
        String url = API_URL + endpoint;

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonData))
            .build();

//        asnyc
        CompletableFuture<HttpResponse<String>> futureResponse = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        futureResponse.thenAccept(response -> {
            System.out.println("Status Code: " + response.statusCode());
            System.out.println("Response Body: " + response.body());
        }).exceptionally(e -> {
            System.err.println("http error: " + e.getMessage());
            return null;
        });

//        sync
/*
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Status Code: " + response.statusCode());//
        } catch (IOException | InterruptedException e) {
            System.err.println("http error: " + e.toString());
        }
*/
    }

    public Map<String, Rank> getRanks() {
        // Map<String, Rank> ranks = ranksInst.ranks;
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

        JsonObject jo = new JsonObject();
        jo.addProperty("player_id", player.toString());
        jo.addProperty("rank", rank);

        apiPost("/api/coint-connector/roles/gtnh", jo.getAsString());
    }

    public void syncRanks(boolean onlyRoles) {
        JsonObject data = new JsonObject();

        JsonArray roles = new JsonArray();
        for (Rank r : ranksInst.ranks.values()) {
            JsonObject role = new JsonObject();
            role.addProperty("name", r.getId());
            role.addProperty("power", r.getPriority());
            roles.add(role);
        }
        data.add("roles", roles);

        if (!onlyRoles) {
            JsonArray players = new JsonArray();
            for (PlayerRank p : ranksInst.playerRanks.values()) {
                JsonObject user = new JsonObject();
                user.addProperty("player_id", p.uuid.toString());

                boolean hasEpoch = false;
                for (Rank par : p.getActualParents()) {
                    if (isEpoch(par.getId())) {
                        user.addProperty("role", par.getId());
                        hasEpoch = true;
                    }
                }
                if (!hasEpoch) continue;

                players.add(user);
            }
            data.add("players", players);
        }

        apiPost("/api/coint-connector/roles/sync", data.getAsString());
    }
}
