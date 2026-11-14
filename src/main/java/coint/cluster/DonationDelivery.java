package coint.cluster;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import coint.CointConfig;
import coint.CointCore;
import coint.commands.kit.KitManager;
import coint.commands.temprank.TempRankManager;
import coint.player.PlayerLookup;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.util.NBTUtils;

/** Append-only purchase credits. Receipts travel with player NBT; only the home node delivers. */
public final class DonationDelivery {

    private static final Gson JSON = new Gson();
    private static final Path FILE = Paths.get("cointcore", "donation-kit-grants.json");
    private static List<Grant> grants;
    private static List<Grant> replica = new ArrayList<>();
    private static final Set<String> warned = new HashSet<>();
    private static boolean querying;
    private static long next, generation;

    public static final class Grant {

        public String id, name, uuid, kit;
        public int amount;
    }

    private DonationDelivery() {}

    public static boolean enabled() {
        return CointConfig.cluster.enabled && !CointConfig.cluster.accessAuthorityNodeId.trim()
            .isEmpty();
    }

    static void reset() {
        generation++;
        grants = null;
        replica = new ArrayList<>();
        warned.clear();
        querying = false;
        next = 0;
    }

    private static void validate(Grant g) {
        if (g == null || g.id == null
            || !g.id.matches("[A-Za-z0-9_.:-]{1,160}")
            || g.name == null
            || !g.name.matches("[A-Za-z0-9_]{1,16}")
            || g.kit == null
            || !g.kit.matches("[a-z0-9_-]{1,64}")
            || g.amount <= 0) throw new IllegalArgumentException("Invalid kit delivery record");
        if (g.uuid != null) UUID.fromString(g.uuid);
    }

    private static void load() {
        if (grants != null) return;
        List<Grant> loaded = new ArrayList<>();
        try {
            if (Files.exists(FILE)) loaded = JSON.fromJson(
                new String(Files.readAllBytes(FILE), StandardCharsets.UTF_8),
                new TypeToken<List<Grant>>() {}.getType());
            if (loaded == null) throw new IllegalArgumentException("Empty donation ledger");
            Set<String> ids = new HashSet<>();
            for (Grant grant : loaded) {
                validate(grant);
                if (!ids.add(grant.id)) throw new IllegalArgumentException("Duplicate grant ID");
            }
            grants = loaded;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read donation ledger; purchases blocked", e);
        }
    }

    private static void save(List<Grant> values) {
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            byte[] data = JSON.toJson(values)
                .getBytes(StandardCharsets.UTF_8);
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(tmp.toFile())) {
                output.write(data);
                output.getFD()
                    .sync();
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot save kit purchase", e);
        }
    }

    public static String add(String name, String kit, int amount, String purchaseId) {
        TempRankManager.requireAuthority();
        load();
        Grant g = new Grant();
        g.id = purchaseId == null ? UUID.randomUUID()
            .toString() : purchaseId + ":" + kit;
        g.name = name;
        g.kit = kit;
        g.amount = amount;
        ForgePlayer p = PlayerLookup.exact(name);
        g.uuid = p == null ? null
            : p.getId()
                .toString();
        validate(g);
        for (Grant old : grants) if (old.id.equals(g.id)) {
            if (!old.name.equalsIgnoreCase(name) || !old.kit.equals(kit) || old.amount != amount)
                throw new IllegalArgumentException("Purchase ID reused with different contents");
            return old.id;
        }
        List<Grant> updated = new ArrayList<>(grants);
        updated.add(g);
        save(updated);
        grants = updated;
        return g.id;
    }

    static List<Grant> snapshot() {
        load();
        return new ArrayList<>(grants);
    }

    static void receive(List<Grant> values) {
        if (values == null) {
            replica = new ArrayList<>();
            return;
        }
        for (Grant g : values) validate(g);
        replica = new ArrayList<>(values);
    }

    static void tick() {
        if (!enabled() || System.currentTimeMillis() < next) return;
        next = System.currentTimeMillis() + 5000;
        if (!TempRankManager.replica()) resolvePending();
        List<Grant> available = TempRankManager.replica() ? replica : snapshot();
        for (Object value : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) value;
            if (!ClusterService.nodeId()
                .equals(ClusterHomes.homeOf(player.getUniqueID()))) continue;
            NBTTagCompound persisted = NBTUtils.getPersistedData(player, true);
            NBTTagCompound receipts = persisted.getCompoundTag("cointcore_donation_receipts");
            boolean changed = false;
            for (Grant grant : available) {
                if (!player.getUniqueID()
                    .toString()
                    .equals(grant.uuid) || receipts.getBoolean(grant.id)) continue;
                if (KitManager.getKit(MinecraftServer.getServer(), grant.kit) == null) {
                    if (warned.add(grant.kit)) CointCore.LOG
                        .warn("[Donation] Kit {} is missing on this home node; credits remain pending", grant.kit);
                    continue;
                }
                int total = Math.addExact(KitManager.getKitBonusRemaining(player, grant.kit), grant.amount);
                KitManager.setKitBonusRemaining(player, grant.kit, total);
                receipts.setBoolean(grant.id, true);
                persisted.setTag("cointcore_donation_receipts", receipts);
                changed = true;
                CointCore.LOG.info(
                    "[Donation] Applied kit credit {}: {} x{} for {}",
                    grant.id,
                    grant.kit,
                    grant.amount,
                    player.getCommandSenderName());
            }
            // Credit and receipt belong to the same player save, including future migration exports.
            if (changed) player.worldObj.getSaveHandler()
                .getSaveHandler()
                .writePlayerData(player);
        }
    }

    private static void resolvePending() {
        if (querying) return;
        load();
        Set<String> names = new HashSet<>();
        for (Grant g : grants) if (g.uuid == null) names.add(g.name);
        TempRankManager.get()
            .getAllEntries()
            .forEach(e -> { if (e.pendingIdentity) names.add(e.playerName); });
        if (names.isEmpty()) return;
        querying = true;
        long session = generation;
        boolean queued = ClusterService.query(db -> {
            Map<String, UUID> identities = new HashMap<>();
            try (java.sql.PreparedStatement statement = db.connection()
                .prepareStatement("SELECT DISTINCT uuid FROM cluster_seen WHERE name = ? LIMIT 2")) {
                for (String name : names) {
                    statement.setString(1, name);
                    try (java.sql.ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) continue;
                        UUID id = UUID.fromString(rows.getString(1));
                        if (rows.next()) throw new IllegalStateException("Ambiguous purchase identity: " + name);
                        identities.put(name, id);
                    }
                }
            }
            return identities;
        }, identities -> {
            if (session != generation) return;
            querying = false;
            for (Map.Entry<String, UUID> identity : identities.entrySet()) {
                TempRankManager.get()
                    .bindIdentity(identity.getKey(), identity.getValue());
                for (Grant g : grants)
                    if (g.uuid == null && g.name.equalsIgnoreCase(identity.getKey())) g.uuid = identity.getValue()
                        .toString();
            }
            if (!identities.isEmpty()) save(grants);
        }, error -> { if (session == generation) querying = false; });
        if (!queued) querying = false;
    }
}
