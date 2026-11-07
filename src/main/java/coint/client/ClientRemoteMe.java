package coint.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import coint.cluster.ContainerRemoteMe;
import coint.cluster.RemoteMe;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** What the server says about the open remote ME window: its title, state and what the network holds. */
@SideOnly(Side.CLIENT)
public final class ClientRemoteMe {

    public static final class Entry {

        public final ItemStack stack;
        public final long count;
        private String search;

        Entry(ItemStack stack, long count) {
            this.stack = stack;
            this.count = count;
        }

        /** Lower-case display name, for the search field. */
        public String search() {
            if (search == null) {
                try {
                    search = stack.getDisplayName()
                        .toLowerCase();
                } catch (RuntimeException e) {
                    search = "";
                }
            }
            return search;
        }
    }

    public static int windowId = -1;
    public static String title = "", status = "";
    /** Network state from the snapshot, or -1 before the first one. */
    public static int state = -1;
    public static List<Entry> entries = Collections.emptyList();
    /** Bumped whenever {@link #entries} change, so the window re-filters. */
    public static int revision;

    private static long assemblingVersion = -1;
    private static ByteArrayOutputStream assembling;
    private static int nextPart;

    private ClientRemoteMe() {}

    /** A new window starts empty. */
    public static void reset() {
        windowId = -1;
        title = "";
        status = "";
        state = -1;
        entries = Collections.emptyList();
        revision++;
        assembling = null;
    }

    public static void apply(int window, String newTitle, String newStatus, long version, int part, int parts,
        byte[] data) {
        Minecraft.getMinecraft()
            .func_152344_a(() -> applyOnMain(window, newTitle, newStatus, version, part, parts, data));
    }

    private static void applyOnMain(int window, String newTitle, String newStatus, long version, int part, int parts,
        byte[] data) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || !(mc.thePlayer.openContainer instanceof ContainerRemoteMe container)
            || container.windowId != window) {
            return;
        }
        if (windowId != window) {
            windowId = window;
            state = -1;
            entries = Collections.emptyList();
            revision++;
            assembling = null;
        }
        title = newTitle;
        status = newStatus;
        if (parts == 0) {
            return;
        }
        if (part == 0) {
            assembling = new ByteArrayOutputStream();
            assemblingVersion = version;
            nextPart = 0;
        }
        if (assembling == null || version != assemblingVersion || part != nextPart) {
            assembling = null;
            return;
        }
        assembling.write(data, 0, data.length);
        nextPart++;
        if (nextPart == parts) {
            byte[] all = assembling.toByteArray();
            assembling = null;
            read(all);
        }
    }

    private static void read(byte[] bytes) {
        NBTTagCompound root;
        try {
            root = CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return;
        }
        state = root.getByte("s");
        NBTTagList list = root.getTagList("l", 10);
        List<Entry> result = new ArrayList<>(list.tagCount());
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound e = list.getCompoundTagAt(i);
            Item item = (Item) Item.itemRegistry.getObject(e.getString("i"));
            if (item == null) {
                continue;
            }
            ItemStack stack = new ItemStack(item, 1, e.getShort("d"));
            if (e.hasKey("t")) {
                stack.setTagCompound(e.getCompoundTag("t"));
            }
            result.add(new Entry(stack, e.getLong("n")));
        }
        result.sort((a, b) -> Long.compare(b.count, a.count));
        entries = result;
        revision++;
    }

    /** The line under the title: the server's status, else what the snapshot says. */
    public static String statusLine() {
        if (!status.isEmpty()) {
            return status;
        }
        return switch (state) {
            case -1 -> "§7Загрузка…";
            case RemoteMe.STATE_MISSING -> "§cСеть не найдена";
            case RemoteMe.STATE_UNPOWERED -> "§cСеть без энергии";
            case RemoteMe.STATE_NO_WAP -> "§cТочка доступа выключена";
            default -> "";
        };
    }
}
