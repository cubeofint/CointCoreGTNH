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
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import coint.commands.kit.KitDefinition;
import coint.commands.kit.KitManager;
import coint.util.TimeUtil;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.math.Ticks;
import serverutils.lib.util.NBTUtils;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandKit extends CommandBase {

    private static final Pattern KIT_NAME = Pattern.compile("^[a-z0-9_-]{1,32}$");
    private static final String TAG_KIT_COOLDOWNS = "cointcore_kit_cooldowns";

    public CommandKit(MinecraftServer server) {
        PermissionAPI.registerNode("cointcore.command.kit.create", DefaultPermissionLevel.OP, "CointCore kit create");
        PermissionAPI.registerNode("cointcore.command.kit.claim", DefaultPermissionLevel.NONE, "CointCore kit claim");
        PermissionAPI.registerNode("cointcore.command.kit.list", DefaultPermissionLevel.NONE, "CointCore kit list");
        PermissionAPI.registerNode("cointcore.command.kit.delete", DefaultPermissionLevel.OP, "CointCore kit delete");
        PermissionAPI
            .registerNode("cointcore.command.kit.reset", DefaultPermissionLevel.OP, "CointCore kit reset cooldown");

        if (server != null) {
            for (String name : KitManager.getKitNames(server)) {
                PermissionAPI
                    .registerNode("cointcore.kit." + name, DefaultPermissionLevel.NONE, "CointCore kit: " + name);
            }
        }
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
        // EntityPlayerMP is the concrete server-side player class; using it avoids
        // edge cases where Forge wraps the sender in a non-EntityPlayer proxy.
        if (!(sender instanceof EntityPlayerMP)) {
            return "/kit <create|claim|list|delete|reset>";
        }
        EntityPlayerMP player = EntityPlayerMP.class.cast(sender);
        List<String> parts = new ArrayList<>();
        if (PermissionAPI.hasPermission(player, "cointcore.command.kit.create")) {
            parts.add("/kit create <name> [cooldown]");
        }
        if (PermissionAPI.hasPermission(player, "cointcore.command.kit.claim")) {
            parts.add("/kit claim <name>");
        }
        if (PermissionAPI.hasPermission(player, "cointcore.command.kit.list")) {
            parts.add("/kit list");
        }
        if (PermissionAPI.hasPermission(player, "cointcore.command.kit.delete")) {
            parts.add("/kit delete <name>");
        }
        if (PermissionAPI.hasPermission(player, "cointcore.command.kit.reset")) {
            parts.add("/kit reset <name> <player>");
        }
        return parts.isEmpty() ? "У вас нет доступа к /kit" : String.join(" | ", parts);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender instanceof EntityPlayerMP player) {
                if (PermissionAPI.hasPermission(player, "cointcore.command.kit.create")) subs.add("create");
                if (PermissionAPI.hasPermission(player, "cointcore.command.kit.claim")) subs.add("claim");
                if (PermissionAPI.hasPermission(player, "cointcore.command.kit.list")) subs.add("list");
                if (PermissionAPI.hasPermission(player, "cointcore.command.kit.delete")) subs.add("delete");
                if (PermissionAPI.hasPermission(player, "cointcore.command.kit.reset")) subs.add("reset");
            } else {
                subs.addAll(java.util.Arrays.asList("create", "claim", "list", "delete", "reset"));
            }
            return getListOfStringsMatchingLastWord(args, subs.toArray(new String[0]));
        }
        if (args.length == 2 && "claim".equalsIgnoreCase(args[0])) {
            // For claim: show only kits the player has permission to use
            if (sender instanceof EntityPlayerMP player) {
                List<String> accessible = new ArrayList<>();
                for (String n : KitManager.getKitNames(MinecraftServer.getServer())) {
                    if (PermissionAPI.hasPermission(player, "cointcore.kit." + n)) {
                        accessible.add(n);
                    }
                }
                return getListOfStringsMatchingLastWord(args, accessible.toArray(new String[0]));
            }
        }
        if (args.length == 2 && ("create".equalsIgnoreCase(args[0]) || "delete".equalsIgnoreCase(args[0])
            || "reset".equalsIgnoreCase(args[0]))) {
            Collection<String> names = KitManager.getKitNames(MinecraftServer.getServer());
            return getListOfStringsMatchingLastWord(args, names.toArray(new String[0]));
        }
        if (args.length == 3 && "reset".equalsIgnoreCase(args[0])) {
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

        // reset — не требует EntityPlayer от sender, обрабатываем отдельно
        if ("reset".equals(sub)) {
            if (sender instanceof EntityPlayer player
                && !PermissionAPI.hasPermission(player, "cointcore.command.kit.reset")) {
                throw new CommandException("commands.generic.permission");
            }
            if (args.length < 3) {
                throw new WrongUsageException("/kit reset <kitName> <player>");
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

            ForgePlayer target = Universe.get()
                .getPlayer(args[2]);
            if (target == null) {
                sendError(sender, "Игрок не найден: " + args[2]);
                return;
            }

            if (target.isOnline()) {
                // Онлайн: работаем напрямую с entityData в памяти игрока.
                // setPlayerNBT() вызывает readEntityFromNBT(), который НЕ восстанавливает
                // entityData/PERSISTED_NBT_TAG — поэтому кулдаун там не сбросится.
                EntityPlayerMP onlinePlayer = target.getPlayer();
                NBTTagCompound persisted = NBTUtils.getPersistedData(onlinePlayer, true);
                NBTTagCompound kitsTag = persisted.getCompoundTag(TAG_KIT_COOLDOWNS);
                kitsTag.removeTag(kitName);
                persisted.setTag(TAG_KIT_COOLDOWNS, kitsTag);
            } else {
                // Оффлайн: читаем .dat файл, правим, пишем обратно.
                NBTTagCompound playerNBT = target.getPlayerNBT();
                NBTTagCompound persisted = playerNBT.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
                NBTTagCompound kitsTag = persisted.getCompoundTag(TAG_KIT_COOLDOWNS);
                kitsTag.removeTag(kitName);
                persisted.setTag(TAG_KIT_COOLDOWNS, kitsTag);
                playerNBT.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
                target.setPlayerNBT(playerNBT);
            }

            String status = target.isOnline() ? "" : " (оффлайн)";
            sendSuccess(sender, "Кулдаун набора " + kitName + " сброшен для " + target.getName() + status);
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
                if (lacksPermission(player, "cointcore.command.kit.create")) {
                    throw new CommandException("commands.generic.permission");
                }
                long cooldownTicks = parseCooldown(sender, args);
                if (cooldownTicks < 0) {
                    return;
                }

                List<ItemStack> items = captureItems(player);
                if (items.isEmpty()) {
                    sendError(sender, "Не найден источник набора: сундук или предмет в руке");
                    return;
                }

                KitDefinition kit = new KitDefinition(name, items, cooldownTicks);
                KitManager.putKit(MinecraftServer.getServer(), kit);
                registerKitPermission(name);
                sendSuccess(sender, "Набор сохранен: " + name);
                return;
            }
            case "claim": {
                KitDefinition kit = KitManager.getKit(MinecraftServer.getServer(), name);
                if (kit == null) {
                    sendError(sender, "Набор не найден: " + name);
                    return;
                }

                if (lacksPermission(player, "cointcore.command.kit.claim")
                    || lacksPermission(player, "cointcore.kit." + name)) {
                    throw new CommandException("commands.generic.permission");
                }

                if (isOnCooldown(sender, player, kit)) {
                    return;
                }

                if (!canFitAll(player, kit.getItems())) {
                    sendError(sender, "Инвентарь полон, набор не получен");
                    return;
                }

                for (ItemStack stack : kit.getItems()) {
                    giveItem(player, stack.copy());
                }
                sendSuccess(sender, "Набор получен: " + name);
                return;
            }
            case "list": {
                if (lacksPermission(player, "cointcore.command.kit.list")) {
                    throw new CommandException("commands.generic.permission");
                }
                Collection<String> allNames = KitManager.getKitNames(MinecraftServer.getServer());
                List<String> accessible = new ArrayList<>();
                for (String kitName : allNames) {
                    if (PermissionAPI.hasPermission(player, "cointcore.kit." + kitName)) {
                        accessible.add(kitName);
                    }
                }
                if (accessible.isEmpty()) {
                    sendError(sender, "Нет доступных наборов");
                    return;
                }
                sendSuccess(sender, "Доступные наборы: " + String.join(", ", accessible));
                return;
            }
            case "delete": {
                if (lacksPermission(player, "cointcore.command.kit.delete")) {
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
        float yaw = player.rotationYaw;
        float pitch = player.rotationPitch;
        float yawRad = (float) Math.toRadians(-yaw) - (float) Math.PI;
        float pitchRad = (float) Math.toRadians(-pitch);

        double cosPitch = MathHelper.cos(pitchRad);
        double sinPitch = MathHelper.sin(pitchRad);
        double cosYaw = MathHelper.cos(yawRad);
        double sinYaw = MathHelper.sin(yawRad);

        Vec3 start = Vec3.createVectorHelper(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        Vec3 look = Vec3.createVectorHelper(sinYaw * cosPitch, sinPitch, cosYaw * cosPitch);
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

    private boolean lacksPermission(EntityPlayer player, String node) {
        return !PermissionAPI.hasPermission(player, node);
    }

    private long parseCooldown(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            return 0L;
        }

        try {
            return Ticks.get(args[2])
                .ticks();
        } catch (Exception ex) {
            sendError(sender, "Некорректный формат кулдауна");
            return -1L;
        }
    }

    private boolean isOnCooldown(ICommandSender sender, EntityPlayer player, KitDefinition kit) {
        long cooldownTicks = kit.getCooldownTicks();
        if (cooldownTicks <= 0) {
            return false;
        }

        NBTTagCompound persisted = NBTUtils.getPersistedData(player, true);
        NBTTagCompound kitsTag = persisted.getCompoundTag(TAG_KIT_COOLDOWNS);
        long lastUse = kitsTag.getLong(kit.getName());
        long now = System.currentTimeMillis();
        long elapsed = now - lastUse;
        long cooldownMs = Ticks.get(cooldownTicks)
            .millis();
        if (lastUse > 0 && elapsed < cooldownMs) {
            long remainingMs = cooldownMs - elapsed + 999L;
            sendError(sender, "Набор будет доступен через " + TimeUtil.formatDuration(remainingMs));
            return true;
        }

        kitsTag.setLong(kit.getName(), now);
        persisted.setTag(TAG_KIT_COOLDOWNS, kitsTag);
        return false;
    }

    private void registerKitPermission(String name) {
        PermissionAPI.registerNode("cointcore.kit." + name, DefaultPermissionLevel.NONE, "CointCore kit: " + name);
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
