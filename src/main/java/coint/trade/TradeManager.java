package coint.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.network.WorldTravelNetwork;
import coint.supply.SupplyServerTasks;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Player trades on this server: requests ({@code /trade}), the open trades, and the exchange itself.
 *
 * <p>
 * Server thread only (packets hop onto it first).
 */
@EventBusSubscriber
public final class TradeManager {

    /** Main inventory slots: the hotbar and the three rows. */
    static final int INVENTORY = 36;

    /** Asker → his one request waiting for an answer. */
    private static final Map<UUID, Request> requests = new HashMap<>();
    /** Each of the two players → their trade. */
    private static final Map<UUID, TradeSession> sessions = new HashMap<>();
    private static int tick;

    private TradeManager() {}

    // ---------------------------------------------------------------- requests

    static void request(EntityPlayerMP asker, EntityPlayerMP target) {
        if (!CointConfig.trade.enabled) {
            asker.addChatMessage(text("§cОбмен сейчас выключен."));
            return;
        }
        if (target == asker) {
            asker.addChatMessage(text("§cНельзя обменяться с самим собой."));
            return;
        }
        if (sessions.containsKey(asker.getUniqueID())) {
            asker.addChatMessage(text("§cВы уже в обмене."));
            return;
        }
        Request mine = requests.get(asker.getUniqueID());
        if (mine != null) {
            asker.addChatMessage(
                text("§cУ вас уже есть запрос на обмен к §b" + mine.targetName + "§c. Отмените его: ")
                    .appendSibling(button("[Отменить]", EnumChatFormatting.RED, "/trade cancel")));
            return;
        }
        if (sessions.containsKey(target.getUniqueID())) {
            asker.addChatMessage(text("§b" + target.getCommandSenderName() + "§c сейчас в другом обмене."));
            return;
        }
        int seconds = CointConfig.trade.requestSeconds;
        requests.put(
            asker.getUniqueID(),
            new Request(
                asker.getUniqueID(),
                asker.getCommandSenderName(),
                target.getUniqueID(),
                target.getCommandSenderName(),
                System.currentTimeMillis() + seconds * 1000L));
        asker.addChatMessage(
            text(
                "§eЗапрос на обмен отправлен §b" + target.getCommandSenderName()
                    + "§e. Он действует "
                    + seconds
                    + " сек. ").appendSibling(button("[Отменить]", EnumChatFormatting.RED, "/trade cancel")));
        String name = asker.getCommandSenderName();
        target.addChatMessage(
            text("§b" + name + "§e предлагает вам обмен. ")
                .appendSibling(button("[Принять]", EnumChatFormatting.GREEN, "/trade accept " + name))
                .appendSibling(text(" "))
                .appendSibling(button("[Отклонить]", EnumChatFormatting.RED, "/trade deny " + name)));
    }

    /** /trade accept [name]: the request from {@code name}, or the latest one when no name is given. */
    static void accept(EntityPlayerMP player, String name) {
        Request request = incoming(player, name);
        if (request == null) {
            player.addChatMessage(text("§cНет запроса на обмен" + (name == null ? "." : " от §b" + name + "§c.")));
            return;
        }
        requests.remove(request.asker);
        EntityPlayerMP asker = online(request.asker);
        if (asker == null) {
            player.addChatMessage(text("§b" + request.askerName + "§c уже не на сервере."));
            return;
        }
        if (sessions.containsKey(player.getUniqueID())) {
            player.addChatMessage(text("§cВы уже в обмене."));
            return;
        }
        if (sessions.containsKey(asker.getUniqueID())) {
            player.addChatMessage(text("§b" + request.askerName + "§c сейчас в другом обмене."));
            return;
        }
        // A request of the one accepting, to someone else, is dropped: he trades now.
        Request own = requests.remove(player.getUniqueID());
        if (own != null) {
            EntityPlayerMP other = online(own.target);
            if (other != null) {
                other.addChatMessage(text("§eЗапрос на обмен от §b" + own.askerName + "§e отменён."));
            }
        }
        TradeSession session = new TradeSession(asker, player);
        sessions.put(asker.getUniqueID(), session);
        sessions.put(player.getUniqueID(), session);
        session.say("§7Обмен начат. Выложите предметы и нажмите «Готов».");
        asker.addChatMessage(text("§b" + player.getCommandSenderName() + "§a принял обмен."));
        sendBoth(session);
    }

    static void deny(EntityPlayerMP player, String name) {
        Request request = incoming(player, name);
        if (request == null) {
            player.addChatMessage(text("§cНет запроса на обмен" + (name == null ? "." : " от §b" + name + "§c.")));
            return;
        }
        requests.remove(request.asker);
        player.addChatMessage(text("§eВы отклонили обмен с §b" + request.askerName + "§e."));
        EntityPlayerMP asker = online(request.asker);
        if (asker != null) {
            asker.addChatMessage(text("§b" + player.getCommandSenderName() + "§c отклонил обмен."));
        }
    }

    /** /trade cancel: his own request, or the trade he is in. */
    static void cancel(EntityPlayerMP player) {
        TradeSession session = sessions.get(player.getUniqueID());
        if (session != null) {
            end(session, "§c" + player.getCommandSenderName() + " отменил обмен.");
            return;
        }
        Request request = requests.remove(player.getUniqueID());
        if (request == null) {
            player.addChatMessage(text("§cУ вас нет запроса на обмен."));
            return;
        }
        player.addChatMessage(text("§eЗапрос на обмен к §b" + request.targetName + "§e отменён."));
        EntityPlayerMP target = online(request.target);
        if (target != null) {
            target.addChatMessage(text("§b" + request.askerName + "§e отменил запрос на обмен."));
        }
    }

    private static Request incoming(EntityPlayerMP player, String name) {
        Request latest = null;
        long now = System.currentTimeMillis();
        for (Request request : requests.values()) {
            if (!request.target.equals(player.getUniqueID()) || request.expiresAt < now) {
                continue;
            }
            if (name != null && !request.askerName.equalsIgnoreCase(name)) {
                continue;
            }
            if (latest == null || request.expiresAt > latest.expiresAt) {
                latest = request;
            }
        }
        return latest;
    }

    // ---------------------------------------------------------------- the trade window

    static void act(EntityPlayerMP player, int action, int a, int count, String text) {
        SupplyServerTasks.run(() -> {
            TradeSession session = sessions.get(player.getUniqueID());
            if (session == null || session.over) {
                if (action != PacketTradeAction.CLOSE) {
                    WorldTravelNetwork.CHANNEL.sendTo(PacketTradeState.closed(), player);
                }
                return;
            }
            TradeSession.Side me = session.side(player.getUniqueID());
            TradeSession.Side partner = session.other(me);
            me.player = player;
            me.notice = "";
            switch (action) {
                case PacketTradeAction.ADD -> add(session, me, partner, a, count);
                case PacketTradeAction.REMOVE -> remove(session, me, a, count);
                case PacketTradeAction.READY -> {
                    if (me.ready) {
                        session.unready();
                    } else {
                        me.ready = true;
                        me.confirmed = partner.confirmed = false;
                    }
                }
                case PacketTradeAction.CONFIRM -> {
                    if (me.ready && partner.ready) {
                        me.confirmed = true;
                        if (partner.confirmed) {
                            complete(session);
                            return;
                        }
                    }
                }
                case PacketTradeAction.CHAT -> {
                    String line = EnumChatFormatting.getTextWithoutFormattingCodes(text == null ? "" : text)
                        .trim();
                    if (line.length() > 120) {
                        line = line.substring(0, 120);
                    }
                    if (!line.isEmpty()) {
                        session.say("§b" + me.name + "§f: " + line);
                    }
                }
                case PacketTradeAction.CLOSE -> {
                    end(session, "§c" + me.name + " закрыл окно обмена.");
                    return;
                }
                default -> {}
            }
            sendBoth(session);
        });
    }

    private static void add(TradeSession session, TradeSession.Side me, TradeSession.Side partner, int slot,
        int count) {
        if (me.ready || slot < 0 || slot >= INVENTORY || count <= 0) {
            return;
        }
        ItemStack stack = me.player.inventory.mainInventory[slot];
        if (stack == null || stack.stackSize <= 0) {
            return;
        }
        String epoch = TradeLimits.blockReason(stack, partner.player);
        if (epoch != null) {
            me.notice = "§c" + partner.name + " ещё не достиг эпохи " + epoch + ": этот предмет ему передать нельзя.";
            return;
        }
        TradeSession.Offer offer = me.offer(slot);
        if (offer != null && !same(offer.kind, stack)) {
            me.offers.remove(offer);
            offer = null;
        }
        if (offer == null) {
            if (me.offers.size() >= TradeSession.MAX_OFFERS) {
                me.notice = "§cНа столе нет места.";
                return;
            }
            ItemStack kind = stack.copy();
            kind.stackSize = 1;
            offer = new TradeSession.Offer(slot, kind, 0);
            me.offers.add(offer);
        }
        offer.count = Math.min(stack.stackSize, offer.count + count);
        session.unready();
    }

    private static void remove(TradeSession session, TradeSession.Side me, int index, int count) {
        if (me.ready || index < 0 || index >= me.offers.size()) {
            return;
        }
        TradeSession.Offer offer = me.offers.get(index);
        offer.count = count <= 0 ? 0 : offer.count - count;
        if (offer.count <= 0) {
            me.offers.remove(index);
        }
        session.unready();
    }

    // ---------------------------------------------------------------- the exchange

    private static void complete(TradeSession session) {
        TradeSession.Side a = session.a, b = session.b;
        if (check(a) | check(b)) {
            session.unready();
            a.notice = b.notice = "§cПредметы в обмене изменились. Проверьте и подтвердите снова.";
            sendBoth(session);
            return;
        }
        if (blocked(a, b) | blocked(b, a)) {
            session.unready();
            sendBoth(session);
            return;
        }
        List<ItemStack> fromA = offered(a), fromB = offered(b);
        boolean roomA = fits(a, fromB), roomB = fits(b, fromA);
        if (!roomA || !roomB) {
            session.unready();
            noRoom(a, b, roomA);
            noRoom(b, a, roomB);
            sendBoth(session);
            return;
        }
        take(a);
        take(b);
        give(a.player, fromB);
        give(b.player, fromA);
        a.player.inventoryContainer.detectAndSendChanges();
        b.player.inventoryContainer.detectAndSendChanges();
        TradeLog.log(a.name, b.name, fromA, fromB);
        session.over = true;
        sessions.remove(a.uuid);
        sessions.remove(b.uuid);
        for (TradeSession.Side side : new TradeSession.Side[] { a, b }) {
            WorldTravelNetwork.CHANNEL.sendTo(PacketTradeState.closed(), side.player);
            side.player.addChatMessage(text("§aОбмен с §b" + session.other(side).name + "§a завершён."));
        }
    }

    private static void noRoom(TradeSession.Side side, TradeSession.Side other, boolean room) {
        if (!room) {
            side.notice = "§cНе хватает места в инвентаре. Освободите слоты и подтвердите снова.";
            other.notice = "§cУ " + side.name + " не хватает места в инвентаре.";
        }
    }

    /** Takes out the offers that no longer match the inventory: true when anything changed. */
    private static boolean check(TradeSession.Side side) {
        boolean changed = false;
        ItemStack[] inventory = side.player.inventory.mainInventory;
        for (Iterator<TradeSession.Offer> it = side.offers.iterator(); it.hasNext();) {
            TradeSession.Offer offer = it.next();
            ItemStack stack = inventory[offer.slot];
            if (stack == null || stack.stackSize <= 0 || !same(offer.kind, stack)) {
                it.remove();
                changed = true;
            } else if (stack.stackSize < offer.count) {
                offer.count = stack.stackSize;
                changed = true;
            }
        }
        return changed;
    }

    /** Offers of {@code giver} that {@code receiver} may not have (his epoch changed, or the list did). */
    private static boolean blocked(TradeSession.Side giver, TradeSession.Side receiver) {
        for (TradeSession.Offer offer : giver.offers) {
            String epoch = TradeLimits.blockReason(offer.kind, receiver.player);
            if (epoch != null) {
                giver.notice = "§cУберите " + offer.kind
                    .getDisplayName() + ": " + receiver.name + " ещё не достиг эпохи " + epoch + ".";
                receiver.notice = "§cВам нельзя получить " + offer.kind.getDisplayName() + " до эпохи " + epoch + ".";
                return true;
            }
        }
        return false;
    }

    private static List<ItemStack> offered(TradeSession.Side side) {
        List<ItemStack> stacks = new ArrayList<>();
        for (TradeSession.Offer offer : side.offers) {
            stacks.add(offer.stack());
        }
        return stacks;
    }

    /** Whether {@code incoming} fits into the inventory of {@code side} once his own offers are gone. */
    private static boolean fits(TradeSession.Side side, List<ItemStack> incoming) {
        ItemStack[] inventory = new ItemStack[INVENTORY];
        for (int i = 0; i < INVENTORY; i++) {
            ItemStack stack = side.player.inventory.mainInventory[i];
            int left = stack == null ? 0 : stack.stackSize - side.reserved(i);
            if (left > 0) {
                inventory[i] = stack.copy();
                inventory[i].stackSize = left;
            }
        }
        for (ItemStack stack : incoming) {
            if (place(inventory, stack.copy(), side.player.inventory) > 0) {
                return false;
            }
        }
        return true;
    }

    private static void take(TradeSession.Side side) {
        ItemStack[] inventory = side.player.inventory.mainInventory;
        for (TradeSession.Offer offer : side.offers) {
            ItemStack stack = inventory[offer.slot];
            stack.stackSize -= offer.count;
            if (stack.stackSize <= 0) {
                inventory[offer.slot] = null;
            }
        }
        side.player.inventory.markDirty();
    }

    private static void give(EntityPlayerMP player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            ItemStack rest = stack.copy();
            if (place(player.inventory.mainInventory, rest, player.inventory) > 0) {
                // Checked beforehand, so only a mod changing the inventory meanwhile gets here: nothing is lost.
                player.entityDropItem(rest, 0.5F);
            }
        }
        player.inventory.markDirty();
    }

    /**
     * Puts {@code stack} into {@code inventory}: first onto stacks of the same item, then into empty slots (hotbar
     * first, as picking up does). Returns how many did not fit (also left in {@code stack}).
     */
    private static int place(ItemStack[] inventory, ItemStack stack, InventoryPlayer owner) {
        int limit = Math.min(stack.getMaxStackSize(), owner.getInventoryStackLimit());
        if (stack.isStackable()) {
            for (int i = 0; i < INVENTORY && stack.stackSize > 0; i++) {
                ItemStack there = inventory[i];
                if (there != null && same(there, stack) && there.stackSize < limit) {
                    int moved = Math.min(limit - there.stackSize, stack.stackSize);
                    there.stackSize += moved;
                    stack.stackSize -= moved;
                }
            }
        }
        for (int i = 0; i < INVENTORY && stack.stackSize > 0; i++) {
            if (inventory[i] == null) {
                ItemStack put = stack.copy();
                put.stackSize = Math.min(stack.stackSize, Math.max(1, limit));
                inventory[i] = put;
                stack.stackSize -= put.stackSize;
            }
        }
        return stack.stackSize;
    }

    static boolean same(ItemStack x, ItemStack y) {
        return x.getItem() == y.getItem() && x.getItemDamage() == y.getItemDamage()
            && ItemStack.areItemStackTagsEqual(x, y);
    }

    // ---------------------------------------------------------------- ending and keeping up

    private static void end(TradeSession session, String why) {
        if (session.over) {
            return;
        }
        session.over = true;
        for (TradeSession.Side side : new TradeSession.Side[] { session.a, session.b }) {
            sessions.remove(side.uuid);
            EntityPlayerMP player = online(side.uuid);
            if (player != null) {
                WorldTravelNetwork.CHANNEL.sendTo(PacketTradeState.closed(), player);
                player.addChatMessage(text(why + " §7Ничего не передано."));
            }
        }
    }

    private static void sendBoth(TradeSession session) {
        send(session, session.a);
        send(session, session.b);
    }

    private static void send(TradeSession session, TradeSession.Side me) {
        TradeSession.Side partner = session.other(me);
        PacketTradeState state = new PacketTradeState();
        state.open = true;
        state.partner = partner.name;
        state.myReady = me.ready;
        state.partnerReady = partner.ready;
        state.myConfirm = me.confirmed;
        state.partnerConfirm = partner.confirmed;
        int n = me.offers.size();
        state.mySlots = new int[n];
        state.myItems = new ItemStack[n];
        for (int i = 0; i < n; i++) {
            state.mySlots[i] = me.offers.get(i).slot;
            state.myItems[i] = me.offers.get(i)
                .stack();
        }
        state.partnerItems = offered(partner).toArray(new ItemStack[0]);
        List<Integer> slots = new ArrayList<>();
        List<String> epochs = new ArrayList<>();
        for (int i = 0; i < INVENTORY; i++) {
            String epoch = TradeLimits.blockReason(me.player.inventory.mainInventory[i], partner.player);
            if (epoch != null) {
                slots.add(i);
                epochs.add(epoch);
            }
        }
        state.blockedSlots = slots.stream()
            .mapToInt(Integer::intValue)
            .toArray();
        state.blockedEpochs = epochs.toArray(new String[0]);
        state.chat = session.chat.toArray(new String[0]);
        state.notice = me.notice;
        WorldTravelNetwork.CHANNEL.sendTo(state, me.player);
    }

    /** Twice a second: requests that ran out, players gone, offers that no longer match the inventory. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++tick % 10 != 0) {
            return;
        }
        if (!requests.isEmpty()) {
            long now = System.currentTimeMillis();
            for (Iterator<Request> it = requests.values()
                .iterator(); it.hasNext();) {
                Request request = it.next();
                if (request.expiresAt >= now) {
                    continue;
                }
                it.remove();
                EntityPlayerMP asker = online(request.asker), target = online(request.target);
                if (asker != null) {
                    asker.addChatMessage(text("§eЗапрос на обмен к §b" + request.targetName + "§e истёк."));
                }
                if (target != null) {
                    target.addChatMessage(text("§eЗапрос на обмен от §b" + request.askerName + "§e истёк."));
                }
            }
        }
        if (sessions.isEmpty()) {
            return;
        }
        Set<TradeSession> seen = new HashSet<>(sessions.values());
        for (TradeSession session : seen) {
            EntityPlayerMP pa = online(session.a.uuid), pb = online(session.b.uuid);
            if (pa == null || pb == null || pa.isDead || pb.isDead) {
                end(
                    session,
                    "§cОбмен отменён: " + (pa == null || pa.isDead ? session.a.name : session.b.name) + " недоступен.");
                continue;
            }
            session.a.player = pa;
            session.b.player = pb;
            if (check(session.a) | check(session.b)) {
                session.unready();
                sendBoth(session);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.player.getUniqueID();
        TradeSession session = sessions.get(uuid);
        if (session != null) {
            end(session, "§cОбмен отменён: " + event.player.getCommandSenderName() + " вышел.");
        }
        requests.remove(uuid);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.entityLiving instanceof EntityPlayer player) {
            TradeSession session = sessions.get(player.getUniqueID());
            if (session != null) {
                end(session, "§cОбмен отменён: " + player.getCommandSenderName() + " погиб.");
            }
        }
    }

    public static void clear() {
        requests.clear();
        sessions.clear();
    }

    // ---------------------------------------------------------------- helpers

    static EntityPlayerMP online(UUID uuid) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return null;
        }
        for (Object o : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (player.getUniqueID()
                .equals(uuid)) {
                return player;
            }
        }
        return null;
    }

    static IChatComponent text(String s) {
        return new ChatComponentText(s);
    }

    private static IChatComponent button(String label, EnumChatFormatting color, String command) {
        ChatComponentText button = new ChatComponentText(label);
        button.setChatStyle(
            new ChatStyle().setColor(color)
                .setBold(true)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(command))));
        return button;
    }

    private static final class Request {

        final UUID asker, target;
        final String askerName, targetName;
        final long expiresAt;

        Request(UUID asker, String askerName, UUID target, String targetName, long expiresAt) {
            this.asker = asker;
            this.askerName = askerName;
            this.target = target;
            this.targetName = targetName;
            this.expiresAt = expiresAt;
        }
    }
}
