package coint.commands.kit;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import coint.util.NbtAuditLimit;
import cpw.mods.fml.common.registry.GameData;

/** Immutable provenance, kept outside mutable item data while a stack is alive. */
public final class KitOrigin {

    public static final String TAG = "CointCoreKitOrigin";

    private final String kit;
    private final String recipient;
    private final String recipientId;

    private KitOrigin(String kit, String recipient, String recipientId) {
        this.kit = kit;
        this.recipient = recipient;
        this.recipientId = recipientId;
    }

    public static KitOrigin read(NBTTagCompound parent) {
        if (parent == null || !parent.hasKey(TAG, 10)) {
            return null;
        }
        NBTTagCompound tag = parent.getCompoundTag(TAG);
        String kit = tag.getString("Kit");
        String recipient = tag.getString("Recipient");
        String recipientId = tag.getString("RecipientId");
        if (kit.isEmpty() || recipient.isEmpty() || recipientId.isEmpty()) {
            return null;
        }
        return new KitOrigin(kit, recipient, recipientId);
    }

    public static ItemStack issuedCopy(ItemStack template, String kit, EntityPlayer player) throws IOException {
        NbtAuditLimit.check(template.getTagCompound());
        ItemStack copy = template.copy();
        KitOrigin origin = new KitOrigin(
            kit,
            player.getCommandSenderName(),
            player.getUniqueID()
                .toString());
        origin.markChestContents(copy.getItem(), copy.getTagCompound(), 0, new int[1]);
        ((KitMarkedStack) (Object) copy).cointcore$setKitOrigin(origin);
        return copy;
    }

    /** Only runs on the issued copy, never when reopening a chest with later player deposits. */
    private void markChestContents(Item item, NBTTagCompound data, int depth, int[] marked) throws IOException {
        String id = GameData.getItemRegistry()
            .getNameForObject(item);
        if (data == null || !("avaritiaddons:CompressedChest".equalsIgnoreCase(id)
            || "avaritiaddons:InfinityChest".equalsIgnoreCase(id))) return;
        if (depth > 16) throw new IOException("Kit chest nesting limit exceeded");
        NBTTagList contents = data.getTagList("Contents", 10);
        for (int i = 0; i < contents.tagCount(); i++) {
            NBTTagCompound stored = contents.getCompoundTagAt(i);
            int slot = stored.getShort("Slot");
            if (slot < 0 || slot >= 243) continue;
            Item child = stored.hasKey("id", 8) ? (Item) Item.itemRegistry.getObject(stored.getString("id"))
                : Item.getItemById(stored.getShort("id"));
            if (child == null) continue;
            if (++marked[0] > 10000) throw new IOException("Kit chest item limit exceeded");
            if (stored.hasKey("tag") && !stored.hasKey("tag", 10))
                throw new IOException("Invalid item NBT in kit chest");
            NBTTagCompound childData = stored.getCompoundTag("tag");
            // Edit only provenance: preserve Slot, Count, InfinityChest's intCount and all mod-specific data.
            write(stored);
            write(childData);
            stored.setTag("tag", childData);
            markChestContents(child, childData, depth + 1, marked);
        }
    }

    public static KitOrigin get(ItemStack stack) {
        return stack == null ? null : ((KitMarkedStack) (Object) stack).cointcore$getKitOrigin();
    }

    public void write(NBTTagCompound parent) {
        NBTTagCompound existing = parent.getCompoundTag(TAG);
        if (kit.equals(existing.getString("Kit")) && recipient.equals(existing.getString("Recipient"))
            && recipientId.equals(existing.getString("RecipientId"))) {
            return;
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("Kit", kit);
        tag.setString("Recipient", recipient);
        tag.setString("RecipientId", recipientId);
        parent.setTag(TAG, tag);
    }

    public String getKit() {
        return kit;
    }

    public String getRecipient() {
        return recipient;
    }
}
