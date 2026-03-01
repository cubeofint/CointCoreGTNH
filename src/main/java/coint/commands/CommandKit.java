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
import serverutils.lib.math.Ticks;
import serverutils.lib.util.NBTUtils;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;
import serverutils.ranks.Ranks;

public class CommandKit extends CommandBase {

    private static final Pattern KIT_NAME = Pattern.compile("^[a-z0-9_-]{1,32}$");
    private static final String TAG_KIT_COOLDOWNS = "cointcore_kit_cooldowns";

    public CommandKit(MinecraftServer server) {
        PermissionAPI.registerNode("cointcore.command.kit.create", DefaultPermissionLevel.NONE, "CointCore kit create");
        PermissionAPI.registerNode("cointcore.command.kit.claim", DefaultPermissionLevel.NONE, "CointCore kit claim");
        PermissionAPI.registerNode("cointcore.command.kit.list", DefaultPermissionLevel.NONE, "CointCore kit list");
        PermissionAPI.registerNode("cointcore.command.kit.delete", DefaultPermissionLevel.NONE, "CointCore kit delete");

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
        return sender instanceof EntityPlayer;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/kit create <name> [cooldown] | /kit claim <name> | /kit list | /kit delete <name>";
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "create", "claim", "list", "delete");
        }
        if (args.length == 2 && ("claim".equalsIgnoreCase(args[0]) || "create".equalsIgnoreCase(args[0])
            || "delete".equalsIgnoreCase(args[0]))) {
            Collection<String> names = KitManager.getKitNames(MinecraftServer.getServer());
            return getListOfStringsMatchingLastWord(args, names.toArray(new String[0]));
        }
        return super.addTabCompletionOptions(sender, args);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP player)) {
            return;
        }

        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String sub = args[0].toLowerCase();
        String name = args.length > 1 ? args[1].toLowerCase() : null;
        if (("create".equals(sub) || "claim".equals(sub) || "delete".equals(sub)) && name == null) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        if (name != null && !KIT_NAME.matcher(name)
            .matches()) {
            sendError(sender, "Некорректное имя набора");
            return;
        }

        if ("create".equals(sub)) {
            if (!hasPermission(player, "cointcore.command.kit.create")) {
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

        if ("claim".equals(sub)) {
            KitDefinition kit = KitManager.getKit(MinecraftServer.getServer(), name);
            if (kit == null) {
                sendError(sender, "Набор не найден: " + name);
                return;
            }

            if (!hasPermission(player, "cointcore.command.kit.claim")
                || !hasPermission(player, "cointcore.kit." + name)) {
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

        if ("list".equals(sub)) {
            if (!hasPermission(player, "cointcore.command.kit.list")) {
                throw new CommandException("commands.generic.permission");
            }
            Collection<String> names = KitManager.getKitNames(MinecraftServer.getServer());
            if (names.isEmpty()) {
                sendError(sender, "Наборы не найдены");
                return;
            }
            sendSuccess(sender, "Наборы: " + String.join(", ", names));
            return;
        }

        if ("delete".equals(sub)) {
            if (!hasPermission(player, "cointcore.command.kit.delete")) {
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

        throw new WrongUsageException(getCommandUsage(sender));
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

    private boolean hasPermission(EntityPlayer player, String node) {
        return Ranks.INSTANCE.getPermission(player.getGameProfile(), node, true)
            .getBoolean();
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
            long remainingSeconds = (cooldownMs - elapsed + 999L);
            sendError(sender, "Набор будет доступен через " + TimeUtil.formatDuration(remainingSeconds));
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
