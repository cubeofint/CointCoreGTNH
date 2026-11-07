package coint.cluster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
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
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.play.server.S29PacketSoundEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import appeng.api.AEApi;
import appeng.api.config.CraftingAllow;
import appeng.api.features.ILocatable;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.implementations.tiles.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CraftingItemList;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.container.AEBaseContainer;
import appeng.container.ContainerOpenContext;
import appeng.container.PrimaryGui;
import appeng.container.implementations.CraftingCPUStatus;
import appeng.container.implementations.CraftingCpuEntry;
import appeng.core.sync.GuiBridge;
import appeng.hooks.CraftingNotificationManager;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.tile.networking.TileWireless;
import appeng.util.Platform;
import appeng.util.ScheduledReason;
import appeng.util.item.AEItemStack;
import coint.CointConfig;
import coint.CointCore;
import coint.network.PacketRemoteMeCraftPlan;
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
    public static final int ACTION_EXTRACT = 0, ACTION_INSERT_ALL = 1, ACTION_INSERT_ONE = 2, ACTION_CRAFT = 3,
        ACTION_PLAN = 4, ACTION_START = 5;
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
        /** For AE2's own window: the network's items; null for ours. */
        RemoteItemMonitor monitor;
        /** For AE2's own window: the last state told in chat. */
        int toldState = -1;
        String toldStatus = "";
        /** For AE2's own window: the terminal it is opened with, for its other windows (amount, plan). */
        IWirelessTermHandler handler;
        ItemStack terminal;
        int slot = -1;

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
            int slot = CointConfig.cluster.remoteMeNativeWindow ? slotOf(player, item) : -1;
            if (slot >= 0 && openNative(player, view, handler, player.inventory.mainInventory[slot], slot)) {
                views.put(uuid, view);
                return;
            }
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

    /** The terminal's slot in the main inventory, or -1 (a bauble slot: our own window is used then). */
    private static int slotOf(EntityPlayerMP player, ItemStack item) {
        ItemStack[] main = player.inventory.mainInventory;
        for (int i = 0; i < main.length; i++) {
            if (main[i] == item) {
                return i;
            }
        }
        for (int i = 0; i < main.length; i++) {
            if (main[i] != null && ItemStack.areItemStacksEqual(main[i], item)) {
                return i;
            }
        }
        return -1;
    }

    private static final class NativeOpening {

        final EntityPlayer player;
        final View view;
        final IWirelessTermHandler handler;
        final ItemStack terminal;
        final int slot;

        NativeOpening(EntityPlayer player, View view, IWirelessTermHandler handler, ItemStack terminal, int slot) {
            this.player = player;
            this.view = view;
            this.handler = handler;
            this.terminal = terminal;
            this.slot = slot;
        }
    }

    /** AE2's own window being opened right now, for the mixin on its GUI handler. */
    private static NativeOpening nativeOpening;

    /** Opens AE2's own terminal window onto the remote network; false when it didn't open. */
    private static boolean openNative(EntityPlayerMP player, View view, IWirelessTermHandler handler,
        ItemStack terminal, int slot) {
        view.monitor = new RemoteItemMonitor(stack -> isOpen(player, view) && insert(player, view, stack));
        view.handler = handler;
        view.terminal = terminal;
        view.slot = slot;
        nativeOpening = new NativeOpening(player, view, handler, terminal, slot);
        try {
            Platform.openGUI(player, null, null, GuiBridge.GUI_ME, slot);
        } catch (RuntimeException e) {
            CointCore.LOG.error("[RemoteMe] AE2 terminal window failed to open", e);
        } finally {
            nativeOpening = null;
        }
        if (player.openContainer instanceof ContainerRemoteMeNative container && container.remoteView() == view) {
            view.windowId = container.windowId;
            return true;
        }
        view.monitor = null;
        return false;
    }

    /** For the mixin on AE2's GUI handler: no window may open (one AE2 would open on a local network). */
    public static final Object BLOCK = new Object();

    /**
     * For the mixin on AE2's GUI handler: the container for an AE2 window opened onto a remote network (the terminal,
     * the craft amount and the craft plan), {@link #BLOCK} for AE2's other windows opened from one, null otherwise.
     */
    public static Object nativeContainer(EntityPlayer player, int ordinal) {
        View view;
        NativeOpening o = nativeOpening;
        if (o != null && o.player == player) {
            view = o.view;
        } else if (player.openContainer instanceof RemoteMeWindow w && w.remoteView() != null
            && w.remoteView().monitor != null
            && views.get(player.getUniqueID()) == w.remoteView()) {
                view = w.remoteView();
            } else {
                return null;
            }
        GuiBridge[] guis = GuiBridge.values();
        int id = ordinal >> 5;
        GuiBridge gui = id >= 0 && id < guis.length ? guis[id] : null;
        ItemStack[] main = player.inventory.mainInventory;
        ItemStack terminal = view.slot >= 0 && view.slot < main.length && main[view.slot] != null ? main[view.slot]
            : view.terminal;
        RemoteTerminalObject host = new RemoteTerminalObject(
            view.handler,
            terminal,
            player,
            player.worldObj,
            view.slot,
            view.monitor);
        AEBaseContainer container;
        if (gui == GuiBridge.GUI_ME) {
            container = new ContainerRemoteMeNative(player.inventory, host, view);
        } else if (gui == GuiBridge.GUI_CRAFTING_AMOUNT) {
            container = new ContainerRemoteCraftAmount(player.inventory, host, view);
        } else if (gui == GuiBridge.GUI_CRAFTING_CONFIRM) {
            container = new ContainerRemoteCraftConfirm(player.inventory, host, view);
        } else if (gui == GuiBridge.GUI_CRAFTING_STATUS) {
            container = new ContainerRemoteCraftingStatus(player.inventory, host, view);
        } else {
            return BLOCK;
        }
        ContainerOpenContext context = new ContainerOpenContext(host);
        context.setWorld(player.worldObj);
        context.setX(view.slot);
        context.setY(0);
        context.setZ(0);
        context.setSide(ForgeDirection.UNKNOWN);
        container.setOpenContext(context);
        return container;
    }

    /**
     * For the mixin on AE2's terminal action packet, on a craft request in AE2's window onto a remote network: true
     * when it is swallowed (the item can't be crafted there). Otherwise AE2 opens its amount window, onto the remote
     * network through {@link #nativeContainer}.
     */
    public static boolean onAutoCraft(EntityPlayer player) {
        if (!(player.openContainer instanceof ContainerRemoteMeNative c)) {
            return false;
        }
        return !(c.getTargetStack() instanceof IAEItemStack target && target.isCraftable());
    }

    /**
     * For the mixin on AE2's craft request packet ("Next" in the amount window): for a remote network, opens AE2's plan
     * window and asks the remote network for the plan. True when handled.
     */
    public static boolean onCraftRequest(EntityPlayer player, long amount) {
        if (!(player.openContainer instanceof ContainerRemoteCraftAmount amountWindow)) {
            return false;
        }
        View view = amountWindow.remoteView();
        if (!(player instanceof EntityPlayerMP mp) || !isOpen(mp, view)
            || !(amountWindow.getItemToCraft() instanceof IAEItemStack what)
            || amount <= 0) {
            return true;
        }
        ItemStack kind = what.getItemStack();
        kind.stackSize = 1;
        String item = SupplyKey.encodeItem(kind);
        if (item == null) {
            return true;
        }
        int count = (int) Math.min(amount, MAX_CRAFT);
        PrimaryGui back = amountWindow.getPrimaryGui();
        Platform.openGUI(mp, null, ForgeDirection.UNKNOWN, GuiBridge.GUI_CRAFTING_CONFIRM, view.slot);
        if (!(mp.openContainer instanceof ContainerRemoteCraftConfirm plan) || plan.remoteView() != view) {
            return true;
        }
        IAEItemStack shown = what.copy();
        shown.setStackSize(count);
        plan.setItemToCraft(shown);
        plan.setPrimaryGui(back);
        plan.ordered(kind);
        if (view.providerNode == null || view.outstanding >= MAX_OUTSTANDING) {
            plan.failed("§cУдалённая сеть сейчас недоступна.");
            return true;
        }
        view.outstanding++;
        long session = view.session;
        boolean queued = ClusterService
            .query(db -> RemoteMeStore.queueOp(db, session, RemoteMeStore.PLAN, item, count), plan::awaiting, error -> {
                view.outstanding--;
                if (isOpen(mp, view) && mp.openContainer == plan) {
                    plan.failed("§cСеть серверов сейчас недоступна.");
                }
            });
        if (!queued) {
            view.outstanding--;
            plan.failed("§cСеть серверов сейчас недоступна.");
        }
        return true;
    }

    /** For {@link SupplyGuiHandler}: the view whose window is being opened, or null. */
    public static View opening() {
        return opening;
    }

    /** Remote windows closed this tick; their sessions end unless another window onto them opened instead. */
    private static final Set<View> closing = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** A remote window was closed. AE2 closes its terminal window to open its amount or plan window. */
    static void closed(View view) {
        closing.add(view);
    }

    private static void tickClosing() {
        if (closing.isEmpty()) {
            return;
        }
        for (View view : closing) {
            if (views.get(view.player) != view) {
                // Already ended: replaced by another session or the player left.
                continue;
            }
            EntityPlayerMP player = player(view.player);
            if (player != null && player.openContainer instanceof RemoteMeWindow w && w.remoteView() == view) {
                continue;
            }
            views.remove(view.player);
            settle(view);
        }
        closing.clear();
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
        extract(player, viewOf(player, windowId), kind, amount);
    }

    static void extract(EntityPlayerMP player, View view, ItemStack kind, int amount) {
        if (view == null || !isOpen(player, view)
            || kind == null
            || view.providerNode == null
            || view.outstanding >= MAX_OUTSTANDING) {
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

    /** The player asks the remote network to plan crafting this many; the plan comes back to his window. */
    public static void plan(EntityPlayerMP player, int windowId, ItemStack kind, int amount) {
        View view = viewOf(player, windowId);
        String item = kind == null ? null : SupplyKey.encodeItem(kind);
        if (view == null || item == null || view.providerNode == null || view.outstanding >= MAX_OUTSTANDING) {
            return;
        }
        int count = Math.max(1, Math.min(amount, MAX_CRAFT));
        view.outstanding++;
        long session = view.session;
        ClusterService.query(
            db -> RemoteMeStore.queueOp(db, session, RemoteMeStore.PLAN, item, count),
            id -> {},
            error -> view.outstanding--);
    }

    /** The player starts a plan he was shown, on the processor he picked (-1: any free one). */
    public static void startPlan(EntityPlayerMP player, int windowId, ItemStack kind, long planId, int cpu) {
        startPlan(player, viewOf(player, windowId), kind, planId, cpu);
    }

    /** Actions on a remote crafting processor. */
    public static final int CPU_CANCEL = 0, CPU_SUSPEND = 1, CPU_ALLOW = 2, CPU_FOLLOW = 3;

    /** The crafting status window asks the remote network about its processors (index: the one looked at, or -1). */
    static boolean requestCpus(EntityPlayerMP player, View view, ContainerRemoteCraftingStatus window, int index) {
        if (!isOpen(player, view) || view.providerNode == null || view.outstanding >= MAX_OUTSTANDING) {
            return false;
        }
        view.outstanding++;
        long session = view.session;
        boolean queued = ClusterService
            .query(db -> RemoteMeStore.queueOp(db, session, RemoteMeStore.CPUS, "", index), window::awaiting, error -> {
                view.outstanding--;
                window.awaiting(-1);
            });
        if (!queued) {
            view.outstanding--;
        }
        return queued;
    }

    /** The player cancels, pauses or sets who may use a remote crafting processor. */
    static void cpuAction(EntityPlayerMP player, View view, int index, int action, int arg) {
        if (index < 0 || !isOpen(player, view) || view.providerNode == null || view.outstanding >= MAX_OUTSTANDING) {
            return;
        }
        byte[] data = java.nio.ByteBuffer.allocate(8)
            .putInt(action)
            .putInt(arg)
            .array();
        view.outstanding++;
        long session = view.session;
        // Who follows the craft, for a follow.
        String name = player.getCommandSenderName();
        ClusterService.query(
            db -> RemoteMeStore.queueOp(db, session, RemoteMeStore.CPU_ACT, name, index, data),
            id -> {},
            error -> view.outstanding--);
    }

    /**
     * AE2's notices of finished crafts kept for players not on this server (it shows them at their next login here).
     */
    private static final Field NOTICE_MAPS;

    static {
        Field field;
        try {
            field = CraftingNotificationManager.class.getDeclaredField("notificationMaps");
            field.setAccessible(true);
        } catch (NoSuchFieldException e) {
            CointCore.LOG.warn("[RemoteMe] AE2's craft notices can't be sent to other servers", e);
            field = null;
        }
        NOTICE_MAPS = field;
    }

    /**
     * A craft a player follows finished while he is on another server: AE2 keeps the notice here until he logs in
     * here, so it is sent to his server instead.
     */
    @SuppressWarnings("unchecked")
    private static void relayCraftNotices() {
        if (NOTICE_MAPS == null) {
            return;
        }
        List<Map<String, List<CraftingCPUCluster.CraftNotification>>> maps;
        try {
            maps = (List<Map<String, List<CraftingCPUCluster.CraftNotification>>>) NOTICE_MAPS.get(null);
        } catch (IllegalAccessException e) {
            return;
        }
        for (Map<String, List<CraftingCPUCluster.CraftNotification>> map : maps) {
            for (Iterator<Map.Entry<String, List<CraftingCPUCluster.CraftNotification>>> it = map.entrySet()
                .iterator(); it.hasNext();) {
                Map.Entry<String, List<CraftingCPUCluster.CraftNotification>> e = it.next();
                String target = ClusterService.remotePlayerName(e.getKey());
                if (target == null) {
                    continue;
                }
                for (CraftingCPUCluster.CraftNotification notice : e.getValue()) {
                    String what = notice.getFinalOutput() == null ? "?"
                        : notice.getFinalOutput()
                            .getDisplayName();
                    long seconds = notice.getElapsedTime() / 1_000_000_000L;
                    String text = "§f" + notice.getOutputsCount()
                        + " × "
                        + what
                        + "§a за "
                        + String.format("%d:%02d", seconds / 60, seconds % 60);
                    ClusterService.publishTo(ClusterService.KIND_CRAFT, "AE2", "AE2", target, text);
                }
                it.remove();
            }
        }
    }

    /** A notice of a finished craft from another server ({@link #relayCraftNotices}), for a player here. */
    static void deliverCraftNotice(String server, String target, String text) {
        MinecraftServer mc = MinecraftServer.getServer();
        EntityPlayerMP player = mc == null || target == null ? null
            : mc.getConfigurationManager()
                .func_152612_a(target);
        if (player == null) {
            return;
        }
        player.addChatMessage(new ChatComponentText("§a[ME " + server + "] Крафт готов: " + text));
        player.playerNetServerHandler
            .sendPacket(new S29PacketSoundEffect("random.levelup", player.posX, player.posY, player.posZ, 1f, 1f));
    }

    static void startPlan(EntityPlayerMP player, View view, ItemStack kind, long planId, int cpu) {
        String item = kind == null ? null : SupplyKey.encodeItem(kind);
        if (view == null || !isOpen(player, view)
            || item == null
            || view.providerNode == null
            || view.outstanding >= MAX_OUTSTANDING) {
            return;
        }
        byte[] data = java.nio.ByteBuffer.allocate(4)
            .putInt(cpu)
            .array();
        view.outstanding++;
        long session = view.session;
        ClusterService.query(
            db -> RemoteMeStore.queueOp(db, session, RemoteMeStore.START, item, planId, data),
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
        insertCursor(player, viewOf(player, windowId), one);
    }

    static void insertCursor(EntityPlayerMP player, View view, boolean one) {
        ItemStack held = player.inventory.getItemStack();
        if (view == null || held == null || !isOpen(player, view)) {
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
        return isOpen(player, view) && insert(player, view, stack.copy());
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
        return view != null && view.windowId == windowId && isOpen(player, view) ? view : null;
    }

    /** The view is the player's current one and its window is the one he has open. */
    private static boolean isOpen(EntityPlayerMP player, View view) {
        return views.get(player.getUniqueID()) == view && player.openContainer instanceof RemoteMeWindow w
            && w.remoteView() == view;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tickClosing();
        }
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
            relayCraftNotices();
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
            if (view.monitor != null) {
                updateNative(player, view, v);
            } else if (v.snapshot != null) {
                view.version = v.snapshotVersion;
                sendSnapshot(player, view, v.snapshot);
            } else if (!view.statusSent) {
                sendStatus(player, view);
            }
        }
    }

    /** AE2's own window: the items go into its monitor, the state into chat when it changes. */
    private static void updateNative(EntityPlayerMP player, View view, RemoteMeStore.SessionView v) {
        if (!view.status.equals(view.toldStatus)) {
            view.toldStatus = view.status;
            if (view.status.startsWith("§c")) {
                player.addChatMessage(new ChatComponentText(view.status));
            }
        }
        if (v.snapshot == null) {
            return;
        }
        view.version = v.snapshotVersion;
        NBTTagCompound root;
        try {
            root = CompressedStreamTools.readCompressed(new java.io.ByteArrayInputStream(v.snapshot));
        } catch (IOException e) {
            return;
        }
        view.monitor.update(root);
        int state = root.getByte("s");
        if (state != view.toldState) {
            String text = switch (state) {
                case STATE_MISSING -> "§cУдалённая сеть не найдена";
                case STATE_UNPOWERED -> "§cУдалённая сеть без энергии";
                case STATE_NO_WAP -> "§cТочка доступа удалённой сети выключена";
                default -> view.toldState > STATE_OK ? "§aУдалённая сеть снова доступна" : null;
            };
            view.toldState = state;
            if (text != null) {
                player.addChatMessage(new ChatComponentText(text));
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
        if (RemoteMeStore.CPUS.equals(op.kind)) {
            View view = views.get(player.getUniqueID());
            if (view != null && view.session == op.session
                && player.openContainer instanceof ContainerRemoteCraftingStatus status
                && status.remoteView() == view
                && status.waitsFor(op.id)) {
                status.applyStatus(op.data);
            }
            return;
        }
        if (RemoteMeStore.CPU_ACT.equals(op.kind)) {
            if (op.result == 0) {
                player.addChatMessage(new ChatComponentText("§cУдалённая сеть не выполнила действие с процессором."));
            }
            return;
        }
        ItemStack kind = SupplyKey.decodeItem(op.item);
        if (kind == null) {
            CointCore.LOG.warn("[RemoteMe] Unknown item in operation {} for {}", op.id, player.getCommandSenderName());
            return;
        }
        if (RemoteMeStore.PLAN.equals(op.kind)) {
            View view = views.get(player.getUniqueID());
            if (view != null && view.session == op.session && isOpen(player, view)) {
                if (player.openContainer instanceof ContainerRemoteCraftConfirm plan) {
                    if (plan.waitsFor(op.id)) {
                        plan.applyPlan(op.id, op.result == 1, op.data);
                    }
                } else if (view.monitor == null) {
                    WorldTravelNetwork.CHANNEL.sendTo(
                        new PacketRemoteMeCraftPlan(view.windowId, op.id, op.result == 1, kind, op.amount, op.data),
                        player);
                }
            }
            return;
        }
        if (RemoteMeStore.CRAFT.equals(op.kind) || RemoteMeStore.START.equals(op.kind)) {
            String what = RemoteMeStore.START.equals(op.kind) ? kind.getDisplayName()
                : op.amount + " × " + kind.getDisplayName();
            String text;
            if (op.result == RemoteMeStore.CRAFT_STARTED) {
                text = "§aЗаказ запущен в удалённой сети: " + what;
            } else if (op.result == RemoteMeStore.CRAFT_NO_CPU) {
                text = "§cНе удалось заказать " + what + ": нет свободного процессора крафта";
            } else if (op.result == RemoteMeStore.CRAFT_NO_NETWORK) {
                text = "§cНе удалось заказать " + what + ": сеть недоступна";
            } else if (op.result == RemoteMeStore.CRAFT_PLAN_GONE) {
                text = "§cНе удалось заказать " + what + ": план устарел, рассчитайте заново";
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
        List<Finished> answers = new ArrayList<>();
        for (RemoteMeStore.Op op : poll.ops) {
            Long serial = serials.get(op.session);
            if (RemoteMeStore.CPUS.equals(op.kind)) {
                // Asked every second while a status window is open: no new item snapshot for it.
                answers.add(new Finished(op.id, 1, serial == null ? null : cpuStatus(serial, (int) op.amount)));
                continue;
            }
            changed.add(op.session);
            if (RemoteMeStore.CPU_ACT.equals(op.kind)) {
                results.add(new long[] { op.id, serial == null ? 0 : cpuAction(serial, op) });
                continue;
            }
            if (RemoteMeStore.START.equals(op.kind)) {
                results.add(new long[] { op.id, startPlanned(op) });
                continue;
            }
            if (RemoteMeStore.CRAFT.equals(op.kind) || RemoteMeStore.PLAN.equals(op.kind)) {
                long result = serial == null ? RemoteMeStore.CRAFT_NO_NETWORK
                    : startCraft(serial, op, now, RemoteMeStore.PLAN.equals(op.kind));
                if (result >= 0) {
                    results.add(new long[] { op.id, result });
                }
                continue;
            }
            results.add(new long[] { op.id, serial == null ? 0 : apply(serial, op) });
        }
        if (!results.isEmpty() || !answers.isEmpty()) {
            ClusterService.query(db -> {
                for (long[] r : results) {
                    RemoteMeStore.done(db, r[0], r[1]);
                }
                for (Finished a : answers) {
                    RemoteMeStore.done(db, a.opId, a.result, a.data);
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
        /** Only plan it and keep the plan for a later START, or start it as soon as it is planned. */
        final boolean planOnly;
        final Future<ICraftingJob> job;

        Craft(long opId, long serial, long deadline, boolean planOnly, Future<ICraftingJob> job) {
            this.opId = opId;
            this.serial = serial;
            this.deadline = deadline;
            this.planOnly = planOnly;
            this.job = job;
        }
    }

    /** A planned craft waiting for its player to pick a processor and start it. */
    private static final class Planned {

        final ICraftingJob job;
        final long serial, expiresAt;
        /** The processors in the order the player was shown them. */
        final List<ICraftingCPU> cpus;

        Planned(ICraftingJob job, long serial, long expiresAt, List<ICraftingCPU> cpus) {
            this.job = job;
            this.serial = serial;
            this.expiresAt = expiresAt;
            this.cpus = cpus;
        }
    }

    /** How long a shown plan can still be started. */
    private static final long PLAN_KEEP_MS = 5 * 60_000L;
    /** Most lines of a plan sent to the player. */
    private static final int PLAN_MAX_LINES = 400;

    /** Craft orders the network is still thinking over. */
    private static final List<Craft> crafts = new ArrayList<>();
    /** Plans shown to players, by the id of their PLAN operation. */
    private static final Map<Long, Planned> planned = new HashMap<>();

    /** Starts planning a craft order: -1 while the network thinks it over, else its result. */
    private static long startCraft(long serial, RemoteMeStore.Op op, long now, boolean planOnly) {
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
        crafts.add(new Craft(op.id, serial, now + CRAFT_PLAN_MS, planOnly, job));
        return -1;
    }

    /** Starts a plan the player confirmed, on the processor he picked (-1: any free one). */
    private static long startPlanned(RemoteMeStore.Op op) {
        Planned plan = planned.remove(op.amount);
        if (plan == null) {
            return RemoteMeStore.CRAFT_PLAN_GONE;
        }
        IGrid grid = resolve(plan.serial);
        if (state(grid) != STATE_OK) {
            return RemoteMeStore.CRAFT_NO_NETWORK;
        }
        int cpu = op.data != null && op.data.length >= 4 ? java.nio.ByteBuffer.wrap(op.data)
            .getInt() : -1;
        ICraftingCPU target = cpu >= 0 && cpu < plan.cpus.size() ? plan.cpus.get(cpu) : null;
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        ICraftingLink link = crafting.submitJob(plan.job, null, target, false, SOURCE);
        return link == null ? RemoteMeStore.CRAFT_NO_CPU : RemoteMeStore.CRAFT_STARTED;
    }

    private static final class Finished {

        final long opId, result;
        final byte[] data;

        Finished(long opId, long result, byte[] data) {
            this.opId = opId;
            this.result = result;
            this.data = data;
        }
    }

    /** Sends on the craft orders whose plans are ready: shown to the player, or started at once. */
    private static void tickCrafts(long now) {
        planned.values()
            .removeIf(p -> now > p.expiresAt);
        if (crafts.isEmpty()) {
            return;
        }
        List<Finished> results = new ArrayList<>();
        for (Iterator<Craft> it = crafts.iterator(); it.hasNext();) {
            Craft craft = it.next();
            if (craft.job.isDone()) {
                results.add(craft.planOnly ? plan(craft, now) : new Finished(craft.opId, submit(craft), null));
            } else if (now > craft.deadline) {
                craft.job.cancel(true);
                results.add(new Finished(craft.opId, RemoteMeStore.CRAFT_FAILED, null));
            } else {
                continue;
            }
            it.remove();
        }
        if (!results.isEmpty()) {
            ClusterService.query(db -> {
                for (Finished r : results) {
                    RemoteMeStore.done(db, r.opId, r.result, r.data);
                }
                return null;
            }, ignored -> {}, null);
        }
    }

    private static ICraftingJob jobOf(Craft craft) {
        try {
            return craft.job.get();
        } catch (Exception e) {
            return null;
        }
    }

    private static long submit(Craft craft) {
        ICraftingJob job = jobOf(craft);
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

    /**
     * A finished plan for the player: what it takes from storage, what it crafts, what is missing, its size and the
     * processors to pick from. Result 1 when it can be started, 0 when items are missing or planning failed.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Finished plan(Craft craft, long now) {
        ICraftingJob job = jobOf(craft);
        IGrid grid = resolve(craft.serial);
        if (job == null || state(grid) != STATE_OK) {
            return new Finished(craft.opId, RemoteMeStore.CRAFT_FAILED, null);
        }
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        NBTTagCompound root = new NBTTagCompound();
        root.setLong("b", job.getByteTotal());
        root.setBoolean("s", job.isSimulation());
        String error = job.getErrorMessage();
        if (error != null && !error.isEmpty()) {
            root.setString("e", error);
        }
        IItemList<IAEStack<?>> list = AEApi.instance()
            .storage()
            .createAEStackList();
        job.populatePlan(list);
        NBTTagList lines = new NBTTagList();
        for (IAEStack<?> p : list) {
            if (lines.tagCount() >= PLAN_MAX_LINES) {
                root.setBoolean("cut", true);
                break;
            }
            NBTTagCompound e = new NBTTagCompound();
            long take = p.getStackSize(), make = p.getCountRequestable(), missing = 0;
            if (p instanceof IAEItemStack ais) {
                ItemStack is = ais.getItemStack();
                String name = is == null || is.getItem() == null ? null
                    : Item.itemRegistry.getNameForObject(is.getItem());
                if (name == null) {
                    continue;
                }
                e.setString("i", name);
                e.setShort("d", (short) is.getItemDamage());
                if (is.hasTagCompound()) {
                    e.setTag(
                        "t",
                        is.getTagCompound()
                            .copy());
                }
                if (job.isSimulation() && take > 0) {
                    IAEItemStack have = storage.getItemInventory()
                        .getAvailableItem(ais.copy());
                    missing = Math.max(0, take - (have == null ? 0 : have.getStackSize()));
                }
            } else if (p instanceof IAEFluidStack afs && afs.getFluid() != null) {
                e.setString(
                    "f",
                    afs.getFluid()
                        .getName());
                if (job.isSimulation() && take > 0) {
                    IAEFluidStack have = storage.getFluidInventory()
                        .getAvailableItem(afs.copy());
                    missing = Math.max(0, take - (have == null ? 0 : have.getStackSize()));
                }
            } else {
                continue;
            }
            e.setLong("x", take - missing);
            e.setLong("c", make);
            e.setLong("m", missing);
            lines.appendTag(e);
        }
        root.setTag("l", lines);
        List<ICraftingCPU> cpus = new ArrayList<>();
        NBTTagList cpuTags = new NBTTagList();
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        for (ICraftingCPU cpu : crafting.getCpus()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("n", cpu.getName() == null ? "" : cpu.getName());
            c.setLong("b", cpu.getAvailableStorage());
            c.setInteger("p", cpu.getCoProcessors());
            c.setBoolean("busy", cpu.isBusy());
            // For AE2's own plan window: the processor as AE2 describes it; serial = place in the list + 1.
            NBTTagCompound status = new NBTTagCompound();
            new CraftingCPUStatus(cpu, cpus.size() + 1).writeToNBT(status);
            portableCpu(status);
            c.setTag("st", status);
            cpuTags.appendTag(c);
            cpus.add(cpu);
        }
        root.setTag("cpus", cpuTags);
        boolean startable = !job.isSimulation();
        if (startable) {
            planned.put(craft.opId, new Planned(job, craft.serial, now + PLAN_KEEP_MS, cpus));
        }
        byte[] data;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(root, out);
            data = out.toByteArray();
        } catch (IOException e) {
            return new Finished(craft.opId, RemoteMeStore.CRAFT_FAILED, null);
        }
        return new Finished(craft.opId, startable ? 1 : 0, data);
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

    /**
     * AE2 writes items by their numeric id, which differs between servers: the registry name goes along ("in"), for
     * {@link #localize} on the other server.
     */
    private static void portable(NBTTagCompound stack) {
        if (stack.hasKey("id")) {
            Item item = Item.getItemById(stack.getShort("id"));
            String name = item == null ? null : Item.itemRegistry.getNameForObject(item);
            if (name != null) {
                stack.setString("in", name);
            }
        }
    }

    /** The same for what a processor is crafting, in AE2's description of it. */
    private static void portableCpu(NBTTagCompound status) {
        if (status.hasKey("crafting")) {
            portable(status.getCompoundTag("crafting"));
        }
    }

    /** A stack written on another server ({@link #portable}) with this server's numeric id; false if unknown here. */
    static boolean localize(NBTTagCompound stack) {
        if (!stack.hasKey("in")) {
            return true;
        }
        Item item = (Item) Item.itemRegistry.getObject(stack.getString("in"));
        if (item == null) {
            return false;
        }
        stack.setShort("id", (short) Item.getIdFromItem(item));
        return true;
    }

    /** The same for what a remote processor is crafting; an unknown item is left out. */
    static void localizeCpu(NBTTagCompound status) {
        if (status.hasKey("crafting") && !localize(status.getCompoundTag("crafting"))) {
            status.removeTag("crafting");
        }
    }

    /** Most entries of a processor's job sent to a status window. */
    private static final int STATUS_MAX_ENTRIES = 1000;

    /**
     * The network's crafting processors as AE2 describes them (serial = place in the list + 1) and, for the one at
     * this index, what it is crafting; null when the network is gone.
     */
    private static byte[] cpuStatus(long serial, int index) {
        IGrid grid = resolve(serial);
        if (grid == null) {
            return null;
        }
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        List<ICraftingCPU> cpus = new ArrayList<>(crafting.getCpus());
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < cpus.size(); i++) {
            NBTTagCompound tag = new NBTTagCompound();
            new CraftingCPUStatus(cpus.get(i), i + 1).writeToNBT(tag);
            portableCpu(tag);
            list.appendTag(tag);
        }
        root.setTag("cpus", list);
        if (index >= 0 && index < cpus.size() && cpus.get(index) instanceof CraftingCPUCluster cpu) {
            root.setInteger("sel", index + 1);
            root.setLong("el", cpu.getElapsedTime());
            root.setInteger(
                "al",
                cpu.getCraftingAllowMode()
                    .ordinal());
            root.setBoolean("su", cpu.isSuspended());
            root.setInteger("ro", cpu.getRemainingOperations());
            NBTTagList following = new NBTTagList();
            List<String> names = cpu.getPlayersFollowingCurrentCraft();
            if (names != null) {
                for (String name : names) {
                    following.appendTag(new NBTTagString(name));
                }
            }
            root.setTag("fl", following);
            IItemList<IAEStack<?>> items = AEApi.instance()
                .storage()
                .createAEStackList();
            cpu.getModernListOfItem(items, CraftingItemList.ALL);
            NBTTagList entries = new NBTTagList();
            for (IAEStack<?> item : items) {
                if (entries.tagCount() >= STATUS_MAX_ENTRIES) {
                    break;
                }
                IAEStack<?> stack = CraftingCpuEntry.normalizeStack(item);
                NBTTagCompound e = new NBTTagCompound();
                NBTTagCompound s = new NBTTagCompound();
                stack.writeToNBTGeneric(s);
                portable(s);
                e.setTag("s", s);
                e.setLong("x", cpu.getStackAmount(stack, CraftingItemList.STORAGE));
                e.setLong("a", cpu.getStackAmount(stack, CraftingItemList.ACTIVE));
                e.setLong("p", cpu.getStackAmount(stack, CraftingItemList.PENDING));
                ScheduledReason reason = cpu.getScheduledReason(stack);
                e.setInteger("r", reason == null ? -1 : reason.ordinal());
                entries.appendTag(e);
            }
            root.setTag("e", entries);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(root, out);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** Cancels, pauses or sets who may use a crafting processor: 1 when done, 0 otherwise. */
    private static long cpuAction(long serial, RemoteMeStore.Op op) {
        IGrid grid = resolve(serial);
        if (grid == null || op.data == null || op.data.length < 8) {
            return 0;
        }
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        List<ICraftingCPU> cpus = new ArrayList<>(crafting.getCpus());
        int index = (int) op.amount;
        if (index < 0 || index >= cpus.size() || !(cpus.get(index) instanceof CraftingCPUCluster cpu)) {
            return 0;
        }
        java.nio.ByteBuffer data = java.nio.ByteBuffer.wrap(op.data);
        int action = data.getInt(), arg = data.getInt();
        CraftingAllow[] modes = CraftingAllow.values();
        switch (action) {
            case CPU_CANCEL -> cpu.cancel();
            case CPU_SUSPEND -> cpu.setSuspended(arg != 0);
            case CPU_FOLLOW -> {
                if (op.item.isEmpty()) {
                    return 0;
                }
                cpu.togglePlayerFollowStatus(op.item);
            }
            case CPU_ALLOW -> {
                if (arg < 0 || arg >= modes.length) {
                    return 0;
                }
                cpu.changeCraftingAllowMode(modes[arg]);
            }
            default -> {
                return 0;
            }
        }
        return 1;
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
