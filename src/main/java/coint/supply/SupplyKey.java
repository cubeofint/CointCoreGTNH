package coint.supply;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

/**
 * An item kind as text for the cluster database: by its registry name, not its numeric id, plus damage and NBT. The
 * NBT is written as it is, so the servers must still share their id maps for items that keep ids inside (ME cells).
 */
public final class SupplyKey {

    private SupplyKey() {}

    /** The kind of {@code stack}, its size left out; null when the item has no registry name. */
    public static String encodeItem(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        String name = Item.itemRegistry.getNameForObject(stack.getItem());
        if (name == null) {
            return null;
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", name);
        tag.setShort("Damage", (short) stack.getItemDamage());
        if (stack.hasTagCompound()) {
            tag.setTag(
                "tag",
                stack.getTagCompound()
                    .copy());
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(tag, out);
            return Base64.getEncoder()
                .encodeToString(out.toByteArray());
        } catch (IOException e) {
            return null;
        }
    }

    /** The kind of {@code fluid} (its name and NBT), its amount left out; null when it has no name. */
    public static String encodeFluid(net.minecraftforge.fluids.FluidStack fluid) {
        if (fluid == null || fluid.getFluid() == null) {
            return null;
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(
            "FluidName",
            fluid.getFluid()
                .getName());
        if (fluid.tag != null) {
            tag.setTag("Tag", fluid.tag.copy());
        }
        return write(tag);
    }

    /** One millibucket of the fluid, or null when this server doesn't know it. */
    public static net.minecraftforge.fluids.FluidStack decodeFluid(String payload) {
        NBTTagCompound tag = read(payload);
        if (tag == null) {
            return null;
        }
        net.minecraftforge.fluids.Fluid fluid = net.minecraftforge.fluids.FluidRegistry
            .getFluid(tag.getString("FluidName"));
        if (fluid == null) {
            return null;
        }
        net.minecraftforge.fluids.FluidStack stack = new net.minecraftforge.fluids.FluidStack(fluid, 1);
        if (tag.hasKey("Tag")) {
            stack.tag = tag.getCompoundTag("Tag");
        }
        return stack;
    }

    private static String write(NBTTagCompound tag) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(tag, out);
            return Base64.getEncoder()
                .encodeToString(out.toByteArray());
        } catch (IOException e) {
            return null;
        }
    }

    private static NBTTagCompound read(String payload) {
        try {
            return CompressedStreamTools.readCompressed(
                new ByteArrayInputStream(
                    Base64.getDecoder()
                        .decode(payload)));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** One item of the kind, or null when this server doesn't know it. */
    public static ItemStack decodeItem(String payload) {
        try {
            NBTTagCompound tag = CompressedStreamTools.readCompressed(
                new ByteArrayInputStream(
                    Base64.getDecoder()
                        .decode(payload)));
            Item item = (Item) Item.itemRegistry.getObject(tag.getString("id"));
            if (item == null) {
                return null;
            }
            ItemStack stack = new ItemStack(item, 1, tag.getShort("Damage"));
            if (tag.hasKey("tag")) {
                stack.setTagCompound(tag.getCompoundTag("tag"));
            }
            return stack;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }
}
