package coint.commands;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import coint.commands.kit.KitDefinition;
import coint.commands.kit.KitManager;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandKit extends CommandBase {

    private static final Pattern KIT_NAME = Pattern.compile("^[a-z0-9_-]{1,32}$");

    private static final String PERM_KIT_EDIT = "cointcore.command.kit.edit";
    /** Право на /kit add и /kit set (остаток использований). */
    private static final String PERM_KIT_RESET = "cointcore.command.kit.reset";
    /**
     * Доступ ко всем наборам с безлимитным maxClaims (типично для администраторов).
     * Отдельные наборы по-прежнему можно узко выдавать через {@code cointcore.kit.<name>}.
     */
    public static final String PERM_KIT_ALL = "cointcore.kit.all";

    public CommandKit() {
        PermissionAPI.registerNode(PERM_KIT_EDIT, DefaultPermissionLevel.OP, "CointCore kit create and delete");
        PermissionAPI.registerNode(PERM_KIT_RESET, DefaultPermissionLevel.OP, "CointCore kit add and set claims");
        PermissionAPI
            .registerNode(PERM_KIT_ALL, DefaultPermissionLevel.NONE, "CointCore kit: access all unlimited kits");
    }

    @Override
    public String getCommandName() {
        return "kit";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        if (!(sender instanceof EntityPlayerMP)) {
            return "/kit <create|claim|list|delete|add|set>";
        }
        ForgePlayer player = Universe.get()
            .getPlayer(sender);
        List<String> parts = new ArrayList<>();
        parts.add("/kit claim <name>");
        parts.add("/kit list");

        if (PermissionAPI.hasPermission(player.getPlayer(), PERM_KIT_EDIT)) {
            parts.add("/kit create <name> [maxClaims]");
            parts.add("/kit delete <name>");
        }
        if (PermissionAPI.hasPermission(player.getPlayer(), PERM_KIT_RESET)) {
            parts.add("/kit add <name> <player> <amount>");
            parts.add("/kit set <name> <player> <amount>");
        }
        return String.join(" | ", parts);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(java.util.Arrays.asList("claim", "list"));
            if (sender instanceof EntityPlayerMP player) {
                if (PermissionAPI.hasPermission(player, PERM_KIT_EDIT)) {
                    subs.add("create");
                    subs.add("delete");
                }
                if (PermissionAPI.hasPermission(player, PERM_KIT_RESET)) {
                    subs.add("add");
                    subs.add("set");
                }
            } else {
                subs.addAll(java.util.Arrays.asList("create", "delete", "add", "set"));
            }
            return getListOfStringsMatchingLastWord(args, subs.toArray(new String[0]));
        }
        if (args.length == 2 && "claim".equalsIgnoreCase(args[0])) {
            if (sender instanceof EntityPlayerMP player) {
                List<String> accessible = new ArrayList<>();
                for (String n : KitManager.getKitNames(MinecraftServer.getServer())) {
                    KitDefinition kit = KitManager.getKit(MinecraftServer.getServer(), n);
                    if (kit == null) {
                        continue;
                    }
                    if (canClaimKitNow(player, n, kit)) {
                        accessible.add(n);
                    }
                }
                return getListOfStringsMatchingLastWord(args, accessible.toArray(new String[0]));
            }
        }
        if (args.length == 2 && ("create".equalsIgnoreCase(args[0]) || "delete".equalsIgnoreCase(args[0])
            || "add".equalsIgnoreCase(args[0])
            || "set".equalsIgnoreCase(args[0]))) {
            Collection<String> names = KitManager.getKitNames(MinecraftServer.getServer());
            return getListOfStringsMatchingLastWord(args, names.toArray(new String[0]));
        }
        if (args.length == 3 && ("add".equalsIgnoreCase(args[0]) || "set".equalsIgnoreCase(args[0]))) {
            return getListOfStringsMatchingLastWord(
                args,
                MinecraftServer.getServer()
                    .getAllUsernames());
        }
        return super.addTabCompletionOptions(sender, args);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String sub = args[0].toLowerCase();

        if ("add".equals(sub) || "set".equals(sub)) {
            if (sender instanceof EntityPlayer player && !PermissionAPI.hasPermission(player, PERM_KIT_RESET)) {
                throw new CommandException("commands.generic.permission");
            }
            if (args.length < 4) {
                throw new WrongUsageException("/kit " + sub + " <kitName> <player> <amount>");
            }

            String kitName = args[1].toLowerCase();
            if (!KIT_NAME.matcher(kitName)
                .matches()) {
                sendError(sender, "Некорректное имя набора");
                return;
            }

            KitDefinition kit = KitManager.getKit(MinecraftServer.getServer(), kitName);
            if (kit == null) {
                sendError(sender, "Набор не найден: " + kitName);
                return;
            }

            int amount = parseAmountAllowingSuffix(args[3]);
            if (amount < 0) {
                sendError(sender, "Некорректный формат amount (нужны цифры, например 12 или 12mo)");
                return;
            }

            ForgePlayer target = Universe.get()
                .getPlayer(args[2]);
            if (target == null) {
                sendError(sender, "Игрок не найден: " + args[2]);
                return;
            }

            int maxClaims = kit.getMaxClaims();
            String status = target.isOnline() ? "" : " (оффлайн)";

            if (maxClaims < 0) {
                int currentRemaining = KitManager.getKitBonusRemaining(target, kitName);
                int newRemaining;
                if ("add".equals(sub)) {
                    newRemaining = currentRemaining + amount;
                } else {
                    newRemaining = amount;
                }
                KitManager.setKitBonusRemaining(target, kitName, newRemaining);
                sendSuccess(
                    sender,
                    "Бонусные получения набора " + kitName
                        + " для "
                        + target.getName()
                        + status
                        + ": "
                        + currentRemaining
                        + " -> "
                        + newRemaining);
                return;
            }

            int currentClaims = KitManager.getKitClaimCount(target, kitName);
            int currentRemaining = Math.max(0, maxClaims - currentClaims);
            int newRemaining;
            if ("add".equals(sub)) {
                newRemaining = Math.min(maxClaims, currentRemaining + amount);
            } else {
                newRemaining = Math.min(maxClaims, amount);
            }

            int newClaims = Math.max(0, maxClaims - newRemaining);
            KitManager.setKitClaimCount(target, kitName, newClaims);

            sendSuccess(
                sender,
                "Остаток использований " + kitName
                    + " для "
                    + target.getName()
                    + status
                    + ": "
                    + currentRemaining
                    + " -> "
                    + newRemaining
                    + " (макс "
                    + maxClaims
                    + ")");
            return;
        }

        if (!(sender instanceof EntityPlayerMP player)) {
            sendError(sender, "Эту команду может использовать только игрок");
            return;
        }

        String name = args.length > 1 ? args[1].toLowerCase() : null;
        if (("create".equals(sub) || "claim".equals(sub) || "delete".equals(sub)) && name == null) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        if (name != null && !KIT_NAME.matcher(name)
            .matches()) {
            sendError(sender, "Некорректное имя набора");
            return;
        }

        switch (sub) {
            case "create": {
                if (lacksEditPermission(player)) {
                    throw new CommandException("commands.generic.permission");
                }
                int maxClaims = parseMaxClaims(sender, args);
                if (maxClaims < -1) {
                    return;
                }

                List<ItemStack> items = captureItems(player);
                if (items.isEmpty()) {
                    sendError(sender, "Не найден источник набора: сундук или предмет в руке");
                    return;
                }

                KitDefinition kit = new KitDefinition(name, items, maxClaims);
                KitManager.putKit(MinecraftServer.getServer(), kit);
                sendSuccess(sender, "Набор сохранен: " + name);
                return;
            }
            case "claim": {
                KitDefinition kit = KitManager.getKit(MinecraftServer.getServer(), name);
                if (kit == null) {
                    sendError(sender, "Набор не найден: " + name);
                    return;
                }

                if (!canFitAll(player, kit.getItems())) {
                    sendError(sender, "Инвентарь полон, набор не получен");
                    return;
                }

                if (kit.getMaxClaims() < 0) {
                    int bonus = KitManager.getKitBonusRemaining(player, name);
                    if (bonus > 0) {
                        for (ItemStack stack : kit.getItems()) {
                            giveItem(player, stack.copy());
                        }
                        KitManager.setKitBonusRemaining(player, name, bonus - 1);
                        sendSuccess(sender, "Набор получен: " + name);
                        return;
                    }
                }

                if (hasRemainingClaims(player, name, kit)) {
                    int claims = KitManager.getKitClaimCount(player, name);
                    for (ItemStack stack : kit.getItems()) {
                        giveItem(player, stack.copy());
                    }
                    KitManager.setKitClaimCount(player, name, claims + 1);
                    sendSuccess(sender, "Набор получен: " + name);
                    return;
                }

                if (!hasKitRankAccess(player, name, kit)) {
                    sendError(sender, "У вас нет доступа к набору: " + name);
                    return;
                }

                int maxClaims = kit.getMaxClaims();
                if (maxClaims > 0) {
                    int claims = KitManager.getKitClaimCount(player, name);
                    if (claims >= maxClaims) {
                        sendError(sender, "Лимит использований исчерпан (" + claims + "/" + maxClaims + ")");
                        return;
                    }
                    for (ItemStack stack : kit.getItems()) {
                        giveItem(player, stack.copy());
                    }
                    KitManager.setKitClaimCount(player, name, claims + 1);
                    sendSuccess(sender, "Набор получен: " + name);
                    return;
                }

                for (ItemStack stack : kit.getItems()) {
                    giveItem(player, stack.copy());
                }
                sendSuccess(sender, "Набор получен: " + name);
                return;
            }
            case "list": {
                Collection<String> allNames = KitManager.getKitNames(MinecraftServer.getServer());
                List<String> lines = new ArrayList<>();
                for (String kitName : allNames) {
                    KitDefinition kit = KitManager.getKit(MinecraftServer.getServer(), kitName);
                    if (kit == null) {
                        continue;
                    }
                    if (!canClaimKitNow(player, kitName, kit)) {
                        continue;
                    }
                    int claims = KitManager.getKitClaimCount(player, kitName);
                    String usage = formatListSuffix(player, kitName, kit, claims);
                    lines.add("- " + kitName + (usage.isEmpty() ? "" : " " + usage));
                }
                if (lines.isEmpty()) {
                    sendError(sender, "Нет доступных наборов");
                    return;
                }
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + "Наборы:"));
                for (String line : lines) {
                    ChatComponentText msg = new ChatComponentText(line);
                    msg.getChatStyle()
                        .setColor(EnumChatFormatting.GREEN);
                    sender.addChatMessage(msg);
                }
                return;
            }
            case "delete": {
                if (lacksEditPermission(player)) {
                    throw new CommandException("commands.generic.permission");
                }
                KitDefinition kit = KitManager.getKit(MinecraftServer.getServer(), name);
                if (kit == null) {
                    sendError(sender, "Набор не найден: " + name);
                    return;
                }
                KitManager.removeKit(MinecraftServer.getServer(), name);
                sendSuccess(sender, "Набор удален: " + name);
                return;
            }
            default:
                throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private List<ItemStack> captureItems(EntityPlayer player) {
        IInventory inventory = getTargetInventory(player);
        if (inventory != null) {
            return captureFromInventory(inventory);
        }

        ItemStack held = player.getHeldItem();
        if (held != null) {
            List<ItemStack> items = new ArrayList<>();
            items.add(held.copy());
            return items;
        }

        return new ArrayList<>();
    }

    private IInventory getTargetInventory(EntityPlayer player) {
        Vec3 start = Vec3.createVectorHelper(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        Vec3 look = player.getLook(1.0F);
        Vec3 end = start.addVector(look.xCoord * 5.0D, look.yCoord * 5.0D, look.zCoord * 5.0D);
        MovingObjectPosition hit = player.worldObj.rayTraceBlocks(start, end);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }

        TileEntity tile = player.worldObj.getTileEntity(hit.blockX, hit.blockY, hit.blockZ);
        if (tile instanceof IInventory) {
            return (IInventory) tile;
        }

        return null;
    }

    private List<ItemStack> captureFromInventory(IInventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null) {
                items.add(stack.copy());
            }
        }
        return items;
    }

    private void giveItem(EntityPlayer player, ItemStack stack) {
        if (stack == null) {
            return;
        }
        if (!player.inventory.addItemStackToInventory(stack)) {
            player.entityDropItem(stack, 0.0F);
        }
    }

    private boolean canFitAll(EntityPlayer player, List<ItemStack> items) {
        InventoryPlayer inventory = player.inventory;
        ItemStack[] slots = new ItemStack[inventory.mainInventory.length];
        for (int i = 0; i < inventory.mainInventory.length; i++) {
            ItemStack existing = inventory.mainInventory[i];
            slots[i] = existing == null ? null : existing.copy();
        }

        for (ItemStack stack : items) {
            if (stack == null) {
                continue;
            }
            ItemStack remaining = stack.copy();
            if (remaining.stackSize <= 0) {
                continue;
            }

            mergeIntoExisting(slots, inventory, remaining);
            if (remaining.stackSize > 0) {
                fillEmptySlots(slots, inventory, remaining);
            }
            if (remaining.stackSize > 0) {
                return false;
            }
        }

        return true;
    }

    private void mergeIntoExisting(ItemStack[] slots, InventoryPlayer inventory, ItemStack remaining) {
        if (!remaining.isStackable()) {
            return;
        }

        for (ItemStack existing : slots) {
            if (existing == null) {
                continue;
            }
            if (!existing.isStackable()) {
                continue;
            }
            if (!existing.isItemEqual(remaining)) {
                continue;
            }
            if (!ItemStack.areItemStackTagsEqual(existing, remaining)) {
                continue;
            }

            int max = Math.min(existing.getMaxStackSize(), inventory.getInventoryStackLimit());
            if (existing.stackSize >= max) {
                continue;
            }

            int space = max - existing.stackSize;
            int toMove = Math.min(space, remaining.stackSize);
            existing.stackSize += toMove;
            remaining.stackSize -= toMove;
            if (remaining.stackSize <= 0) {
                return;
            }
        }
    }

    private void fillEmptySlots(ItemStack[] slots, InventoryPlayer inventory, ItemStack remaining) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) {
                continue;
            }
            int max = Math.min(remaining.getMaxStackSize(), inventory.getInventoryStackLimit());
            int toMove = Math.min(max, remaining.stackSize);
            ItemStack copy = remaining.copy();
            copy.stackSize = toMove;
            slots[i] = copy;
            remaining.stackSize -= toMove;
            if (remaining.stackSize <= 0) {
                return;
            }
        }
    }

    private boolean lacksEditPermission(EntityPlayer player) {
        return !PermissionAPI.hasPermission(player, PERM_KIT_EDIT);
    }

    /**
     * Ранговый доступ: глобально {@link #PERM_KIT_ALL} только для безлимитных наборов,
     * либо точечно {@code cointcore.kit.<name>} для любого набора.
     */
    private boolean hasKitRankAccess(EntityPlayer player, String kitName, KitDefinition kit) {
        if (PermissionAPI.hasPermission(player, "cointcore.kit." + kitName)) {
            return true;
        }
        if (kit.getMaxClaims() >= 0) {
            return false;
        }
        return PermissionAPI.hasPermission(player, PERM_KIT_ALL);
    }

    private boolean hasRemainingClaims(EntityPlayer player, String kitName, KitDefinition kit) {
        int maxClaims = kit.getMaxClaims();
        if (maxClaims <= 0) {
            return false;
        }
        int claims = KitManager.getKitClaimCount(player, kitName);
        return claims < maxClaims;
    }

    /**
     * Набор можно получить сейчас: безлимит — только с ранговым доступом; лимит — пока не исчерпан лимит
     * получений (согласовано с ветками {@code claim}).
     */
    private boolean canClaimKitNow(EntityPlayer player, String kitName, KitDefinition kit) {
        int maxClaims = kit.getMaxClaims();
        if (maxClaims < 0) {
            if (KitManager.getKitBonusRemaining(player, kitName) > 0) {
                return true;
            }
            return hasKitRankAccess(player, kitName, kit);
        }
        int claims = KitManager.getKitClaimCount(player, kitName);
        return claims < maxClaims;
    }

    /** Лимит: (использовано/всего); безлимит: пусто или (n) — остаток бонусных выдач. */
    private String formatListSuffix(EntityPlayer player, String kitName, KitDefinition kit, int claims) {
        int maxClaims = kit.getMaxClaims();
        if (maxClaims < 0) {
            int bonus = KitManager.getKitBonusRemaining(player, kitName);
            return bonus > 0 ? "(" + bonus + ")" : "";
        }
        return "(" + claims + "/" + maxClaims + ")";
    }

    /**
     * Число из последнего аргумента {@code add}/{@code set}: учитываются только цифры ({@code 12mo} → 12).
     *
     * @return значение или {@code -1}, если цифр нет или число не помещается в {@code int}
     */
    private static int parseAmountAllowingSuffix(String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        String digitsStr = digits.toString();
        if (digitsStr.isEmpty()) {
            return -1;
        }
        try {
            long v = Long.parseLong(digitsStr);
            if (v > Integer.MAX_VALUE) {
                return -1;
            }
            return (int) v;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private int parseMaxClaims(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            return -1;
        }
        try {
            int value = Integer.parseInt(args[2]);
            if (value == 0 || value < -1) {
                sendError(sender, "maxClaims должен быть -1 (без лимита) или положительным числом");
                return -2;
            }
            return value;
        } catch (NumberFormatException ex) {
            sendError(sender, "Некорректный формат maxClaims");
            return -2;
        }
    }

    private void sendSuccess(ICommandSender sender, String message) {
        ChatComponentText msg = new ChatComponentText(message);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.GREEN);
        sender.addChatMessage(msg);
    }

    private void sendError(ICommandSender sender, String message) {
        ChatComponentText msg = new ChatComponentText(message);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.RED);
        sender.addChatMessage(msg);
    }
}
