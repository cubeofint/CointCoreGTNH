package coint.integration.discord;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Entity-only provenance: never changes item NBT or vanilla stack merging. */
final class DropOrigins {

    private static final String KEY = "cointcore_drop_sources";
    final int count;
    final LinkedHashMap<String, Integer> sources = new LinkedHashMap<>();

    private DropOrigins(int count) {
        this.count = Math.max(0, count);
    }

    static boolean tracked(EntityItem item) {
        return item.getEntityData()
            .hasKey(KEY, 9);
    }

    static DropOrigins read(EntityItem item) {
        DropOrigins result = new DropOrigins(item.isDead ? 0 : item.getEntityItem().stackSize);
        NBTTagList list = item.getEntityData()
            .getTagList(KEY, 10);
        int room = result.count;
        for (int i = 0; i < Math.min(64, list.tagCount()) && room > 0; i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            String name = tag.getString("name");
            int n = Math.min(room, Math.max(0, tag.getInteger("count")));
            if (name.isEmpty() || name.length() > 64 || n == 0) continue;
            result.sources.merge(name, n, Integer::sum);
            room -= n;
        }
        return result;
    }

    static void thrown(EntityItem item, String name) {
        DropOrigins result = new DropOrigins(item.getEntityItem().stackSize);
        result.sources.put(name, result.count);
        result.write(item);
    }

    void write(EntityItem item) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<String, Integer> entry : sources.entrySet()) if (entry.getValue() > 0) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("name", entry.getKey());
            tag.setInteger("count", entry.getValue());
            list.appendTag(tag);
        }
        if (list.tagCount() == 0) item.getEntityData()
            .removeTag(KEY);
        else item.getEntityData()
            .setTag(KEY, list);
    }

    /** Allocate untracked world loot first, then player sources in their recorded order. */
    LinkedHashMap<String, Integer> take(int amount) {
        int tracked = 0;
        for (int value : sources.values()) tracked += value;
        int remaining = Math.max(0, Math.min(count, amount) - (count - tracked));
        LinkedHashMap<String, Integer> picked = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : sources.entrySet()) {
            int n = Math.min(remaining, entry.getValue());
            if (n > 0) picked.put(entry.getKey(), n);
            entry.setValue(entry.getValue() - n);
            remaining -= n;
        }
        return picked;
    }

    static void merged(EntityItem a, EntityItem b, DropOrigins beforeA, DropOrigins beforeB) {
        int afterA = a.isDead ? 0 : a.getEntityItem().stackSize;
        int afterB = b.isDead ? 0 : b.getEntityItem().stackSize;
        if (afterA > beforeA.count && afterB < beforeB.count) {
            transfer(a, b, beforeA, beforeB, Math.min(afterA - beforeA.count, beforeB.count - afterB));
        } else if (afterB > beforeB.count && afterA < beforeA.count) {
            transfer(b, a, beforeB, beforeA, Math.min(afterB - beforeB.count, beforeA.count - afterA));
        }
    }

    private static void transfer(EntityItem to, EntityItem from, DropOrigins dest, DropOrigins source, int amount) {
        for (Map.Entry<String, Integer> entry : source.take(amount)
            .entrySet()) dest.sources.merge(entry.getKey(), entry.getValue(), Integer::sum);
        dest.write(to);
        source.write(from);
    }
}
