package coint.cluster;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.google.common.collect.ImmutableSet;

import appeng.api.config.CraftingAllow;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.storage.data.IAEStack;
import appeng.container.implementations.ContainerCPUTable;
import appeng.container.implementations.ContainerCraftingStatus;
import appeng.container.implementations.CraftingCPUStatus;
import appeng.container.implementations.CraftingCpuEntry;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.PacketCompressedNBT;
import appeng.core.sync.packets.PacketCraftingCPUTableUpdate;
import appeng.core.sync.packets.PacketCraftingCpuUpdate;
import appeng.util.ScheduledReason;

/**
 * AE2's crafting status window for a network on another server. About once a second it asks the remote network for
 * its processors and for what the one looked at is crafting; cancel, pause and the allowed requesters go back to the
 * remote network as operations.
 */
public class ContainerRemoteCraftingStatus extends ContainerCraftingStatus implements RemoteMeWindow {

    /** The processor table looks its picks up among the local network's processors; there are none here. */
    private static final Field LAST_CPU_SET;
    /** How often the window asks again once an answer came. */
    private static final long REFRESH_MS = 1000;

    static {
        try {
            LAST_CPU_SET = ContainerCPUTable.class.getDeclaredField("lastCpuSet");
            LAST_CPU_SET.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private final RemoteMe.View view;
    /** The serial of the processor looked at (its place in the remote list + 1), or -1. */
    private int shown = -1;
    private boolean inFlight;
    private long awaited = -1;
    private long nextAt;

    ContainerRemoteCraftingStatus(InventoryPlayer inventory, RemoteTerminalObject host, RemoteMe.View view) {
        super(inventory, host);
        this.view = view;
        // AE2 gives up on the window when it finds no local network.
        setValidContainer(true);
        try {
            LAST_CPU_SET.set(cpuTable, ImmutableSet.of());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public RemoteMe.View remoteView() {
        return view;
    }

    /** The picked processor is one of the remote network's: shown once its next status arrives. */
    @Override
    protected void setCPU(ICraftingCPU cpu) {
        int picked = cpuTable == null ? -1 : cpuTable.selectedCpuSerial;
        if (picked == shown) {
            return;
        }
        shown = picked;
        elapsed = -1;
        cachedSuspend = false;
        send(new ArrayList<>(), 0, Collections.emptyList());
        // Ask at once about the new pick, unless an answer is on its way.
        nextAt = 0;
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (!(getInventoryPlayer().player instanceof EntityPlayerMP player) || inFlight) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextAt) {
            return;
        }
        nextAt = now + REFRESH_MS;
        inFlight = RemoteMe.requestCpus(player, view, this, shown - 1);
    }

    /** The id of the status request sent; -1 when it couldn't be sent. */
    void awaiting(long opId) {
        if (opId < 0) {
            inFlight = false;
        } else {
            awaited = opId;
        }
    }

    boolean waitsFor(long opId) {
        return inFlight && awaited == opId;
    }

    /** The remote network's answer: its processors and what the one looked at is crafting. */
    void applyStatus(byte[] data) {
        inFlight = false;
        nextAt = System.currentTimeMillis() + REFRESH_MS;
        NBTTagCompound root = null;
        if (data != null && data.length > 0) {
            try {
                root = CompressedStreamTools.readCompressed(new ByteArrayInputStream(data));
            } catch (IOException e) {
                root = null;
            }
        }
        if (root == null || !(getInventoryPlayer().player instanceof EntityPlayerMP player)) {
            return;
        }
        List<CraftingCPUStatus> cpus = new ArrayList<>();
        NBTTagList cpuTags = root.getTagList("cpus", 10);
        for (int i = 0; i < cpuTags.tagCount(); i++) {
            NBTTagCompound status = cpuTags.getCompoundTagAt(i);
            RemoteMe.localizeCpu(status);
            cpus.add(new CraftingCPUStatus(status));
        }
        cpuTable.handleCPUUpdate(cpus.toArray(new CraftingCPUStatus[0]));
        try {
            NetworkHandler.instance.sendTo(new PacketCraftingCPUTableUpdate(cpus), player);
        } catch (IOException ignored) {}
        if (shown < 0 || root.getInteger("sel") != shown) {
            return;
        }
        elapsed = root.getLong("el");
        CraftingAllow[] modes = CraftingAllow.values();
        int mode = root.getInteger("al");
        allow = mode >= 0 && mode < modes.length ? modes[mode] : CraftingAllow.ALLOW_ALL;
        cachedSuspend = root.getBoolean("su");
        List<CraftingCpuEntry> entries = new ArrayList<>();
        NBTTagList tags = root.getTagList("e", 10);
        for (int i = 0; i < tags.tagCount(); i++) {
            NBTTagCompound e = tags.getCompoundTagAt(i);
            NBTTagCompound s = e.getCompoundTag("s");
            IAEStack<?> stack = RemoteMe.localize(s) ? IAEStack.fromNBTGeneric(s) : null;
            if (stack == null) {
                continue;
            }
            int reason = e.getInteger("r");
            entries.add(
                new CraftingCpuEntry(
                    stack,
                    e.getLong("x"),
                    e.getLong("a"),
                    e.getLong("p"),
                    reason >= 0 && reason < ScheduledReason.VALUES.length ? ScheduledReason.VALUES[reason]
                        : ScheduledReason.UNDEFINED));
        }
        List<String> following = new ArrayList<>();
        NBTTagList names = root.getTagList("fl", 8);
        for (int i = 0; i < names.tagCount(); i++) {
            following.add(names.getStringTagAt(i));
        }
        send(entries, root.getInteger("ro"), following);
    }

    /** Replaces what the window shows for the processor. */
    private void send(List<CraftingCpuEntry> entries, int remainingOperations, List<String> following) {
        if (!(getInventoryPlayer().player instanceof EntityPlayerMP player)) {
            return;
        }
        try {
            NetworkHandler.instance.sendTo(new PacketCraftingCpuUpdate(entries, true, remainingOperations), player);
            NetworkHandler.instance.sendTo(new PacketCompressedNBT(followers(following)), player);
        } catch (IOException ignored) {}
    }

    private static NBTTagCompound followers(List<String> names) {
        NBTTagCompound result = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (String name : names) {
            list.appendTag(new NBTTagString(name));
        }
        result.setTag("playNameList", list);
        return result;
    }

    private void act(int action, int arg) {
        if (getInventoryPlayer().player instanceof EntityPlayerMP player) {
            RemoteMe.cpuAction(player, view, shown - 1, action, arg);
            nextAt = 0;
        }
    }

    @Override
    public void cancelCrafting() {
        act(RemoteMe.CPU_CANCEL, 0);
        elapsed = -1;
    }

    @Override
    public void suspendCrafting() {
        cachedSuspend = !cachedSuspend;
        act(RemoteMe.CPU_SUSPEND, cachedSuspend ? 1 : 0);
    }

    @Override
    public void changeAllowMode(String msg) {
        CraftingAllow[] modes = CraftingAllow.values();
        int mode;
        try {
            mode = Integer.parseInt(msg);
        } catch (NumberFormatException e) {
            return;
        }
        if (mode < 0 || mode >= modes.length) {
            return;
        }
        allow = modes[mode].next();
        act(RemoteMe.CPU_ALLOW, allow.ordinal());
    }

    /** Followed on the network's server; the notice comes here when the craft is done. */
    @Override
    public void togglePlayerFollowStatus(String name) {
        act(RemoteMe.CPU_FOLLOW, 0);
    }

    /** There is no local network to check rights on; the remote one checks them. */
    @Override
    public void verifyPermissions(SecurityPermissions security, boolean requirePower) {}

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        RemoteMe.closed(view);
    }
}
