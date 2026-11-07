package coint.cluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;

/**
 * The items of a network on another server, for AE2's own terminal window: filled from the snapshots that server
 * writes, with every change told to the window. Nothing can be taken out here at once (taking goes through the
 * database, see {@link ContainerRemoteMeNative}); items put in are handed to {@code inserter} and count as taken.
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
final class RemoteItemMonitor implements IMEMonitor<IAEItemStack> {

    private IItemList<IAEItemStack> list = AEApi.instance()
        .storage()
        .createItemList();
    private final Map<IMEMonitorHandlerReceiver, Object> listeners = new HashMap<>();
    /** Takes items put in; false when they could not be sent. */
    private final Predicate<ItemStack> inserter;

    RemoteItemMonitor(Predicate<ItemStack> inserter) {
        this.inserter = inserter;
    }

    /** A new snapshot (the "l" list of {@link RemoteMe}'s format): the window hears of what changed. */
    void update(NBTTagCompound snapshot) {
        IItemList<IAEItemStack> next = AEApi.instance()
            .storage()
            .createItemList();
        NBTTagList items = snapshot.getTagList("l", 10);
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound e = items.getCompoundTagAt(i);
            Item item = (Item) Item.itemRegistry.getObject(e.getString("i"));
            if (item == null) {
                continue;
            }
            ItemStack stack = new ItemStack(item, 1, e.getShort("d"));
            if (e.hasKey("t")) {
                stack.setTagCompound(e.getCompoundTag("t"));
            }
            IAEItemStack ais = AEItemStack.create(stack);
            if (ais == null) {
                continue;
            }
            ais.setStackSize(e.getLong("n"));
            ais.setCraftable(e.getBoolean("c"));
            next.add(ais);
        }
        List<IAEItemStack> changed = new ArrayList<>();
        for (IAEItemStack now : next) {
            IAEItemStack before = list.findPrecise(now);
            if (before == null || before.getStackSize() != now.getStackSize()
                || before.isCraftable() != now.isCraftable()) {
                changed.add(now.copy());
            }
        }
        for (IAEItemStack before : list) {
            if (next.findPrecise(before) == null) {
                changed.add(before.copy());
            }
        }
        list = next;
        if (!changed.isEmpty()) {
            for (IMEMonitorHandlerReceiver l : new ArrayList<>(listeners.keySet())) {
                l.postChange(this, changed, null);
            }
        }
    }

    @Override
    public IItemList<IAEItemStack> getStorageList() {
        return list;
    }

    @Override
    public void addListener(IMEMonitorHandlerReceiver l, Object verificationToken) {
        listeners.put(l, verificationToken);
    }

    @Override
    public void removeListener(IMEMonitorHandlerReceiver l) {
        listeners.remove(l);
    }

    @Override
    public IAEItemStack injectItems(IAEItemStack input, Actionable type, BaseActionSource src) {
        if (input == null || input.getStackSize() <= 0) {
            return null;
        }
        if (type == Actionable.SIMULATE) {
            return null;
        }
        ItemStack stack = input.getItemStack();
        return stack != null && inserter.test(stack) ? null : input;
    }

    @Override
    public IAEItemStack extractItems(IAEItemStack request, Actionable mode, BaseActionSource src) {
        return null;
    }

    @Override
    public IItemList<IAEItemStack> getAvailableItems(IItemList out, int iteration) {
        for (IAEItemStack s : list) {
            out.add(s);
        }
        return out;
    }

    @Override
    public IItemList<IAEItemStack> getAvailableItems(IItemList out) {
        return getAvailableItems(out, 0);
    }

    @Override
    public IAEItemStack getAvailableItem(IAEItemStack request, int iteration) {
        IAEItemStack found = list.findPrecise(request);
        return found == null ? null : found.copy();
    }

    @Override
    public IAEStackType<?> getStackType() {
        return AEItemStackType.ITEM_STACK_TYPE;
    }

    @Override
    public AccessRestriction getAccess() {
        return AccessRestriction.READ_WRITE;
    }

    @Override
    public boolean isPrioritized(IAEItemStack input) {
        return false;
    }

    @Override
    public boolean canAccept(IAEItemStack input) {
        return true;
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public int getSlot() {
        return 0;
    }

    @Override
    public boolean validForPass(int i) {
        return true;
    }
}
