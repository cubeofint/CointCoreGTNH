package coint.player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.command.CommandException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import coint.CointCore;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

/**
 * Players deleting their own team's personal dimension ({@code /pdim delete}). Every team member online when it is
 * asked for has to approve within {@link #APPROVE_MS} (the one who asked as well, so nobody deletes by a slip); any
 * member can cancel. The deletion itself is the admin one, {@link PlayerResetManager#resetPersonalDimension}.
 */
public final class PDimDeleteRequests {

    private static final long APPROVE_MS = 120_000;

    private static final class Pending {

        final int dimId;
        final Set<UUID> required;
        final Set<UUID> approved = new HashSet<>();
        final long expiresAt;

        Pending(int dimId, Set<UUID> required, long expiresAt) {
            this.dimId = dimId;
            this.required = required;
            this.expiresAt = expiresAt;
        }
    }

    /** By team id. */
    private static final Map<String, Pending> pending = new HashMap<>();

    private PDimDeleteRequests() {}

    public static void request(EntityPlayerMP player) throws CommandException {
        request(player, false);
    }

    /**
     * The delete button of a portal: {@code dimId} is the dimension that portal belongs to, which has to be the
     * team's. The button had its own confirmation, so it counts as the player's approval.
     */
    public static void requestFromPortal(EntityPlayerMP player, int dimId) throws CommandException {
        int teamDim = TeamsManager.get()
            .getDim(teamOf(player));
        if (teamDim <= 0 || dimId != teamDim) {
            throw new CommandException("Это не персоналка вашей команды: удалить можно только свою.");
        }
        request(player, true);
    }

    private static void request(EntityPlayerMP player, boolean approvedByRequester) throws CommandException {
        ForgeTeam team = teamOf(player);
        int dimId = TeamsManager.get()
            .getDim(team);
        if (dimId <= 0) {
            throw new CommandException("У вашей команды нет персоналки. Если она есть, напишите администрации.");
        }
        Pending existing = live(team);
        if (existing != null) {
            throw new CommandException(
                "Удаление уже запрошено: подтвердите /pdim delete approve или отмените /pdim delete cancel.");
        }
        Set<UUID> required = new HashSet<>();
        for (ForgePlayer member : team.getMembers()) {
            if (member.isOnline()) {
                required.add(member.getId());
            }
        }
        required.add(player.getUniqueID());
        pending.put(team.getId(), new Pending(dimId, required, System.currentTimeMillis() + APPROVE_MS));

        IChatComponent message = new ChatComponentText(
            "§c" + player.getCommandSenderName()
                + " хочет удалить персоналку команды (DIM "
                + dimId
                + ") вместе со всем построенным. Это необратимо. ");
        message.appendSibling(button("[Подтвердить]", "/pdim delete approve", EnumChatFormatting.RED));
        message.appendSibling(new ChatComponentText(" "));
        message.appendSibling(button("[Отменить]", "/pdim delete cancel", EnumChatFormatting.GREEN));
        message.appendSibling(
            new ChatComponentText(
                " §7Подтвердить должны все, кто в сети (" + required.size() + "), в течение 2 минут."));
        if (approvedByRequester && required.size() == 1) {
            // Alone and already confirmed on the screen: nobody else to ask.
            approve(player);
            return;
        }
        tell(required, message);
        if (approvedByRequester) {
            approve(player);
        }
    }

    public static void approve(EntityPlayerMP player) throws CommandException {
        ForgeTeam team = teamOf(player);
        Pending request = live(team);
        if (request == null) {
            throw new CommandException("Запроса на удаление персоналки нет. Начать: /pdim delete");
        }
        if (!request.required.contains(player.getUniqueID())) {
            throw new CommandException("Вас не было в сети, когда удаление запросили: подтверждать должны другие.");
        }
        request.approved.add(player.getUniqueID());
        if (!request.approved.containsAll(request.required)) {
            tell(
                request.required,
                new ChatComponentText(
                    "§e" + player.getCommandSenderName()
                        + " подтвердил удаление персоналки ("
                        + request.approved.size()
                        + "/"
                        + request.required.size()
                        + ")."));
            return;
        }

        pending.remove(team.getId());
        ForgePlayer owner = Universe.get()
            .getPlayer(player);
        int deleted = PlayerResetManager.resetPersonalDimension(owner);
        CointCore.LOG.info(
            "[PDim] Team {} deleted its personal dimension DIM {} (approved by {})",
            team.getId(),
            deleted,
            request.approved);
        tell(
            request.required,
            new ChatComponentText(
                deleted > 0 ? "§aПерсоналка команды удалена. Новую можно получить заново."
                    : "§eАктивная персоналка не найдена, привязка сброшена: новую можно получить заново."));
    }

    public static void cancel(EntityPlayerMP player) throws CommandException {
        ForgeTeam team = teamOf(player);
        Pending request = live(team);
        if (request == null) {
            throw new CommandException("Запроса на удаление персоналки нет.");
        }
        pending.remove(team.getId());
        tell(
            request.required,
            new ChatComponentText("§a" + player.getCommandSenderName() + " отменил удаление персоналки."));
    }

    /** The team's request, if it has one still waiting; an expired one is dropped. */
    private static Pending live(ForgeTeam team) {
        Pending request = pending.get(team.getId());
        if (request != null && System.currentTimeMillis() > request.expiresAt) {
            pending.remove(team.getId());
            tell(request.required, new ChatComponentText("§7Запрос на удаление персоналки истёк: не все подтвердили."));
            return null;
        }
        return request;
    }

    private static ForgeTeam teamOf(EntityPlayerMP player) throws CommandException {
        ForgePlayer forgePlayer = Universe.get()
            .getPlayer(player);
        if (forgePlayer == null || !forgePlayer.hasTeam()) {
            throw new CommandException("Вы не состоите в команде: персоналка принадлежит команде.");
        }
        return forgePlayer.team;
    }

    private static IChatComponent button(String text, String command, EnumChatFormatting color) {
        return new ChatComponentText(text).setChatStyle(
            new ChatStyle().setColor(color)
                .setBold(true)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    private static void tell(Set<UUID> players, IChatComponent message) {
        for (UUID uuid : players) {
            ForgePlayer forgePlayer = Universe.get()
                .getPlayer(uuid);
            EntityPlayerMP online = forgePlayer == null ? null : forgePlayer.getNullablePlayer();
            if (online != null) {
                online.addChatMessage(message);
            }
        }
    }
}
