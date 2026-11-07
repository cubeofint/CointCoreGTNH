package coint.cluster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.zip.CRC32;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import appeng.api.AEApi;
import appeng.api.features.ILocatable;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.implementations.tiles.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.tile.networking.TileWireless;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import coint.CointConfig;
import coint.CointCore;
import coint.network.PacketRemoteMeState;
import coint.network.WorldTravelNetwork;
import coint.supply.SupplyGuiHandler;
import coint.supply.SupplyKey;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The remote ME terminal: a wireless terminal linked to an access point on another server of the network opens that
 * ME network here. Both sides live in this class.
 *
 * <p>
 * The viewer's server (where the player is) opens a session in the database and shows the player what the network
 * holds; takes and puts become operations in the database. The provider's server (where the access point is) claims
 * the session, writes what the network holds every few seconds while the session is looked at, and carries out the
 * operations. Items put in leave the player at once and come back if the network didn't take them; items taken come
 * when the provider has taken them out of the network.
 */
@EventBusSubscriber
public final class RemoteMe {

    /** States of the network a snapshot tells. */
    public static final byte STATE_OK = 0, STATE_MISSING = 1, STATE_UNPOWERED = 2, STATE_NO_WAP = 3;
    public static final int ACTION_EXTRACT = 0, ACTION_INSERT_ALL = 1, ACTION_INSERT_ONE = 2, ACTION_CRAFT = 3;
    /** Most items one craft order may ask for. */
    public static final int MAX_CRAFT = 1_000_000;
    /** How long the network may think over a craft order before it is given up. */
    private static final long CRAFT_PLAN_MS = 60_000;

    private static final long VIEW_POLL_MS = 500, PROVIDE_POLL_MS = 1000, SNAPSHOT_MS = 2500, PRUNE_MS = 3_600_000L;
    private static final int IDLE_SECONDS = 15, CLAIM_WINDOW_SECONDS = 30, EXPIRE_SECONDS = 30, NO_PROVIDER_SECONDS = 6;
    private static final int MAX_OUTSTANDING = 16;
    /** Snapshot bytes per packet, under the 32 KB a custom payload may carry. */
    private static final int CHUNK = 30_000;
    private static final BaseActionSource SOURCE = new BaseActionSource();

    // ---- viewer's server

    /** A session a player looks at. */
    public static final class View {

        final long session;
        final UUID player;
        int windowId = -1;
        long version;
        String providerNode;
        String title = "";
        String status = "";
        boolean statusSent;
        int outstanding;

        View(long session, UUID player) {
            this.session = session;
            this.player = player;
        }
    }

    private static final Map<UUID, View> views = new HashMap<>();
    /** The view whose window is being opened right now, for {@link SupplyGuiHandler}. */
    private static View opening;
    private static final Set<UUID> requested = new HashSet<>();
    /** Sessions closed or left whose operations are not all handed over yet, by player. */
    private static final Map<UUID, Set<Long>> settling = new HashMap<>();
    private static boolean viewPollInFlight, providePollInFlight;
    private static long nextViewPollAt, nextProvidePollAt, nextPruneAt;

    // ---- provider's server

    private static final Map<Long, Long> nextSnapshotAt = new HashMap<>();
    private static final Map<Long, Long> snapshotCrc = new java.util.concurrent.ConcurrentHashMap<>();

    private RemoteMe() {}

    /**
     * A wireless terminal is used: when it is linked to an access point this server doesn't have, the network is looked
     * for on the other servers and shown here. False when AE2 should handle it as always.
     */
    public static boolean openFromTerminal(EntityPlayer entity, ItemStack item) {
        if (!(entity instanceof EntityPlayerMP player) || !ClusterService.isRunning()
            || !CointConfig.cluster.remoteMeEnabled
            || item == null) {
            return false;
        }
        IWirelessTermHandler handler = AEApi.instance()
            .registries()
            .wireless()
            .getWirelessTerminalHandler(item);
        if (handler == null) {
            return false;
        }
        String key = handler.getEncryptionKey(item);
        if (key == null || key.isEmpty()) {
            return false;
        }
        long serial;
        try {
            serial = Long.parseLong(key);
        } catch (NumberFormatException e) {
            return false;
        }
        if (AEApi.instance()
            .registries()
            .locatable()
            .getLocatableBy(serial) != null) {
            return false;
        }
        if (ClusterHomes.isGuest(player)) {
            player.addChatMessage(new ChatComponentText("§cНа чужом сервере удалённая ME-сеть недоступна."));
            return true;
        }
        if (!handler.hasInfinityPower(item) && !handler.hasPower(player, 0.5, item)) {
            player.addChatMessage(new ChatComponentText("§cТерминал разряжен."));
            return true;
        }
        UUID uuid = player.getUniqueID();
        if (!requested.add(uuid)) {
            return true;
        }
        String id = uuid.toString(), name = player.getCommandSenderName(), node = ClusterService.nodeId();
        boolean queued = ClusterService.query(db -> RemoteMeStore.openSession(db, serial, id, name, node), session -> {
            requested.remove(uuid);
            if (!online(player)) {
                ClusterService.query(db -> {
                    RemoteMeStore.closeSession(db, session);
                    return null;
                }, ignored -> {}, null);
                return;
            }
            View old = views.remove(uuid);
            if (old != null) {
                settle(old);
            }
            View view = new View(session, uuid);
            view.status = "§7Поиск сети…";
            opening = view;
            try {
                player.openGui(CointCore.instance, SupplyGuiHandler.REMOTE_ME, player.worldObj, 0, 0, 0);
            } finally {
                opening = null;
            }
            if (player.openContainer instanceof ContainerRemoteMe container && container.view() == view) {
                view.windowId = container.windowId;
                views.put(uuid, view);
                sendStatus(player, view);
            } else {
                settle(view);
            }
        }, error -> {
            requested.remove(uuid);
            player.addChatMessage(new ChatComponentText("§cСеть серверов сейчас недоступна."));
        });
        if (!queued) {
            requested.remove(uuid);
            return false;
        }
        return true;
    }

    /** For {@link SupplyGuiHandler}: the view whose window is being opened, or null. */
    public static View opening() {
        return opening;
    }

    /** The remote window was closed. */
    static void closed(View view) {
        if (views.get(view.player) == view) {
            views.remove(view.player);
        }
        settle(view);
    }

    private static void settle(View view) {
        settling.computeIfAbsent(view.player, k -> new HashSet<>())
            .add(view.session);
        long session = view.session;
        ClusterService.query(db -> {
            RemoteMeStore.closeSession(db, session);
            return null;
        }, ignored -> {}, null);
    }

    /** The player asks for items of this kind from the remote network. */
    public static void extract(EntityPlayerMP player, int windowId, ItemStack kind, int amount) {
        View view = viewOf(player, windowId);
        if (view == null || kind == null || view.providerNode == null || view.outstanding >= MAX_OUTSTANDING) {
            return;
        }
        String item = SupplyKey.encodeItem(kind);
        if (item == null) {
            return;
        }
        int count = Math.max(1, Math.min(amount, kind.getMaxStackSize()));
        view.outstanding++;
        long session = view.session;
        ClusterService.query(
            db -> RemoteMeStore.queueOp(db, session, RemoteMeStore.EXTRACT, item, count),
            id -> {},
            error -> view.outstanding--);
    }

    /** The player orders items of this kind to be crafted by the remote network. */
    public static void craft(EntityPlayerMP player, int windowId, ItemStack kind, int amount) {
        View view = viewOf(player, windowId);
        if (view == null || kind == null || view.providerNode == null || view.outstanding >= MAX_OUTSTANDING) {
            return;
        }
        String item = SupplyKey.encodeItem(kind);
        if (item == null) {
            return;
        }
        int count = Math.max(1, Math.min(amount, MAX_CRAFT));
        view.outstanding++;
        long session = view.session;
        ClusterService.query(
            db -> RemoteMeStore.queueOp(db, session, RemoteMeStore.CRAFT, item, count),
            id -> {},
            error -> view.outstanding--);
    }

    /** The player puts what he holds on the cursor (all or one) into the remote network. */
    public static void insertCursor(EntityPlayerMP player, int windowId, boolean one) {
        View view = viewOf(player, windowId);
        ItemStack held = player.inventory.getItemStack();
        if (view == null || held == null) {
            return;
        }
        int count = one ? 1 : held.stackSize;
        ItemStack part = held.copy();
        part.stackSize = count;
        if (insert(player, view, part)) {
            held.stackSize -= count;
            player.inventory.setItemStack(held.stackSize > 0 ? held : null);
            player.updateHeldItem();
        }
    }

    /** Shift-click in the remote window: the whole stack goes to the remote network. True when it was taken. */
    static boolean insertStack(EntityPlayerMP player, View view, ItemStack stack) {
        return views.get(player.getUniqueID()) == view && insert(player, view, stack.copy());
    }

    private static boolean insert(EntityPlayerMP player, View view, ItemStack stack) {
        if (view.providerNode == null || view.outstanding >= MAX_OUTSTANDING || stack.stackSize <= 0) {
            return false;
        }
        String item = SupplyKey.encodeItem(stack);
        if (item == null) {
            return false;
        }
        long session = view.session;
        int count = stack.stackSize;
        view.outstanding++;
        boolean queued = ClusterService
            .query(db -> RemoteMeStore.queueOp(db, session, RemoteMeStore.INSERT, item, count), id -> {}, error -> {
                view.outstanding--;
                give(player, stack, count);
            });
        if (!queued) {
            view.outstanding--;
        }
        return queued;
    }

    private static View viewOf(EntityPlayerMP player, int windowId) {
        View view = views.get(player.getUniqueID());
        return view != null && view.windowId == windowId
            && player.openContainer instanceof ContainerRemoteMe
            && ((ContainerRemoteMe) player.openContainer).view() == view ? view : null;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClusterService.isRunning() || !CointConfig.cluster.remoteMeEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!viewPollInFlight && now >= nextViewPollAt && (!views.isEmpty() || !settling.isEmpty())) {
            nextViewPollAt = now + VIEW_POLL_MS;
            pollViews();
        }
        tickCrafts(now);
        if (!providePollInFlight && now >= nextProvidePollAt) {
            nextProvidePollAt = now + PROVIDE_POLL_MS;
            pollProvider(now);
        }
        if (now >= nextPruneAt) {
            nextPruneAt = now + PRUNE_MS;
            ClusterService.query(db -> {
                RemoteMeStore.prune(db);
                return null;
            }, ignored -> {}, null);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ClusterService.isRunning() || !CointConfig.cluster.remoteMeEnabled) {
            return;
        }
        UUID uuid = event.player.getUniqueID();
        String id = uuid.toString(), node = ClusterService.nodeId();
        ClusterService.query(db -> RemoteMeStore.sessionsWithResults(db, id, node), sessions -> {
            if (!sessions.isEmpty()) {
                settling.computeIfAbsent(uuid, k -> new HashSet<>())
                    .addAll(sessions);
            }
        }, null);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.player.getUniqueID();
        View view = views.remove(uuid);
        if (view != null) {
            settle(view);
        }
        // What is still owed is found again at the next login.
        settling.remove(uuid);
        requested.remove(uuid);
    }

    // ---- viewer: poll

    private static final class ViewPoll {

        final Map<Long, RemoteMeStore.SessionView> views = new HashMap<>();
        List<RemoteMeStore.Op> done = new ArrayList<>();
        final Map<UUID, Integer> unsettled = new HashMap<>();
    }

    private static void pollViews() {
        Map<Long, Long> known = new HashMap<>();
        Map<Long, UUID> owners = new HashMap<>();
        for (View view : views.values()) {
            known.put(view.session, view.version);
            owners.put(view.session, view.player);
        }
        Map<UUID, List<Long>> settlingCopy = new HashMap<>();
        for (Map.Entry<UUID, Set<Long>> e : settling.entrySet()) {
            settlingCopy.put(e.getKey(), new ArrayList<>(e.getValue()));
            for (long session : e.getValue()) {
                owners.put(session, e.getKey());
            }
        }
        List<Long> all = new ArrayList<>(owners.keySet());
        viewPollInFlight = true;
        boolean queued = ClusterService.query(db -> {
            ViewPoll poll = new ViewPoll();
            for (Map.Entry<Long, Long> e : known.entrySet()) {
                RemoteMeStore.SessionView v = RemoteMeStore.view(db, e.getKey(), e.getValue());
                if (v != null) {
                    poll.views.put(e.getKey(), v);
                }
            }
            RemoteMeStore.expire(db, all, EXPIRE_SECONDS);
            poll.done = RemoteMeStore.consumeDone(db, all);
            for (Map.Entry<UUID, List<Long>> e : settlingCopy.entrySet()) {
                poll.unsettled.put(e.getKey(), RemoteMeStore.unsettled(db, e.getValue()));
            }
            return poll;
        }, poll -> {
            viewPollInFlight = false;
            applyViewPoll(poll, owners, settlingCopy);
        }, error -> viewPollInFlight = false);
        if (!queued) {
            viewPollInFlight = false;
        }
    }

    private static void applyViewPoll(ViewPoll poll, Map<Long, UUID> owners, Map<UUID, List<Long>> polledSettling) {
        for (RemoteMeStore.Op op : poll.done) {
            UUID owner = owners.get(op.session);
            EntityPlayerMP player = owner == null ? null : player(owner);
            if (player == null) {
                ClusterService.query(db -> {
                    RemoteMeStore.unconsume(db, op.id);
                    return null;
                }, ignored -> {}, null);
                continue;
            }
            handOver(player, op);
            View view = views.get(owner);
            if (view != null && view.session == op.session && view.outstanding > 0) {
                view.outstanding--;
            }
        }
        for (Map.Entry<UUID, Integer> e : poll.unsettled.entrySet()) {
            if (e.getValue() == 0) {
                Set<Long> sessions = settling.get(e.getKey());
                if (sessions != null) {
                    sessions.removeAll(polledSettling.get(e.getKey()));
                    if (sessions.isEmpty()) {
                        settling.remove(e.getKey());
                    }
                }
            }
        }
        for (View view : views.values()) {
            RemoteMeStore.SessionView v = poll.views.get(view.session);
            EntityPlayerMP player = player(view.player);
            if (v == null || player == null) {
                continue;
            }
            view.providerNode = v.providerNode;
            String status;
            if (v.providerNode == null) {
                status = v.ageSeconds >= NO_PROVIDER_SECONDS
                    ? "§cСеть не найдена: база с точкой доступа не прогружена (нужен чанклоад) или сервер выключен"
                    : "§7Поиск сети…";
            } else {
                view.title = v.providerName;
                status = "";
            }
            if (!status.equals(view.status)) {
                view.status = status;
                view.statusSent = false;
            }
            if (v.snapshot != null) {
                view.version = v.snapshotVersion;
                sendSnapshot(player, view, v.snapshot);
            } else if (!view.statusSent) {
                sendStatus(player, view);
            }
        }
    }

    private static void sendStatus(EntityPlayerMP player, View view) {
        view.statusSent = true;
        WorldTravelNetwork.CHANNEL.sendTo(
            new PacketRemoteMeState(view.windowId, view.title, view.status, view.version, 0, 0, new byte[0]),
            player);
    }

    private static void sendSnapshot(EntityPlayerMP player, View view, byte[] snapshot) {
        view.statusSent = true;
        int parts = Math.max(1, (snapshot.length + CHUNK - 1) / CHUNK);
        for (int i = 0; i < parts; i++) {
            int from = i * CHUNK, to = Math.min(snapshot.length, from + CHUNK);
            byte[] part = java.util.Arrays.copyOfRange(snapshot, from, to);
            WorldTravelNetwork.CHANNEL.sendTo(
                new PacketRemoteMeState(view.windowId, view.title, view.status, view.version, i, parts, part),
                player);
        }
    }

    private static void handOver(EntityPlayerMP player, RemoteMeStore.Op op) {
        ItemStack kind = SupplyKey.decodeItem(op.item);
        if (kind == null) {
            CointCore.LOG.warn("[RemoteMe] Unknown item in operation {} for {}", op.id, player.getCommandSenderName());
            return;
        }
        if (RemoteMeStore.CRAFT.equals(op.kind)) {
            String what = op.amount + " × " + kind.getDisplayName();
            String text;
            if (op.result == RemoteMeStore.CRAFT_STARTED) {
                text = "§aЗаказ запущен в удалённой сети: " + what;
            } else if (op.result == RemoteMeStore.CRAFT_NO_CPU) {
                text = "§cНе удалось заказать " + what + ": нет свободного процессора крафта";
            } else if (op.result == RemoteMeStore.CRAFT_NO_NETWORK) {
                text = "§cНе удалось заказать " + what + ": сеть недоступна";
            } else {
                text = "§cНе удалось заказать " + what + ": не хватает ресурсов или сеть не ответила";
            }
            player.addChatMessage(new ChatComponentText(text));
            return;
        }
        long count = RemoteMeStore.EXTRACT.equals(op.kind) ? op.result : op.amount - op.result;
        if (count > 0) {
            give(player, kind, count);
            if (RemoteMeStore.INSERT.equals(op.kind)) {
                player.addChatMessage(
                    new ChatComponentText(
                        "§eСеть не приняла " + count + " × " + kind.getDisplayName() + ", они возвращены."));
            }
        }
    }

    private static void give(EntityPlayerMP player, ItemStack kind, long count) {
        int max = Math.max(1, kind.getMaxStackSize());
        while (count > 0) {
            ItemStack stack = kind.copy();
            stack.stackSize = (int) Math.min(count, max);
            count -= stack.stackSize;
            player.inventory.addItemStackToInventory(stack);
            if (stack.stackSize > 0) {
                player.dropPlayerItemWithRandomChoice(stack, false);
            }
        }
        player.inventoryContainer.detectAndSendChanges();
    }

    // ---- provider: poll

    private static final class ProvidePoll {

        List<long[]> unclaimed, served;
        List<RemoteMeStore.Op> ops;
    }

    private static void pollProvider(long now) {
        String node = ClusterService.nodeId();
        providePollInFlight = true;
        boolean queued = ClusterService.query(db -> {
            ProvidePoll poll = new ProvidePoll();
            poll.unclaimed = RemoteMeStore.unclaimed(db, CLAIM_WINDOW_SECONDS);
            poll.served = RemoteMeStore.served(db, node, IDLE_SECONDS);
            List<Long> ids = new ArrayList<>();
            for (long[] s : poll.served) {
                ids.add(s[0]);
            }
            poll.ops = RemoteMeStore.takeOps(db, ids);
            return poll;
        }, poll -> {
            providePollInFlight = false;
            applyProvidePoll(poll, node, now);
        }, error -> providePollInFlight = false);
        if (!queued) {
            providePollInFlight = false;
        }
    }

    private static void applyProvidePoll(ProvidePoll poll, String node, long now) {
        for (long[] s : poll.unclaimed) {
            long id = s[0];
            if (resolve(s[1]) != null) {
                ClusterService.query(db -> RemoteMeStore.claim(db, id, node), claimed -> {
                    if (claimed) {
                        CointCore.LOG.info("[RemoteMe] Serving remote ME session {}", id);
                    }
                }, null);
            }
        }
        Map<Long, Long> serials = new HashMap<>();
        Set<Long> live = new HashSet<>();
        for (long[] s : poll.served) {
            serials.put(s[0], s[1]);
            if (s[2] != 0) {
                live.add(s[0]);
            }
        }
        Set<Long> changed = new HashSet<>();
        List<long[]> results = new ArrayList<>();
        for (RemoteMeStore.Op op : poll.ops) {
            Long serial = serials.get(op.session);
            changed.add(op.session);
            if (RemoteMeStore.CRAFT.equals(op.kind)) {
                long result = serial == null ? RemoteMeStore.CRAFT_NO_NETWORK : startCraft(serial, op, now);
                if (result >= 0) {
                    results.add(new long[] { op.id, result });
                }
                continue;
            }
            results.add(new long[] { op.id, serial == null ? 0 : apply(serial, op) });
        }
        if (!results.isEmpty()) {
            ClusterService.query(db -> {
                for (long[] r : results) {
                    RemoteMeStore.done(db, r[0], r[1]);
                }
                return null;
            }, ignored -> {}, null);
        }
        Map<Long, NBTTagCompound> bySerial = new HashMap<>();
        for (long session : live) {
            Long due = nextSnapshotAt.get(session);
            if (!changed.contains(session) && due != null && now < due) {
                continue;
            }
            nextSnapshotAt.put(session, now + SNAPSHOT_MS);
            NBTTagCompound snapshot = bySerial.computeIfAbsent(serials.get(session), RemoteMe::snapshot);
            writeSnapshot(session, snapshot);
        }
        nextSnapshotAt.keySet()
            .retainAll(live);
        snapshotCrc.keySet()
            .retainAll(live);
    }

    // ---- provider: craft orders

    private static final class Craft {

        final long opId, serial, deadline;
        final Future<ICraftingJob> job;

        Craft(long opId, long serial, long deadline, Future<ICraftingJob> job) {
            this.opId = opId;
            this.serial = serial;
            this.deadline = deadline;
            this.job = job;
        }
    }

    /** Craft orders the network is still thinking over. */
    private static final List<Craft> crafts = new ArrayList<>();

    /** Starts planning a craft order: -1 while the network thinks it over, else its result. */
    private static long startCraft(long serial, RemoteMeStore.Op op, long now) {
        IGridNode node = resolveNode(serial);
        IGrid grid = node == null ? null : node.getGrid();
        if (state(grid) != STATE_OK) {
            return RemoteMeStore.CRAFT_NO_NETWORK;
        }
        ItemStack kind = SupplyKey.decodeItem(op.item);
        if (kind == null || op.amount <= 0) {
            return RemoteMeStore.CRAFT_FAILED;
        }
        IAEItemStack what = AEItemStack.create(kind);
        what.setStackSize(Math.min(op.amount, MAX_CRAFT));
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        Future<ICraftingJob> job = crafting.beginCraftingJob(node.getWorld(), grid, SOURCE, (IAEStack<?>) what, null);
        if (job == null) {
            return RemoteMeStore.CRAFT_FAILED;
        }
        crafts.add(new Craft(op.id, serial, now + CRAFT_PLAN_MS, job));
        return -1;
    }

    /** Submits the craft orders whose plans are ready. */
    private static void tickCrafts(long now) {
        if (crafts.isEmpty()) {
            return;
        }
        List<long[]> results = new ArrayList<>();
        for (Iterator<Craft> it = crafts.iterator(); it.hasNext();) {
            Craft craft = it.next();
            long result;
            if (craft.job.isDone()) {
                result = submit(craft);
            } else if (now > craft.deadline) {
                craft.job.cancel(true);
                result = RemoteMeStore.CRAFT_FAILED;
            } else {
                continue;
            }
            it.remove();
            results.add(new long[] { craft.opId, result });
        }
        if (!results.isEmpty()) {
            ClusterService.query(db -> {
                for (long[] r : results) {
                    RemoteMeStore.done(db, r[0], r[1]);
                }
                return null;
            }, ignored -> {}, null);
        }
    }

    private static long submit(Craft craft) {
        ICraftingJob job;
        try {
            job = craft.job.get();
        } catch (Exception e) {
            return RemoteMeStore.CRAFT_FAILED;
        }
        if (job == null || job.isSimulation()) {
            return RemoteMeStore.CRAFT_FAILED;
        }
        IGrid grid = resolve(craft.serial);
        if (state(grid) != STATE_OK) {
            return RemoteMeStore.CRAFT_NO_NETWORK;
        }
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        ICraftingLink link = crafting.submitJob(job, null, null, false, SOURCE);
        return link == null ? RemoteMeStore.CRAFT_NO_CPU : RemoteMeStore.CRAFT_STARTED;
    }

    private static void writeSnapshot(long session, NBTTagCompound snapshot) {
        ClusterService.query(db -> {
            byte[] bytes;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                CompressedStreamTools.writeCompressed(snapshot, out);
                bytes = out.toByteArray();
            } catch (IOException e) {
                return null;
            }
            CRC32 crc = new CRC32();
            crc.update(bytes);
            long value = crc.getValue();
            Long last = snapshotCrc.get(session);
            if (last == null || last != value) {
                RemoteMeStore.writeSnapshot(db, session, bytes);
            }
            return value;
        }, value -> {
            if (value != null) {
                snapshotCrc.put(session, value);
            }
        }, null);
    }

    /** The grid behind an access point serial, if this server has it. */
    private static IGrid resolve(long serial) {
        IGridNode node = resolveNode(serial);
        return node == null ? null : node.getGrid();
    }

    private static IGridNode resolveNode(long serial) {
        ILocatable locatable = AEApi.instance()
            .registries()
            .locatable()
            .getLocatableBy(serial);
        if (!(locatable instanceof IGridHost host)) {
            return null;
        }
        return host.getGridNode(ForgeDirection.UNKNOWN);
    }

    private static byte state(IGrid grid) {
        if (grid == null) {
            return STATE_MISSING;
        }
        IEnergyGrid energy = grid.getCache(IEnergyGrid.class);
        if (energy == null || !energy.isNetworkPowered()) {
            return STATE_UNPOWERED;
        }
        for (IGridNode n : grid.getMachines(TileWireless.class)) {
            if (n.getMachine() instanceof IWirelessAccessPoint wap && wap.isActive()) {
                return STATE_OK;
            }
        }
        return STATE_NO_WAP;
    }

    /** Carries out a take or a put: how many items came out or went in. */
    private static long apply(long serial, RemoteMeStore.Op op) {
        IGrid grid = resolve(serial);
        if (state(grid) != STATE_OK) {
            return 0;
        }
        ItemStack kind = SupplyKey.decodeItem(op.item);
        if (kind == null || op.amount <= 0) {
            return 0;
        }
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        IEnergyGrid energy = grid.getCache(IEnergyGrid.class);
        IMEMonitor<IAEItemStack> inventory = storage.getItemInventory();
        IAEItemStack request = AEItemStack.create(kind);
        request.setStackSize(op.amount);
        if (RemoteMeStore.EXTRACT.equals(op.kind)) {
            IAEItemStack taken = Platform.poweredExtraction(energy, inventory, request, SOURCE);
            return taken == null ? 0 : taken.getStackSize();
        }
        IAEItemStack left = Platform.poweredInsert(energy, inventory, request, SOURCE);
        return op.amount - (left == null ? 0 : left.getStackSize());
    }

    /** What the network holds: state, then items by registry name, damage, NBT and count. */
    private static NBTTagCompound snapshot(long serial) {
        NBTTagCompound root = new NBTTagCompound();
        IGrid grid = resolve(serial);
        byte state = state(grid);
        root.setByte("s", state);
        NBTTagList items = new NBTTagList();
        if (grid != null && state != STATE_MISSING) {
            IStorageGrid storage = grid.getCache(IStorageGrid.class);
            for (IAEItemStack stack : storage.getItemInventory()
                .getStorageList()) {
                if (stack == null || stack.getStackSize() <= 0 && !stack.isCraftable()) {
                    continue;
                }
                ItemStack is = stack.getItemStack();
                String name = is == null || is.getItem() == null ? null
                    : Item.itemRegistry.getNameForObject(is.getItem());
                if (name == null) {
                    continue;
                }
                NBTTagCompound e = new NBTTagCompound();
                e.setString("i", name);
                e.setShort("d", (short) is.getItemDamage());
                if (is.hasTagCompound()) {
                    e.setTag(
                        "t",
                        is.getTagCompound()
                            .copy());
                }
                e.setLong("n", Math.max(0, stack.getStackSize()));
                if (stack.isCraftable()) {
                    e.setBoolean("c", true);
                }
                items.appendTag(e);
            }
        }
        root.setTag("l", items);
        return root;
    }

    private static boolean online(EntityPlayerMP player) {
        return MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList.contains(player);
    }

    private static EntityPlayerMP player(UUID uuid) {
        for (Object o : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP p = (EntityPlayerMP) o;
            if (p.getUniqueID()
                .equals(uuid)) {
                return p;
            }
        }
        return null;
    }
}
