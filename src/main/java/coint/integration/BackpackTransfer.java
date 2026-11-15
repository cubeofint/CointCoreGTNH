package coint.integration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.common.DimensionManager;

import coint.trade.TradeItems;

/** External storage of Backpack 2.6.18-GTNH. All snapshots are copies, never live cache entries. */
public final class BackpackTransfer {

    public static final String UID = "backpack-UID";
    private static final String RECEIPT = "cointTransferReceipt";
    private static final int MAX_BAGS = 4096;

    private BackpackTransfer() {}

    private static Object handler() throws IOException {
        try {
            Object handler = Class.forName("de.eydamos.backpack.Backpack")
                .getField("saveFileHandler")
                .get(null);
            if (handler == null) throw new IOException("Backpack storage is not initialized");
            return handler;
        } catch (ReflectiveOperationException e) {
            throw new IOException("Unsupported or missing Backpack storage API", e);
        }
    }

    private static Object call(String method, Class<?>[] types, Object... args) throws IOException {
        Object handler = handler();
        try {
            return handler.getClass()
                .getMethod(method, types)
                .invoke(handler, args);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Backpack " + method + " failed", e);
        }
    }

    public static String uuid(String id) throws IOException {
        try {
            if (!UUID.fromString(id)
                .toString()
                .equals(id)) throw new IllegalArgumentException(id);
            return id;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid Backpack UUID: " + id, e);
        }
    }

    public static NBTTagCompound load(String kind, String id) throws IOException {
        return (NBTTagCompound) ((NBTTagCompound) call("load" + kind, new Class<?>[] { String.class }, uuid(id)))
            .copy();
    }

    public static boolean exists(String kind, String id) throws IOException {
        return (Boolean) call(
            kind.equals("Player") ? "playerSaveExists" : "backpackSaveExists",
            new Class<?>[] { String.class },
            uuid(id));
    }

    public static void save(String kind, String id, NBTTagCompound data) throws IOException {
        call("save" + kind, new Class<?>[] { NBTTagCompound.class, String.class }, data.copy(), uuid(id));
    }

    /** Visit item compounds. Bounded recursion also rejects corrupt NBT. */
    public static void visit(NBTBase value, Visitor visitor) throws IOException {
        visit(value, visitor, 0);
    }

    private static void visit(NBTBase value, Visitor visitor, int depth) throws IOException {
        if (depth > 128) throw new IOException("Backpack NBT nesting limit exceeded");
        if (value instanceof NBTTagCompound tag) {
            visitor.accept(tag);
            for (Object key : new ArrayList<>(tag.func_150296_c()))
                visit(tag.getTag(key.toString()), visitor, depth + 1);
        } else if (value instanceof NBTTagList list) {
            if (list.func_150303_d() == 10) {
                for (int i = 0; i < list.tagCount(); i++) visit(list.getCompoundTagAt(i), visitor, depth + 1);
            } else if (list.func_150303_d() == 9) {
                java.util.List<NBTBase> children = cpw.mods.fml.common.ObfuscationReflectionHelper
                    .getPrivateValue(NBTTagList.class, list, "tagList", "field_74747_a");
                for (NBTBase child : children) visit(child, visitor, depth + 1);
            }
        }
    }

    public interface Visitor {

        void accept(NBTTagCompound tag) throws IOException;
    }

    /** Recursively follow external references, preserving shared references and terminating cycles. */
    public static void capture(NBTBase items, NBTTagCompound bags) throws IOException {
        java.util.Set<String> pending = new java.util.LinkedHashSet<>();
        Visitor collect = tag -> {
            if (!tag.hasKey(UID, 8)) return;
            String id = uuid(tag.getString(UID));
            if (bags.hasKey(id)) return;
            pending.add(id);
            if (pending.size() + bags.func_150296_c()
                .size() > MAX_BAGS) throw new IOException("Too many linked backpacks");
        };
        visit(items, collect);
        while (!pending.isEmpty()) {
            String id = pending.iterator()
                .next();
            pending.remove(id);
            if (bags.hasKey(id)) continue;
            NBTTagCompound data = load("Backpack", id);
            if (!data.hasKey("backpackInventories", 10))
                throw new IOException("Missing or invalid Backpack contents: " + id);
            bags.setTag(id, data);
            visit(data, collect);
        }
    }

    /** A trade receives new storage IDs: existing storage on the receiving node is never overwritten. */
    public static NBTTagCompound tradeSnapshot(NBTTagCompound items) throws IOException {
        NBTTagCompound bags = new NBTTagCompound();
        capture(items, bags);
        if (bags.hasNoTags()) return bags;
        Map<String, String> ids = new LinkedHashMap<>();
        for (Object key : bags.func_150296_c()) ids.put(
            key.toString(),
            UUID.randomUUID()
                .toString());
        Visitor replace = tag -> {
            if (tag.hasKey(UID, 8)) tag.setString(UID, ids.getOrDefault(tag.getString(UID), tag.getString(UID)));
        };
        visit(items, replace);
        NBTTagCompound result = new NBTTagCompound();
        for (Map.Entry<String, String> entry : ids.entrySet()) {
            NBTTagCompound data = bags.getCompoundTag(entry.getKey());
            visit(data, replace);
            data.setString(RECEIPT, entry.getValue());
            TradeItems.portable(data);
            result.setTag(entry.getValue(), data);
        }
        localBags(result); // Fail before escrow if even the source cannot decode every saved item.
        return result;
    }

    /** Decode only: may run in a DB worker and must not touch mod caches or world files. */
    public static NBTTagCompound localBags(NBTTagCompound portable) throws IOException {
        if (portable.func_150296_c()
            .size() > MAX_BAGS) throw new IOException("Too many backpacks in payload");
        NBTTagCompound result = (NBTTagCompound) portable.copy();
        for (Object raw : result.func_150296_c()) {
            String id = uuid(raw.toString());
            if (!result.hasKey(id, 10)) throw new IOException("Invalid backpack payload");
            NBTTagCompound bag = result.getCompoundTag(id);
            if (bag.hasNoTags() || !TradeItems.localize(bag)) throw new IOException("Unknown item in backpack " + id);
            visit(bag, tag -> {
                if (tag.hasKey("id", 2) && tag.hasKey("Count")
                    && net.minecraft.item.ItemStack.loadItemStackFromNBT(tag) == null)
                    throw new IOException("Invalid item in backpack " + id);
            });
        }
        references(result, result);
        return result;
    }

    public static void references(NBTBase items, NBTTagCompound bags) throws IOException {
        visit(items, tag -> {
            if (tag.hasKey(UID, 8) && !bags.hasKey(uuid(tag.getString(UID)), 10))
                throw new IOException("Backpack snapshot lacks contents: " + tag.getString(UID));
        });
    }

    /** Main-thread precheck. A matching receipt means replay: never reset a bag already handed to a player. */
    public static void check(NBTTagCompound bags, boolean trade) throws IOException {
        for (Object raw : bags.func_150296_c()) {
            String id = uuid(raw.toString());
            NBTTagCompound incoming = bags.getCompoundTag(id);
            if (trade && !id.equals(incoming.getString(RECEIPT))) throw new IOException("Missing backpack receipt");
            if (!exists("Backpack", id)) continue;
            NBTTagCompound current = load("Backpack", id);
            if (trade && id.equals(current.getString(RECEIPT))) continue;
            if (!trade && current.equals(incoming)) continue;
            throw new IOException("Conflicting Backpack storage: " + id);
        }
    }

    public static void install(NBTTagCompound bags, boolean trade) throws IOException {
        check(bags, trade);
        for (Object raw : bags.func_150296_c()) {
            String id = raw.toString();
            if (exists("Backpack", id)) continue;
            NBTTagCompound data = bags.getCompoundTag(id);
            save("Backpack", id, data);
        }
        // Even a replay may follow a failed disk write. Flush and verify every requested storage before delivery.
        if (!bags.hasNoTags()) {
            flush();
            for (Object raw : bags.func_150296_c()) {
                String id = raw.toString();
                verify("Backpack", id, load("Backpack", id));
            }
        }
    }

    public static void flush() throws IOException {
        call("queueDirtyFiles", new Class<?>[0]);
        try {
            ThreadedFileIOBase.threadedIOInstance.waitForFinish();
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new IOException("Interrupted saving backpacks", e);
        }
    }

    public static void verify(String kind, String id, NBTTagCompound expected) throws IOException {
        File root = DimensionManager.getCurrentSaveRootDirectory();
        if (root == null) throw new IOException("No active world for backpacks");
        File file = new File(
            root,
            "backpacks/" + (kind.equals("Player") ? "player/" : "backpacks/") + uuid(id) + ".dat");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            if (!expected.equals(CompressedStreamTools.readCompressed(input)))
                throw new IOException("Backpack save verification failed: " + id);
        }
    }
}
