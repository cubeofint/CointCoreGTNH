package coint.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

/**
 * One trade between two players of this server. Offered items are not taken out of the inventory: each offer points
 * at an inventory slot and an amount, and everything changes hands at once when both have pressed the final button.
 *
 * <p>
 * Server thread only.
 */
final class TradeSession {

    static final int MAX_OFFERS = 36;
    static final int MAX_CHAT = 30;

    final Side a, b;
    final List<String> chat = new ArrayList<>();
    final long startedAt = System.currentTimeMillis();
    boolean over;

    TradeSession(EntityPlayerMP a, EntityPlayerMP b) {
        this.a = new Side(a);
        this.b = new Side(b);
    }

    Side side(UUID uuid) {
        return a.uuid.equals(uuid) ? a : b.uuid.equals(uuid) ? b : null;
    }

    Side other(Side side) {
        return side == a ? b : a;
    }

    /** Any change to what is on the table takes back both "ready" and both final presses. */
    void unready() {
        a.ready = b.ready = false;
        a.confirmed = b.confirmed = false;
    }

    void say(String line) {
        chat.add(line);
        while (chat.size() > MAX_CHAT) {
            chat.remove(0);
        }
    }

    static final class Side {

        final UUID uuid;
        final String name;
        EntityPlayerMP player;
        final List<Offer> offers = new ArrayList<>();
        boolean ready, confirmed;
        /** A note for this player only (no room, a blocked item), until his next action. */
        String notice = "";

        Side(EntityPlayerMP player) {
            this.uuid = player.getUniqueID();
            this.name = player.getCommandSenderName();
            this.player = player;
        }

        /** How much of inventory slot {@code slot} is on the table. */
        int reserved(int slot) {
            for (Offer offer : offers) {
                if (offer.slot == slot) {
                    return offer.count;
                }
            }
            return 0;
        }

        Offer offer(int slot) {
            for (Offer offer : offers) {
                if (offer.slot == slot) {
                    return offer;
                }
            }
            return null;
        }
    }

    /** {@code count} items of main inventory slot {@code slot}, which held {@code kind} when they were offered. */
    static final class Offer {

        final int slot;
        /** The item as offered, one of it: the slot must still hold the same item. */
        final ItemStack kind;
        int count;

        Offer(int slot, ItemStack kind, int count) {
            this.slot = slot;
            this.kind = kind;
            this.count = count;
        }

        ItemStack stack() {
            ItemStack stack = kind.copy();
            stack.stackSize = count;
            return stack;
        }
    }
}
