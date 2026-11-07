package coint.cluster;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.BufferOverflowException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.google.common.collect.ImmutableSet;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.storage.data.IAEStack;
import appeng.container.implementations.ContainerCPUTable;
import appeng.container.implementations.ContainerCraftConfirm;
import appeng.container.implementations.CraftingCPUStatus;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.PacketCraftingCPUTableUpdate;
import appeng.core.sync.packets.PacketMEInventoryUpdate;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import coint.CointCore;

/**
 * AE2's crafting plan window for an order to a network on another server. The plan, its size and the crafting
 * processors come from the remote network; "Start" sends the plan back there with the processor picked, "Cancel" goes
 * back to the terminal as in AE2.
 */
public class ContainerRemoteCraftConfirm extends ContainerCraftConfirm implements RemoteMeWindow {

    /** The processor table looks its picks up among the local network's processors; there are none here. */
    private static final Field LAST_CPU_SET;

    static {
        try {
            LAST_CPU_SET = ContainerCPUTable.class.getDeclaredField("lastCpuSet");
            LAST_CPU_SET.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private final RemoteMe.View view;
    private ItemStack kind;
    /** The PLAN operation whose result this window waits for, or -1. */
    private long awaited = -1;
    /** Set while no PLAN id is known yet: the result for this order may still be shown. */
    private boolean ordered;
    /** The plan that can be started, or -1. */
    private long planId = -1;

    ContainerRemoteCraftConfirm(InventoryPlayer inventory, RemoteTerminalObject host, RemoteMe.View view) {
        super(inventory, host);
        this.view = view;
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

    /** The order this window shows the plan for. */
    void ordered(ItemStack kind) {
        this.kind = kind;
        this.ordered = true;
    }

    /** The id of the PLAN operation sent for this order. */
    void awaiting(long opId) {
        this.awaited = opId;
    }

    boolean waitsFor(long opId) {
        return ordered && awaited == opId;
    }

    /** The order couldn't be sent to the remote network. */
    void failed(String text) {
        ordered = false;
        tell(text);
        switchToOriginalGUI();
    }

    /** The remote network's plan: shown in the window as AE2 shows its own. */
    void applyPlan(long opId, boolean startable, byte[] data) {
        ordered = false;
        NBTTagCompound root = null;
        if (data != null && data.length > 0) {
            try {
                root = CompressedStreamTools.readCompressed(new ByteArrayInputStream(data));
            } catch (IOException e) {
                root = null;
            }
        }
        if (root == null) {
            failed("§cУдалённая сеть не смогла рассчитать заказ: сеть недоступна или нет шаблона");
            return;
        }
        if (!(getInventoryPlayer().player instanceof EntityPlayerMP player)) {
            return;
        }
        simulation = root.getBoolean("s") || !startable;
        // AE2 picks a processor only once the size is known.
        bytesUsed = Math.max(1, root.getLong("b"));
        errorMessage = root.getString("e");
        planId = simulation ? -1 : opId;

        List<CraftingCPUStatus> cpus = new ArrayList<>();
        NBTTagList cpuTags = root.getTagList("cpus", 10);
        for (int i = 0; i < cpuTags.tagCount(); i++) {
            NBTTagCompound c = cpuTags.getCompoundTagAt(i);
            if (c.hasKey("st")) {
                NBTTagCompound status = c.getCompoundTag("st");
                RemoteMe.localizeCpu(status);
                cpus.add(new CraftingCPUStatus(status));
            }
        }
        cpuTable.handleCPUUpdate(cpus.toArray(new CraftingCPUStatus[0]));

        try {
            PacketMEInventoryUpdate storage = new PacketMEInventoryUpdate((byte) 0);
            PacketMEInventoryUpdate pending = new PacketMEInventoryUpdate((byte) 1);
            PacketMEInventoryUpdate missing = simulation ? new PacketMEInventoryUpdate((byte) 2) : null;
            NBTTagList lines = root.getTagList("l", 10);
            for (int i = 0; i < lines.tagCount(); i++) {
                NBTTagCompound line = lines.getCompoundTagAt(i);
                IAEStack<?> stack = stackOf(line);
                if (stack == null) {
                    continue;
                }
                append(storage, stack, line.getLong("x"));
                append(pending, stack, line.getLong("c"));
                if (missing != null) {
                    append(missing, stack, line.getLong("m"));
                }
            }
            NetworkHandler.instance.sendTo(storage, player);
            NetworkHandler.instance.sendTo(pending, player);
            if (missing != null) {
                NetworkHandler.instance.sendTo(missing, player);
            }
            NetworkHandler.instance.sendTo(new PacketCraftingCPUTableUpdate(cpus), player);
        } catch (IOException | BufferOverflowException e) {
            CointCore.LOG.warn("[RemoteMe] Plan too large to show in full", e);
        }
        if (root.getBoolean("cut")) {
            tell("§eПлан слишком большой, показана только его часть.");
        }
    }

    private static void append(PacketMEInventoryUpdate packet, IAEStack<?> stack, long amount) throws IOException {
        if (amount > 0) {
            IAEStack<?> part = stack.copy();
            part.reset();
            part.setStackSize(amount);
            packet.appendItem(part);
        }
    }

    private static IAEStack<?> stackOf(NBTTagCompound line) {
        if (line.hasKey("f")) {
            Fluid fluid = FluidRegistry.getFluid(line.getString("f"));
            return fluid == null ? null : AEFluidStack.create(new FluidStack(fluid, 1));
        }
        Item item = (Item) Item.itemRegistry.getObject(line.getString("i"));
        if (item == null) {
            return null;
        }
        ItemStack stack = new ItemStack(item, 1, line.getShort("d"));
        if (line.hasKey("t")) {
            stack.setTagCompound(line.getCompoundTag("t"));
        }
        return AEItemStack.create(stack);
    }

    private void tell(String text) {
        getInventoryPlayer().player.addChatMessage(new ChatComponentText(text));
    }

    /** The picked processor is one of the remote network's; its numbers come from the table, not a local one. */
    @Override
    public void onCPUUpdate(ICraftingCPU cpu) {
        CraftingCPUStatus picked = cpuTable == null ? null : cpuTable.getSelectedCPU();
        if (picked == null) {
            myName = "";
            cpuBytesAvail = 0;
            cpuCoProcessors = 0;
        } else {
            myName = picked.getName() == null ? "" : picked.getName();
            cpuBytesAvail = picked.getStorage();
            cpuCoProcessors = (int) picked.getCoprocessors();
        }
    }

    @Override
    public void startJob(boolean followCraft) {
        EntityPlayer player = getInventoryPlayer().player;
        if (planId < 0 || simulation || kind == null || !(player instanceof EntityPlayerMP mp)) {
            return;
        }
        CraftingCPUStatus picked = cpuTable.getSelectedCPU();
        long plan = planId;
        planId = -1;
        RemoteMe.startPlan(mp, view, kind, plan, picked == null ? -1 : picked.getSerial() - 1);
        switchToOriginalGUI();
    }

    /** Patterns are optimized on the network's own server; not from here. */
    @Override
    public void optimizePatterns() {}

    /** There is no local network to check rights on; the remote one checks them. */
    @Override
    public void verifyPermissions(SecurityPermissions security, boolean requirePower) {}

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        RemoteMe.closed(view);
    }
}
