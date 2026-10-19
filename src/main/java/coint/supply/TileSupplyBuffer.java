package coint.supply;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import coint.CointCore;
import coint.cluster.SupplyLink;
import coint.cluster.SupplyStore;

/**
 * The cross-server supply buffer. Linked by a card, it is either the provider (next to the main ME network, on the
 * server of the base) or a remote (on another server).
 *
 * <p>
 * A remote sends what is put into its send slots to the provider's ME network, and keeps a stock of the items its
 * filters name, ordered from that network, for pipes or players to take. Its slots have no sides: anything put in goes
 * to the send slots, anything taken out comes from the stocks.
 *
 * <p>
 * A remote saves its chunk with each new operation before it writes it to the database: a crash can never bring back
 * items that were also sent, nor lose an order the provider has filled. The provider changes its ME network at once and
 * reports the change done after its world is saved ({@link SupplyJournal}).
 */
public class TileSupplyBuffer extends TileEntity
    implements ISidedInventory, IFluidHandler, IGridProxyable, IActionHost {

    public enum Role {
        UNLINKED,
        PROVIDER,
        REMOTE
    }

    static final String TO_MAIN = "TO_MAIN";
    static final String FROM_MAIN = "FROM_MAIN";
    static final String ITEM = "ITEM";
    static final String FLUID = "FLUID";

    /** Send slots, then one virtual slot per filter holding its stock. */
    public static final int SLOTS = 18;
    public static final int FILTERS = 9;
    public static final long[] TARGETS = { 64, 256, 576, 1024, 4096, 16384, 65536 };
    private static final long DEFAULT_TARGET = 576;
    private static final long DEFAULT_FLUID_TARGET = 64_000;
    /** Remote: how many fluids at once, and how much of each, wait to go home. */
    private static final int SEND_FLUID_KINDS = 9;
    private static final long SEND_FLUID_CAPACITY = 256_000;
    private static final int TICK_INTERVAL = 10;
    private static final int PROVIDER_TOUCH_TICKS = 100;
    private static final int MAX_OPS = 64;
    private static final int MAX_BATCH = 4096;
    private static final int CLAIM_LIMIT = 8;
    private static final int SYNC_LIMIT = 100;
    private static final long RETRY_MS = 10_000;
    private static final int[] ALL_SLOTS = new int[SLOTS + FILTERS];

    static {
        for (int i = 0; i < ALL_SLOTS.length; i++) {
            ALL_SLOTS[i] = i;
        }
    }

    private Role role = Role.UNLINKED;
    private String link = "";
    private UUID owner;
    private String ownerName = "";
    private final ItemStack[] slots = new ItemStack[SLOTS];
    /** Remote: what each filter keeps in stock (one item, or null), how many it has, and how many it wants. */
    private final ItemStack[] filters = new ItemStack[FILTERS];
    /** The fluid each filter keeps, read from its item (a bucket, a cell, a fluid from NEI); null for items. */
    private final FluidStack[] filterFluid = new FluidStack[FILTERS];
    /** Remote: fluids poured in, waiting to go home. */
    private final List<FluidAmount> sendFluids = new ArrayList<>();
    private final long[] stock = new long[FILTERS];
    private final long[] target = new long[FILTERS];
    /** Remote: at or below this stock a filter orders up to its target. */
    private final long[] threshold = new long[FILTERS];
    private final long[] retryAt = new long[FILTERS];
    /** Remote: operations sent or about to be, kept until their result was taken in. */
    private final List<Pending> pending = new ArrayList<>();
    /** Remote: results taken in, to tell the database at the next round. */
    private final List<UUID> acknowledged = new ArrayList<>();

    private AENetworkProxy proxy;
    private boolean proxyReady;
    private int ticks;
    private boolean inFlight;
    private boolean linkOnline;
    private long lastRoundAt;
    private String lastError = "";

    public TileSupplyBuffer() {
        java.util.Arrays.fill(target, DEFAULT_TARGET);
        java.util.Arrays.fill(threshold, DEFAULT_TARGET / 4);
    }

    // --- roles and settings

    public Role getRole() {
        return role;
    }

    public String getLink() {
        return link;
    }

    public void setOwner(EntityPlayer player) {
        owner = player.getUniqueID();
        ownerName = player.getCommandSenderName();
        markDirty();
    }

    public boolean canEdit(EntityPlayer player) {
        if (owner == null || owner.equals(player.getUniqueID())) {
            return true;
        }
        return MinecraftServer.getServer()
            .getConfigurationManager()
            .func_152596_g(player.getGameProfile());
    }

    /** Becomes the provider of a new link. */
    void makeProvider(String newLink) {
        role = Role.PROVIDER;
        link = newLink;
        if (!proxyReady && worldObj != null && !worldObj.isRemote) {
            proxy().onReady();
            proxyReady = true;
        }
        SupplyLink.touchProvider(link, location(), aeOnline());
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    /** Becomes a remote of an existing link. */
    void makeRemote(String existingLink) {
        role = Role.REMOTE;
        link = existingLink;
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    public ItemStack getFilter(int index) {
        return filters[index];
    }

    public long getStock(int index) {
        return stock[index];
    }

    public long getTarget(int index) {
        return target[index];
    }

    public long getThreshold(int index) {
        return threshold[index];
    }

    /** Most a filter may keep: far beyond any real need, short of overflowing anything. */
    public static final long MAX_TARGET = 1_000_000_000_000L;

    /** Sets how much a filter keeps and at what stock it orders more (never above the target). */
    void setAmounts(int index, long newTarget, long newThreshold) {
        target[index] = Math.max(1, Math.min(MAX_TARGET, newTarget));
        threshold[index] = Math.max(0, Math.min(target[index], newThreshold));
        retryAt[index] = 0;
        markDirty();
    }

    /** Names what a filter keeps (one of {@code item}), or clears it; what it held of the old kind goes back home. */
    void setFilter(int index, ItemStack item) {
        ItemStack kind = item == null ? null : item.copy();
        FluidStack fluid = SupplyFluids.fluidOf(kind);
        if (kind != null) {
            kind.stackSize = 1;
            String key = fluid != null ? SupplyKey.encodeFluid(fluid) : SupplyKey.encodeItem(kind);
            for (int i = 0; i < FILTERS; i++) {
                if (i != index && filters[i] != null && key != null && key.equals(keyOf(i))) {
                    return;
                }
            }
        }
        if (filters[index] != null && stock[index] > 0) {
            giveBack(typeOf(index), keyOf(index), stock[index]);
        }
        boolean wasFluid = filterFluid[index] != null;
        filters[index] = kind;
        filterFluid[index] = fluid;
        stock[index] = 0;
        retryAt[index] = 0;
        if (kind != null && (fluid != null) != wasFluid) {
            // Millibuckets and items are counted far apart.
            target[index] = fluid != null ? DEFAULT_FLUID_TARGET : DEFAULT_TARGET;
            threshold[index] = target[index] / 4;
        }
        markDirty();
    }

    /** Client side: the filter as the server has it, shown in the window. */
    void setFilterClient(int index, ItemStack item) {
        filters[index] = item;
        filterFluid[index] = SupplyFluids.fluidOf(item);
    }

    /** The fluid a filter keeps, or null when it keeps an item (or nothing). */
    public FluidStack getFilterFluid(int index) {
        return filterFluid[index];
    }

    private String typeOf(int index) {
        return filterFluid[index] != null ? FLUID : ITEM;
    }

    /** What a filter keeps, as the database names it. */
    private String keyOf(int index) {
        if (filters[index] == null) {
            return null;
        }
        return filterFluid[index] != null ? SupplyKey.encodeFluid(filterFluid[index])
            : SupplyKey.encodeItem(filters[index]);
    }

    /** Next or previous stock target of a filter. */
    void cycleTarget(int index, boolean up) {
        int at = 0;
        for (int i = 0; i < TARGETS.length; i++) {
            if (TARGETS[i] <= target[index]) {
                at = i;
            }
        }
        at = Math.max(0, Math.min(TARGETS.length - 1, at + (up ? 1 : -1)));
        long old = target[index];
        target[index] = TARGETS[at];
        // The threshold keeps its share of the target.
        threshold[index] = old <= 0 ? target[index] / 4
            : Math.min(target[index], Math.round(threshold[index] * (double) target[index] / old));
        markDirty();
    }

    /** What is said when looked at, and shown on top of the window. */
    List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add("§6Межсерверный буфер снабжения");
        switch (role) {
            case UNLINKED -> lines.add("§7Не привязан: ПКМ пустой картой связи — сделать Provider у главной ME.");
            case PROVIDER -> {
                lines.add("§eProvider §7связь §f" + shortLink());
                lines.add(aeOnline() ? "§aME сеть в сети" : "§cНет ME сети (кабель, канал, питание)");
            }
            case REMOTE -> {
                lines.add("§eRemote §7связь §f" + shortLink());
                lines.add(linkOnline ? "§aProvider в сети" : "§cProvider недоступен — всё ждёт в буфере");
                lines.add("§7Операций в пути: §f" + pending.size());
                if (!sendFluids.isEmpty()) {
                    lines.add("§7Жидкостей ждёт отправки: §f" + sendFluidTotal() + " §7mB");
                }
            }
        }
        if (!SupplyLink.enabled()) {
            lines.add("§cСеть серверов выключена (cluster.enabled=false)");
        } else if (!lastError.isEmpty()) {
            lines.add("§cОшибка: " + lastError);
        }
        if (!ownerName.isEmpty()) {
            lines.add("§7Владелец: §f" + ownerName);
        }
        return lines;
    }

    boolean isLinkOnline() {
        return linkOnline;
    }

    int pendingCount() {
        return pending.size();
    }

    String lastError() {
        return SupplyLink.enabled() ? lastError : "cluster.enabled=false";
    }

    private String shortLink() {
        return link.length() > 8 ? link.substring(0, 8) : link;
    }

    // --- ticking

    @Override
    public void updateEntity() {
        if (worldObj.isRemote) {
            return;
        }
        if (role == Role.PROVIDER && !proxyReady) {
            proxy().onReady();
            proxyReady = true;
        }
        if (++ticks % TICK_INTERVAL != 0 || !SupplyLink.enabled()) {
            return;
        }
        if (role == Role.PROVIDER) {
            tickProvider();
        } else if (role == Role.REMOTE) {
            tickRemote();
        }
    }

    private void tickProvider() {
        boolean online = aeOnline();
        if (ticks % PROVIDER_TOUCH_TICKS == 0) {
            SupplyLink.touchProvider(link, location(), online);
        }
        if (!online || inFlight) {
            return;
        }
        inFlight = SupplyLink.claim(link, CLAIM_LIMIT, ops -> {
            inFlight = false;
            lastError = "";
            for (SupplyStore.Op op : ops) {
                applyOnProvider(op);
            }
        }, error -> {
            inFlight = false;
            lastError = error;
        });
    }

    private void applyOnProvider(SupplyStore.Op op) {
        if (SupplyJournal.known(op.id)) {
            // Done and saved before this server restarted: only its report was lost.
            SupplyJournal.reportAgain(op.id);
            return;
        }
        boolean toMain = TO_MAIN.equals(op.direction);
        if (!toMain && !FROM_MAIN.equals(op.direction) || !ITEM.equals(op.type) && !FLUID.equals(op.type)) {
            SupplyLink.failed(op.id, "unknown operation: " + op.direction + " " + op.type);
            return;
        }
        long delivered;
        try {
            delivered = FLUID.equals(op.type) ? moveFluid(op, toMain) : moveItems(op, toMain);
        } catch (GridAccessException e) {
            SupplyLink.release(op.id, "ME network away");
            return;
        }
        if (delivered < 0) {
            return;
        }
        if (toMain && delivered == 0) {
            SupplyLink.release(op.id, "ME network full");
            return;
        }
        // Into the network: the rest, if it filled up, goes back to the remote when it reads the result.
        // Out of it: nothing there is an answer too, the remote asks again later.
        SupplyJournal.done(op.id, delivered, worldObj.provider.dimensionId);
    }

    /** How many items went in or came out; -1 when the operation failed for good. */
    private long moveItems(SupplyStore.Op op, boolean toMain) throws GridAccessException {
        ItemStack kind = SupplyKey.decodeItem(op.payload);
        if (kind == null) {
            SupplyLink.failed(op.id, "unknown item on this server");
            return -1;
        }
        IMEMonitor<IAEItemStack> inventory = proxy().getStorage()
            .getItemInventory();
        IAEItemStack stack = AEItemStack.create(kind)
            .setStackSize(op.amount);
        if (toMain) {
            IAEItemStack left = inventory.injectItems(stack, Actionable.MODULATE, new MachineSource(this));
            return op.amount - (left == null ? 0 : left.getStackSize());
        }
        IAEItemStack taken = inventory.extractItems(stack, Actionable.MODULATE, new MachineSource(this));
        return taken == null ? 0 : taken.getStackSize();
    }

    /** How many millibuckets went in or came out; -1 when the operation failed for good. */
    private long moveFluid(SupplyStore.Op op, boolean toMain) throws GridAccessException {
        FluidStack kind = SupplyKey.decodeFluid(op.payload);
        if (kind == null) {
            SupplyLink.failed(op.id, "unknown fluid on this server");
            return -1;
        }
        IMEMonitor<IAEFluidStack> inventory = proxy().getStorage()
            .getFluidInventory();
        IAEFluidStack stack = AEFluidStack.create(kind)
            .setStackSize(op.amount);
        if (toMain) {
            IAEFluidStack left = inventory.injectItems(stack, Actionable.MODULATE, new MachineSource(this));
            return op.amount - (left == null ? 0 : left.getStackSize());
        }
        IAEFluidStack taken = inventory.extractItems(stack, Actionable.MODULATE, new MachineSource(this));
        return taken == null ? 0 : taken.getStackSize();
    }

    private void tickRemote() {
        orderStock();
        reserveOutbound();
        boolean unsaved = false;
        for (Pending p : pending) {
            unsaved |= !p.durable;
        }
        if (unsaved && !saveChunk()) {
            return;
        }
        if (inFlight) {
            return;
        }
        List<SupplyStore.Op> ops = new ArrayList<>();
        for (Pending p : pending) {
            if (p.durable && ops.size() < SYNC_LIMIT) {
                ops.add(p.op);
            }
        }
        if (ops.isEmpty() && acknowledged.isEmpty() && System.currentTimeMillis() - lastRoundAt < 5000) {
            return;
        }
        List<UUID> acks = new ArrayList<>(acknowledged);
        lastRoundAt = System.currentTimeMillis();
        inFlight = SupplyLink.syncRemote(link, ops, acks, result -> {
            inFlight = false;
            lastError = "";
            linkOnline = result.providerOnline;
            acknowledged.removeAll(acks);
            takeResults(result);
        }, error -> {
            inFlight = false;
            lastError = error;
        });
    }

    /** Orders from the main network what the filters run low on: at or below its threshold, up to its target. */
    private void orderStock() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < FILTERS && pending.size() < MAX_OPS; i++) {
            if (filters[i] == null || now < retryAt[i]) {
                continue;
            }
            String key = keyOf(i);
            if (key == null) {
                continue;
            }
            long coming = 0;
            for (Pending p : pending) {
                if (FROM_MAIN.equals(p.op.direction) && key.equals(p.op.payload)) {
                    coming += p.op.amount;
                }
            }
            if (stock[i] + coming > threshold[i]) {
                continue;
            }
            long amount = target[i] - stock[i] - coming;
            if (amount > 0) {
                pending.add(new Pending(new SupplyStore.Op(UUID.randomUUID(), FROM_MAIN, typeOf(i), key, amount)));
                markDirty();
            }
        }
    }

    /** Moves items out of the send slots into new operations. */
    private void reserveOutbound() {
        boolean any = false;
        while (pending.size() < MAX_OPS) {
            int first = -1;
            for (int i = 0; i < SLOTS; i++) {
                if (slots[i] != null) {
                    first = i;
                    break;
                }
            }
            if (first < 0) {
                break;
            }
            ItemStack kind = slots[first].copy();
            kind.stackSize = 1;
            String payload = SupplyKey.encodeItem(kind);
            if (payload == null) {
                // Can't be named: stays where it is, out of the way.
                break;
            }
            long amount = 0;
            for (int i = first; i < SLOTS && amount < MAX_BATCH; i++) {
                ItemStack stack = slots[i];
                if (stack == null || !sameKind(stack, kind)) {
                    continue;
                }
                int take = (int) Math.min(stack.stackSize, MAX_BATCH - amount);
                amount += take;
                stack.stackSize -= take;
                if (stack.stackSize <= 0) {
                    slots[i] = null;
                }
            }
            pending.add(new Pending(new SupplyStore.Op(UUID.randomUUID(), TO_MAIN, ITEM, payload, amount)));
            any = true;
        }
        Iterator<FluidAmount> fluids = sendFluids.iterator();
        while (fluids.hasNext() && pending.size() < MAX_OPS) {
            FluidAmount fluid = fluids.next();
            String payload = SupplyKey.encodeFluid(fluid.kind);
            if (payload != null && fluid.amount > 0) {
                pending.add(new Pending(new SupplyStore.Op(UUID.randomUUID(), TO_MAIN, FLUID, payload, fluid.amount)));
                any = true;
            }
            fluids.remove();
        }
        if (any) {
            markDirty();
        }
    }

    /** Saves this chunk now, then lets the new operations go out. */
    private boolean saveChunk() {
        try {
            WorldServer world = (WorldServer) worldObj;
            Chunk chunk = world.getChunkFromBlockCoords(xCoord, zCoord);
            world.theChunkProviderServer.currentChunkLoader.saveChunk(world, chunk);
        } catch (MinecraftException | IOException | RuntimeException e) {
            lastError = "chunk not saved: " + e.getMessage();
            CointCore.LOG.warn("[Supply] Could not save the chunk of the buffer at {}: {}", location(), e.getMessage());
            return false;
        }
        for (Pending p : pending) {
            p.durable = true;
        }
        return true;
    }

    private void takeResults(SupplyStore.SyncResult result) {
        boolean changed = false;
        long now = System.currentTimeMillis();
        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            SupplyStore.Result r = result.results.get(p.op.id);
            if (r == null || !(r.done() || r.failed())) {
                continue;
            }
            long delivered = r.failed() ? 0 : r.delivered;
            if (TO_MAIN.equals(p.op.direction)) {
                if (p.op.amount - delivered > 0) {
                    giveBack(p.op.type, p.op.payload, p.op.amount - delivered);
                }
            } else {
                int filter = filterOf(p.op.payload);
                if (delivered > 0) {
                    if (filter >= 0) {
                        stock[filter] += delivered;
                    } else {
                        // The filter changed meanwhile: what came goes home again.
                        giveBack(p.op.type, p.op.payload, delivered);
                    }
                }
                if (filter >= 0 && delivered < p.op.amount) {
                    retryAt[filter] = now + RETRY_MS;
                }
            }
            if (r.failed() && r.error != null) {
                lastError = r.error;
            }
            it.remove();
            acknowledged.add(p.op.id);
            changed = true;
        }
        if (changed) {
            markDirty();
        }
    }

    private int filterOf(String payload) {
        for (int i = 0; i < FILTERS; i++) {
            if (filters[i] != null && payload.equals(keyOf(i))) {
                return i;
            }
        }
        return -1;
    }

    /** What has to go home again: items into the send slots (dropped on top when full), fluids to the send tanks. */
    private void giveBack(String type, String payload, long amount) {
        if (payload == null || amount <= 0) {
            return;
        }
        if (FLUID.equals(type)) {
            FluidStack fluid = SupplyKey.decodeFluid(payload);
            if (fluid != null) {
                addSendFluid(fluid, amount);
            }
            return;
        }
        ItemStack kind = SupplyKey.decodeItem(payload);
        if (kind == null) {
            return;
        }
        while (amount > 0) {
            ItemStack stack = kind.copy();
            stack.stackSize = (int) Math.min(amount, kind.getMaxStackSize());
            amount -= stack.stackSize;
            int left = insert(stack);
            if (left > 0) {
                stack.stackSize = left;
                EntityItem item = new EntityItem(worldObj, xCoord + 0.5, yCoord + 1.2, zCoord + 0.5, stack);
                worldObj.spawnEntityInWorld(item);
            }
        }
    }

    private int insert(ItemStack stack) {
        int left = stack.stackSize;
        for (int i = 0; i < SLOTS && left > 0; i++) {
            if (slots[i] != null && sameKind(slots[i], stack)) {
                int room = slots[i].getMaxStackSize() - slots[i].stackSize;
                int put = Math.min(room, left);
                slots[i].stackSize += put;
                left -= put;
            }
        }
        for (int i = 0; i < SLOTS && left > 0; i++) {
            if (slots[i] == null) {
                ItemStack put = stack.copy();
                put.stackSize = Math.min(left, stack.getMaxStackSize());
                slots[i] = put;
                left -= put.stackSize;
            }
        }
        return left;
    }

    static boolean sameKind(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage()
            && ItemStack.areItemStackTagsEqual(a, b);
    }

    /** What is left when the block is broken: the send slots and the stocks. */
    void dropContents() {
        for (int i = 0; i < SLOTS; i++) {
            if (slots[i] != null) {
                drop(slots[i]);
                slots[i] = null;
            }
        }
        for (int i = 0; i < FILTERS; i++) {
            while (filters[i] != null && filterFluid[i] == null && stock[i] > 0) {
                ItemStack stack = filters[i].copy();
                stack.stackSize = (int) Math.min(stock[i], stack.getMaxStackSize());
                stock[i] -= stack.stackSize;
                drop(stack);
            }
        }
    }

    private void drop(ItemStack stack) {
        worldObj.spawnEntityInWorld(new EntityItem(worldObj, xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, stack));
    }

    private boolean aeOnline() {
        return proxyReady && proxy().isActive();
    }

    private String location() {
        return String.format(
            Locale.ROOT,
            "dim %d %d %d %d",
            worldObj == null ? 0 : worldObj.provider.dimensionId,
            xCoord,
            yCoord,
            zCoord);
    }

    // --- AE2

    AENetworkProxy proxy() {
        if (proxy == null) {
            proxy = new AENetworkProxy(this, "proxy", new ItemStack(SupplyBlocks.buffer), true);
            proxy.setIdlePowerUsage(2.0);
            proxy.setFlags(GridFlags.REQUIRE_CHANNEL);
        }
        return proxy;
    }

    @Override
    public AENetworkProxy getProxy() {
        return proxy();
    }

    @Override
    public IGridNode getGridNode(ForgeDirection dir) {
        return role == Role.PROVIDER ? proxy().getNode() : null;
    }

    @Override
    public AECableType getCableConnectionType(ForgeDirection dir) {
        return role == Role.PROVIDER ? AECableType.SMART : AECableType.NONE;
    }

    @Override
    public IGridNode getActionableNode() {
        return proxy().getNode();
    }

    @Override
    public DimensionalCoord getLocation() {
        return new DimensionalCoord(this);
    }

    @Override
    public void gridChanged() {}

    @Override
    public void securityBreak() {
        worldObj.func_147480_a(xCoord, yCoord, zCoord, true);
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (proxyReady) {
            proxy().onChunkUnload();
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (proxyReady) {
            proxy().invalidate();
        }
    }

    @Override
    public void validate() {
        super.validate();
        proxy().validate();
    }

    // --- saving

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setString("role", role.name());
        tag.setString("link", link);
        if (owner != null) {
            tag.setString("owner", owner.toString());
            tag.setString("ownerName", ownerName);
        }
        NBTTagList items = new NBTTagList();
        for (int i = 0; i < SLOTS; i++) {
            if (slots[i] != null) {
                NBTTagCompound item = new NBTTagCompound();
                item.setByte("Slot", (byte) i);
                slots[i].writeToNBT(item);
                items.appendTag(item);
            }
        }
        tag.setTag("items", items);
        NBTTagList filterList = new NBTTagList();
        for (int i = 0; i < FILTERS; i++) {
            NBTTagCompound filter = new NBTTagCompound();
            filter.setByte("Index", (byte) i);
            if (filters[i] != null) {
                filters[i].writeToNBT(filter);
            }
            filter.setLong("stock", stock[i]);
            filter.setLong("target", target[i]);
            filter.setLong("threshold", threshold[i]);
            filterList.appendTag(filter);
        }
        tag.setTag("filters", filterList);
        NBTTagList fluidList = new NBTTagList();
        for (FluidAmount fluid : sendFluids) {
            NBTTagCompound entry = new NBTTagCompound();
            fluid.kind.writeToNBT(entry);
            entry.setLong("SendAmount", fluid.amount);
            fluidList.appendTag(entry);
        }
        tag.setTag("sendFluids", fluidList);
        NBTTagList ops = new NBTTagList();
        for (Pending p : pending) {
            NBTTagCompound op = new NBTTagCompound();
            op.setString("id", p.op.id.toString());
            op.setString("dir", p.op.direction);
            op.setString("type", p.op.type);
            op.setString("payload", p.op.payload);
            op.setLong("amount", p.op.amount);
            ops.appendTag(op);
        }
        tag.setTag("pending", ops);
        NBTTagList acks = new NBTTagList();
        for (UUID id : acknowledged) {
            NBTTagCompound ack = new NBTTagCompound();
            ack.setString("id", id.toString());
            acks.appendTag(ack);
        }
        tag.setTag("acks", acks);
        proxy().writeToNBT(tag);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        try {
            role = Role.valueOf(tag.getString("role"));
        } catch (IllegalArgumentException e) {
            role = Role.UNLINKED;
        }
        link = tag.getString("link");
        if (tag.hasKey("owner")) {
            owner = UUID.fromString(tag.getString("owner"));
            ownerName = tag.getString("ownerName");
        }
        NBTTagList items = tag.getTagList("items", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound item = items.getCompoundTagAt(i);
            int slot = item.getByte("Slot");
            if (slot >= 0 && slot < SLOTS) {
                slots[slot] = ItemStack.loadItemStackFromNBT(item);
            }
        }
        NBTTagList filterList = tag.getTagList("filters", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < filterList.tagCount(); i++) {
            NBTTagCompound filter = filterList.getCompoundTagAt(i);
            int index = filter.getByte("Index");
            if (index >= 0 && index < FILTERS) {
                filters[index] = filter.hasKey("id") ? ItemStack.loadItemStackFromNBT(filter) : null;
                filterFluid[index] = SupplyFluids.fluidOf(filters[index]);
                stock[index] = filter.getLong("stock");
                target[index] = filter.hasKey("target") ? filter.getLong("target") : DEFAULT_TARGET;
                threshold[index] = filter.hasKey("threshold") ? filter.getLong("threshold") : target[index] / 4;
            }
        }
        sendFluids.clear();
        NBTTagList fluidList = tag.getTagList("sendFluids", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < fluidList.tagCount(); i++) {
            NBTTagCompound entry = fluidList.getCompoundTagAt(i);
            FluidStack kind = FluidStack.loadFluidStackFromNBT(entry);
            if (kind != null) {
                kind.amount = 1;
                sendFluids.add(new FluidAmount(kind, entry.getLong("SendAmount")));
            }
        }
        pending.clear();
        NBTTagList ops = tag.getTagList("pending", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < ops.tagCount(); i++) {
            NBTTagCompound op = ops.getCompoundTagAt(i);
            Pending p = new Pending(
                new SupplyStore.Op(
                    UUID.fromString(op.getString("id")),
                    op.getString("dir"),
                    op.getString("type"),
                    op.getString("payload"),
                    op.getLong("amount")));
            // Read back from the world: it was saved with them.
            p.durable = true;
            pending.add(p);
        }
        acknowledged.clear();
        NBTTagList acks = tag.getTagList("acks", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < acks.tagCount(); i++) {
            acknowledged.add(
                UUID.fromString(
                    acks.getCompoundTagAt(i)
                        .getString("id")));
        }
        proxy().readFromNBT(tag);
    }

    // --- inventory: no sides; what comes in goes to the send slots, what goes out comes from the stocks

    @Override
    public int getSizeInventory() {
        return SLOTS + FILTERS;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < SLOTS) {
            return slots[slot];
        }
        int f = slot - SLOTS;
        // A fluid stock is drawn by pipes as a fluid, never taken as an item.
        if (filters[f] == null || filterFluid[f] != null || stock[f] <= 0) {
            return null;
        }
        ItemStack shown = filters[f].copy();
        shown.stackSize = (int) Math.min(stock[f], shown.getMaxStackSize());
        return shown;
    }

    @Override
    public ItemStack decrStackSize(int slot, int count) {
        if (slot < SLOTS) {
            ItemStack stack = slots[slot];
            if (stack == null) {
                return null;
            }
            ItemStack taken = stack.splitStack(Math.min(count, stack.stackSize));
            if (stack.stackSize <= 0) {
                slots[slot] = null;
            }
            markDirty();
            return taken;
        }
        int f = slot - SLOTS;
        if (filters[f] == null || filterFluid[f] != null || stock[f] <= 0 || count <= 0) {
            return null;
        }
        ItemStack taken = filters[f].copy();
        taken.stackSize = (int) Math.min(Math.min(count, stock[f]), taken.getMaxStackSize());
        stock[f] -= taken.stackSize;
        markDirty();
        return taken;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        return null;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (slot < SLOTS) {
            slots[slot] = stack;
            markDirty();
            return;
        }
        // Some pipes take from the stack they were shown and write back what is left of it.
        int f = slot - SLOTS;
        if (filters[f] == null || filterFluid[f] != null || stack != null && !sameKind(stack, filters[f])) {
            return;
        }
        ItemStack shown = getStackInSlot(slot);
        long before = shown == null ? 0 : shown.stackSize;
        long after = stack == null ? 0 : stack.stackSize;
        stock[f] = Math.max(0, stock[f] + after - before);
        markDirty();
    }

    @Override
    public String getInventoryName() {
        return "cointcore.supply_buffer";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return worldObj.getTileEntity(xCoord, yCoord, zCoord) == this
            && player.getDistanceSq(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5) <= 64;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return role == Role.REMOTE && slot < SLOTS;
    }

    @Override
    public int[] getAccessibleSlotsFromSide(int side) {
        return role == Role.REMOTE ? ALL_SLOTS : new int[0];
    }

    @Override
    public boolean canInsertItem(int slot, ItemStack stack, int side) {
        return role == Role.REMOTE && slot < SLOTS;
    }

    @Override
    public boolean canExtractItem(int slot, ItemStack stack, int side) {
        return role == Role.REMOTE && slot >= SLOTS;
    }

    // --- fluids: no sides either; what is poured in goes home, what is drawn out comes from the fluid stocks

    /** Adds to what waits to go home; false when there is no room for a new fluid. */
    private boolean addSendFluid(FluidStack kind, long amount) {
        for (FluidAmount fluid : sendFluids) {
            if (fluid.kind.isFluidEqual(kind)) {
                fluid.amount += amount;
                markDirty();
                return true;
            }
        }
        if (sendFluids.size() >= SEND_FLUID_KINDS) {
            return false;
        }
        FluidStack one = kind.copy();
        one.amount = 1;
        sendFluids.add(new FluidAmount(one, amount));
        markDirty();
        return true;
    }

    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        if (role != Role.REMOTE || resource == null || resource.amount <= 0 || resource.getFluid() == null) {
            return 0;
        }
        long room = SEND_FLUID_CAPACITY;
        boolean known = false;
        for (FluidAmount fluid : sendFluids) {
            if (fluid.kind.isFluidEqual(resource)) {
                room = SEND_FLUID_CAPACITY - fluid.amount;
                known = true;
            }
        }
        if (!known && sendFluids.size() >= SEND_FLUID_KINDS) {
            return 0;
        }
        int taken = (int) Math.max(0, Math.min(resource.amount, room));
        if (doFill && taken > 0) {
            addSendFluid(resource, taken);
        }
        return taken;
    }

    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
        if (resource == null) {
            return null;
        }
        for (int i = 0; i < FILTERS; i++) {
            if (filterFluid[i] != null && filterFluid[i].isFluidEqual(resource)) {
                return drainFilter(i, resource.amount, doDrain);
            }
        }
        return null;
    }

    @Override
    public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
        for (int i = 0; i < FILTERS; i++) {
            if (filterFluid[i] != null && stock[i] > 0) {
                return drainFilter(i, maxDrain, doDrain);
            }
        }
        return null;
    }

    private FluidStack drainFilter(int index, int amount, boolean doDrain) {
        if (role != Role.REMOTE || amount <= 0 || stock[index] <= 0) {
            return null;
        }
        int taken = (int) Math.min(amount, stock[index]);
        if (doDrain) {
            stock[index] -= taken;
            markDirty();
        }
        FluidStack out = filterFluid[index].copy();
        out.amount = taken;
        return out;
    }

    @Override
    public boolean canFill(ForgeDirection from, Fluid fluid) {
        return role == Role.REMOTE;
    }

    @Override
    public boolean canDrain(ForgeDirection from, Fluid fluid) {
        if (role != Role.REMOTE) {
            return false;
        }
        for (int i = 0; i < FILTERS; i++) {
            if (filterFluid[i] != null && (fluid == null || filterFluid[i].getFluid() == fluid)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from) {
        List<FluidTankInfo> tanks = new ArrayList<>();
        for (int i = 0; i < FILTERS; i++) {
            if (filterFluid[i] != null) {
                FluidStack shown = filterFluid[i].copy();
                shown.amount = (int) Math.min(Integer.MAX_VALUE, stock[i]);
                tanks.add(new FluidTankInfo(shown, (int) Math.min(Integer.MAX_VALUE, target[i])));
            }
        }
        for (FluidAmount fluid : sendFluids) {
            FluidStack shown = fluid.kind.copy();
            shown.amount = (int) Math.min(Integer.MAX_VALUE, fluid.amount);
            tanks.add(new FluidTankInfo(shown, (int) SEND_FLUID_CAPACITY));
        }
        return tanks.toArray(new FluidTankInfo[0]);
    }

    /** Millibuckets waiting to go home, all fluids together. */
    long sendFluidTotal() {
        long total = 0;
        for (FluidAmount fluid : sendFluids) {
            total += fluid.amount;
        }
        return total;
    }

    private static final class FluidAmount {

        final FluidStack kind;
        long amount;

        FluidAmount(FluidStack kind, long amount) {
            this.kind = kind;
            this.amount = amount;
        }
    }

    private static final class Pending {

        final SupplyStore.Op op;
        /** Saved in the world with this operation: it may be written to the database. */
        boolean durable;

        Pending(SupplyStore.Op op) {
            this.op = op;
        }
    }
}
