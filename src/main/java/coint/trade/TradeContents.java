package coint.trade;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import appeng.api.AEApi;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStackType;
import coint.integration.BackpackTransfer;
import coint.util.NbtAuditLimit;

/** Read-only, bounded audit. Remote bags always come from escrow, never this node's unrelated cache. */
final class TradeContents {

    private static final int MAX_DEPTH = 12, MAX_NODES = 30000, MAX_LINES = 4096, MAX_TEXT = 500000;
    private final StringBuilder text = new StringBuilder();
    private final NBTTagCompound bags;
    private final Set<String> seenBags = new HashSet<>();
    private int nodes, lines;
    private boolean truncated;

    private TradeContents(NBTTagCompound bags) {
        this.bags = bags;
    }

    static String live(List<ItemStack> stacks) {
        return new TradeContents(null).describe(stacks);
    }

    static String remote(List<ItemStack> stacks, byte[] escrow) {
        if (stacks == null) return "Не удалось прочитать предметы из снимка обмена.";
        try {
            if (escrow != null && escrow.length > 4 * 1024 * 1024) throw new IOException("Trade audit input too large");
            NBTTagCompound root = escrow == null ? new NBTTagCompound()
                : CompressedStreamTools.func_152457_a(escrow, new NBTSizeTracker(NbtAuditLimit.MAX_BYTES));
            NbtAuditLimit.check(root);
            return new TradeContents(BackpackTransfer.localBags(root.getCompoundTag("backpacks"))).describe(stacks);
        } catch (IOException | RuntimeException e) {
            return "Не удалось прочитать содержимое из снимка обмена (" + e.getClass()
                .getSimpleName() + ").";
        }
    }

    private String describe(List<ItemStack> stacks) {
        if (stacks == null) return "Предметы недоступны для проверки.";
        for (int i = 0; i < stacks.size() && !truncated; i++) {
            ItemStack stack = stacks.get(i);
            if (stack == null || stack.getItem() == null) continue;
            int savedLength = text.length();
            int before = lines;
            add(
                0,
                "Предмет " + (i + 1)
                    + ": "
                    + item(stack, stack.stackSize)
                    + (stack.stackSize > 1 ? " (содержимое на одну единицу)" : ""));
            inspect(stack, 1);
            // Ordinary items should not clutter the extra inventory report.
            if (lines == before + 1 && !truncated) {
                text.setLength(savedLength);
                lines = before;
            }
        }
        if (truncated) text.append("\n[Достигнут предел аудита; часть вложенного содержимого не показана.]\n");
        return text.toString();
    }

    private void inspect(ItemStack stack, int depth) {
        try {
            NbtAuditLimit.check(stack.getTagCompound());
            contents(stack.copy(), depth);
        } catch (IOException | RuntimeException | LinkageError e) {
            add(
                depth,
                "Содержимое недоступно: " + e.getClass()
                    .getSimpleName());
        }
    }

    private void contents(ItemStack stack, int depth) throws IOException {
        if (!visit(depth)) return;
        NBTTagCompound tag = stack.getTagCompound();
        if (tag != null && tag.hasKey(BackpackTransfer.UID, 8)) {
            backpack(tag.getString(BackpackTransfer.UID), depth);
            return;
        }
        // Storage APIs run on copies: reading some cells initializes/canonicalizes their NBT.
        if (cell(stack, depth)) return;
        if (tag == null) return;
        if (tag.hasKey("clusteritems", 10)) {
            NBTTagList entries = tag.getCompoundTag("clusteritems")
                .getTagList("items", 10);
            add(depth, "Кластер материи:");
            if (entries.tagCount() == 0) add(depth + 1, "пусто");
            for (int i = 0; i < entries.tagCount() && !truncated; i++) {
                NBTTagCompound entry = entries.getCompoundTagAt(i);
                child(entry.getCompoundTag("item"), entry.getLong("count"), depth + 1, "позиция " + (i + 1));
            }
            return;
        }
        scan(tag, depth, "NBT");
    }

    private void backpack(String uid, int depth) throws IOException {
        BackpackTransfer.uuid(uid);
        add(depth, "Рюкзак " + uid + ":");
        if (!seenBags.add(uid)) {
            add(depth + 1, "ссылка на уже указанный рюкзак; повторно не суммируется");
            return;
        }
        NBTTagCompound data;
        if (bags != null) {
            if (!bags.hasKey(uid, 10)) {
                add(depth + 1, "содержимое отсутствует в снимке обмена");
                return;
            }
            data = bags.getCompoundTag(uid);
        } else {
            if (!BackpackTransfer.exists("Backpack", uid)) {
                add(depth + 1, "внешний файл хранилища не найден");
                return;
            }
            data = BackpackTransfer.loadForAudit("Backpack", uid);
        }
        if (!data.hasKey("backpackInventories", 10)) {
            add(depth + 1, "неизвестный формат хранилища");
            return;
        }
        NBTTagCompound inventories = data.getCompoundTag("backpackInventories");
        int before = lines;
        // pickupItems, recipes AND craftingGrid use ghost slots/templates, not transferred inventory.
        if (inventories.hasKey("backpack", 9)) scan(inventories.getTag("backpack"), depth + 1, "backpack");
        if (lines == before) add(depth + 1, "пусто");
    }

    @SuppressWarnings("unchecked")
    private boolean cell(ItemStack stack, int depth) throws IOException {
        if (!AEApi.instance()
            .registries()
            .cell()
            .isCellHandled(stack)) return false;
        add(depth, "МЭ-ячейка:");
        int before = lines;
        IMEInventory<IAEItemStack> items = AEApi.instance()
            .registries()
            .cell()
            .getCellInventory(stack, null, AEItemStackType.ITEM_STACK_TYPE);
        if (items != null) {
            IItemList<IAEItemStack> list = AEItemStackType.ITEM_STACK_TYPE.createList();
            items.getAvailableItems(list);
            for (IAEItemStack entry : list) {
                if (truncated) break;
                if (entry.getStackSize() <= 0) continue;
                ItemStack stored = entry.getItemStack();
                add(depth + 1, item(stored, entry.getStackSize()));
                inspect(stored, depth + 2);
            }
        }
        IMEInventory<IAEFluidStack> fluids = AEApi.instance()
            .registries()
            .cell()
            .getCellInventory(stack, null, AEFluidStackType.FLUID_STACK_TYPE);
        if (fluids != null) {
            IItemList<IAEFluidStack> list = AEFluidStackType.FLUID_STACK_TYPE.createList();
            fluids.getAvailableItems(list);
            for (IAEFluidStack entry : list) {
                if (truncated) break;
                if (entry.getStackSize() <= 0) continue;
                FluidStack fluid = entry.getFluidStack();
                add(
                    depth + 1,
                    entry.getStackSize() + " mB "
                        + clean(fluid.getLocalizedName(), 160)
                        + " ("
                        + fluid.getFluid()
                            .getName()
                        + ")");
            }
        }
        if (lines == before) add(depth + 1, items == null && fluids == null ? "формат ячейки недоступен" : "пусто");
        return true;
    }

    /** Recognize serialized item entries, without treating enchantments, filters or recipe ghosts as inventory. */
    private void scan(NBTBase value, int depth, String path) throws IOException {
        if (!visit(depth)) return;
        if (value instanceof NBTTagCompound compound) {
            if ((compound.hasKey("id", 99) || compound.hasKey("id", 8) || compound.hasKey("in", 8))
                && compound.hasKey("Count", 99)) {
                long count = compound.hasKey("Cnt", 99) ? compound.getLong("Cnt") : compound.getLong("Count");
                child(compound, count, depth, path);
                return;
            }
            List<String> keys = new ArrayList<>();
            for (Object key : compound.func_150296_c()) keys.add(key.toString());
            Collections.sort(keys);
            for (String key : keys) {
                if (truncated) return;
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.contains("filter") || lower.contains("recipe")
                    || lower.contains("config")
                    || lower.equals("display")
                    || lower.equals("ench")
                    || lower.equals("pickupitems")) continue;
                scan(compound.getTag(key), depth + 1, path + "/" + clean(key, 40));
            }
        } else if (value instanceof NBTTagList list && list.func_150303_d() == 10) {
            for (int i = 0; i < list.tagCount() && !truncated; i++)
                scan(list.getCompoundTagAt(i), depth + 1, path + "[" + i + "]");
        }
    }

    private void child(NBTTagCompound tag, long count, int depth, String path) throws IOException {
        if (count <= 0 || truncated) return;
        NBTTagCompound local = (NBTTagCompound) tag.copy();
        if (local.hasKey("in", 8)) {
            Item item = (Item) Item.itemRegistry.getObject(local.getString("in"));
            if (item != null) local.setShort("id", (short) Item.getIdFromItem(item));
        } else if (local.hasKey("id", 8)) {
            Item item = (Item) Item.itemRegistry.getObject(local.getString("id"));
            if (item != null) local.setShort("id", (short) Item.getIdFromItem(item));
        }
        // AE and other storage formats use a separate long count; Count may be zero or overflow a byte.
        local.setByte("Count", (byte) 1);
        ItemStack stored = ItemStack.loadItemStackFromNBT(local);
        if (stored == null || stored.getItem() == null) {
            add(depth, path + ": " + count + "x [неизвестный предмет]");
            return;
        }
        add(depth, path + ": " + item(stored, count));
        inspect(stored, depth + 1);
    }

    private boolean visit(int depth) {
        if (truncated) return false;
        if (++nodes > MAX_NODES || depth > MAX_DEPTH) {
            truncated = true;
            return false;
        }
        return true;
    }

    private void add(int depth, String line) {
        if (truncated) return;
        if (++lines > MAX_LINES || text.length() + line.length() + 32 > MAX_TEXT) {
            truncated = true;
            return;
        }
        for (int i = 0; i < Math.min(depth, MAX_DEPTH); i++) text.append("  ");
        text.append(line)
            .append('\n');
    }

    static String clean(String value, int max) {
        if (value == null) return "?";
        String result = value.replaceAll("§.", "")
            .replaceAll("[\\p{Cntrl}\\s]+", " ")
            .trim();
        return result.length() <= max ? result : result.substring(0, max) + "…";
    }

    static String item(ItemStack stack, long count) {
        String id = Item.itemRegistry.getNameForObject(stack.getItem());
        String name;
        try {
            name = clean(stack.getDisplayName(), 160);
        } catch (RuntimeException | LinkageError e) {
            name = id;
        }
        return count + "x "
            + name
            + " ("
            + id
            + "@"
            + stack.getItemDamage()
            + (stack.hasTagCompound() ? " +nbt" : "")
            + ")";
    }
}
