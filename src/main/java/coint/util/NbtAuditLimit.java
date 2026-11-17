package coint.util;

import java.io.IOException;
import java.util.List;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import cpw.mods.fml.common.ObfuscationReflectionHelper;

/** Bound allocation BEFORE copying audit inputs. Does not mutate the live/cache NBT. */
public final class NbtAuditLimit {

    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private int nodes;
    private long bytes;

    private NbtAuditLimit() {}

    public static void check(NBTBase value) throws IOException {
        new NbtAuditLimit().visit(value, 0);
    }

    private void visit(NBTBase value, int depth) throws IOException {
        if (value == null) return;
        if (depth > 64 || ++nodes > 100000) throw new IOException("Audit input nesting/node limit exceeded");
        bytes += 32;
        if (value instanceof NBTTagCompound compound) {
            for (Object key : compound.func_150296_c()) {
                bytes += 2L * key.toString()
                    .length();
                visit(compound.getTag(key.toString()), depth + 1);
            }
        } else if (value instanceof NBTTagList list) {
            List<NBTBase> children = ObfuscationReflectionHelper
                .getPrivateValue(NBTTagList.class, list, "tagList", "field_74747_a");
            for (NBTBase child : children) visit(child, depth + 1);
        } else if (value instanceof NBTTagByteArray array) bytes += array.func_150292_c().length;
        else if (value instanceof NBTTagIntArray array) bytes += 4L * array.func_150302_c().length;
        else if (value instanceof NBTTagString string) bytes += 2L * string.func_150285_a_()
            .length();
        if (bytes > MAX_BYTES) throw new IOException("Audit input size limit exceeded");
    }
}
