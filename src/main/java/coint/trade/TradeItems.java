package coint.trade;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Items written for another server of the network. Numeric item ids differ between servers, so every item in them,
 * also items inside items (backpacks, toolboxes), carries its registry name ("in") and gets this server's id back
 * when read. An item this server does not know makes the whole list unreadable here.
 */
public final class TradeItems {

    private static final int TAG_SHORT = 2, TAG_COMPOUND = 10;

    private TradeItems() {}

    public static byte[] write(List<ItemStack> stacks) {
        NBTTagList list = new NBTTagList();
        for (ItemStack stack : stacks) {
            NBTTagCompound tag = stack.writeToNBT(new NBTTagCompound());
            // The stack's own count is a byte.
            tag.setInteger("C", stack.stackSize);
            portable(tag);
            list.appendTag(tag);
        }
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("l", list);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CompressedStreamTools.writeCompressed(root, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The items, or null when one of them is unknown on this server (or the data is broken). */
    public static List<ItemStack> read(byte[] data) {
        List<ItemStack> stacks = new ArrayList<>();
        if (data == null || data.length == 0) {
            return stacks;
        }
        NBTTagCompound root;
        try {
            root = CompressedStreamTools.readCompressed(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        NBTTagList list = root.getTagList("l", TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            if (!localize(tag)) {
                return null;
            }
            ItemStack stack = ItemStack.loadItemStackFromNBT(tag);
            if (stack == null || stack.getItem() == null) {
                return null;
            }
            stack.stackSize = tag.getInteger("C");
            stacks.add(stack);
        }
        return stacks;
    }

    /** Whether every item in {@code data} is known here. */
    public static boolean deliverable(byte[] data) {
        return read(data) != null;
    }

    /** An item written by ItemStack: a short id and a count. */
    private static boolean isStack(NBTTagCompound tag) {
        return tag.hasKey("id", TAG_SHORT) && tag.hasKey("Count");
    }

    private static void portable(NBTTagCompound tag) {
        if (isStack(tag)) {
            Item item = Item.getItemById(tag.getShort("id"));
            String name = item == null ? null : Item.itemRegistry.getNameForObject(item);
            if (name != null) {
                tag.setString("in", name);
            }
        }
        for (Object key : tag.func_150296_c()) {
            children((String) key, tag.getTag((String) key), true);
        }
    }

    /** False when an item (or one inside it) is unknown here. */
    private static boolean localize(NBTTagCompound tag) {
        if (tag.hasKey("in", 8)) {
            Item item = (Item) Item.itemRegistry.getObject(tag.getString("in"));
            if (item == null) {
                return false;
            }
            tag.setShort("id", (short) Item.getIdFromItem(item));
            tag.removeTag("in");
        }
        for (Object key : new ArrayList<Object>(tag.func_150296_c())) {
            if (!children((String) key, tag.getTag((String) key), false)) {
                return false;
            }
        }
        return true;
    }

    private static boolean children(String key, NBTBase child, boolean toPortable) {
        if (child instanceof NBTTagCompound compound) {
            if (toPortable) {
                portable(compound);
                return true;
            }
            return localize(compound);
        }
        if (child instanceof NBTTagList list && list.func_150303_d() == TAG_COMPOUND) {
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound compound = list.getCompoundTagAt(i);
                if (toPortable) {
                    portable(compound);
                } else if (!localize(compound)) {
                    return false;
                }
            }
        }
        return true;
    }
}
