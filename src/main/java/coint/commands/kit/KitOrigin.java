package coint.commands.kit;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

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

    public static ItemStack issuedCopy(ItemStack template, String kit, EntityPlayer player) {
        ItemStack copy = template.copy();
        ((KitMarkedStack) (Object) copy).cointcore$setKitOrigin(
            new KitOrigin(
                kit,
                player.getCommandSenderName(),
                player.getUniqueID()
                    .toString()));
        return copy;
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
