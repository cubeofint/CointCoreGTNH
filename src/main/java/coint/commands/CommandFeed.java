package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.integration.serverutilities.CointRankConfigs;
import serverutils.lib.config.RankConfigAPI;
import serverutils.lib.math.Ticks;
import serverutils.lib.util.NBTUtils;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandFeed extends CommandBase {

    public static final String PERMISSION = "cointcore.command.feed";
    private static final String TAG_LAST_FEED_MS = "cointcore_feed_last_ms";

    public CommandFeed() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "CointCore feed permission");
    }

    @Override
    public String getCommandName() {
        return "feed";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true; // console/RCON
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/feed";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        EntityPlayer player = (EntityPlayer) sender;
        if (isOnCooldown(sender, player)) {
            return;
        }

        player.getFoodStats()
            .addStats(20, 20.0F);
        applyNutritionMax(player);
        ChatComponentText success = new ChatComponentText("Голод восполнен, милорд");
        success.getChatStyle().setColor(EnumChatFormatting.GREEN);
        sender.addChatMessage(success);
    }

    private boolean isOnCooldown(ICommandSender sender, EntityPlayer player) {
        NBTTagCompound persisted = NBTUtils.getPersistedData(player, true);
        long cooldownTicks = RankConfigAPI
            .get((net.minecraft.entity.player.EntityPlayerMP) player, CointRankConfigs.FEED_COOLDOWN)
            .getLong();
        if (cooldownTicks <= 0) {
            return false;
        }

        long now = System.currentTimeMillis();
        long lastUse = persisted.getLong(TAG_LAST_FEED_MS);
        long elapsed = now - lastUse;
        long cooldownMs = Ticks.get(cooldownTicks)
            .millis();
        if (lastUse > 0 && elapsed < cooldownMs) {
            long remainingSeconds = (cooldownMs - elapsed + 999L) / 1000L;
            sendError(sender, "Вы сможете покушать через " + formatDuration(remainingSeconds));
            return true;
        }

        persisted.setLong(TAG_LAST_FEED_MS, now);
        return false;
    }

    private void sendError(ICommandSender sender, String message) {
        ChatComponentText msg = new ChatComponentText(message);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.RED);
        sender.addChatMessage(msg);
    }

    private void applyNutritionMax(EntityPlayer player) {
        try {
            Class<?> playerDataHandler = Class.forName("ca.wescook.nutrition.data.PlayerDataHandler");
            Class<?> nutrientList = Class.forName("ca.wescook.nutrition.nutrients.NutrientList");
            Class<?> nutrientClass = Class.forName("ca.wescook.nutrition.nutrients.Nutrient");
            Class<?> nutrientManagerClass = Class.forName("ca.wescook.nutrition.data.NutrientManager");

            Object manager = playerDataHandler.getMethod("getForPlayer", EntityPlayer.class)
                .invoke(null, player);
            if (manager == null) {
                return;
            }

            @SuppressWarnings("unchecked")
            java.util.List<Object> nutrients = (java.util.List<Object>) nutrientList.getMethod("get")
                .invoke(null);
            java.lang.reflect.Method setMethod = nutrientManagerClass.getMethod("set", nutrientClass, Float.class);
            for (Object nutrient : nutrients) {
                setMethod.invoke(manager, nutrient, 100.0F);
            }

            try {
                playerDataHandler.getMethod("setForPlayer", EntityPlayer.class, nutrientManagerClass, boolean.class)
                    .invoke(null, player, manager, true);
            } catch (NoSuchMethodException ignored) {
                playerDataHandler.getMethod("setForPlayer", EntityPlayer.class, nutrientManagerClass)
                    .invoke(null, player, manager);
            }

            nutrientManagerClass.getMethod("update")
                .invoke(manager);
        } catch (Exception ignored) {
            // Nutrition mod not present or API changed; ignore.
        }
    }

    private String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "0 секунд";
        }

        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;

        if (minutes > 0 && remainingSeconds > 0) {
            return formatUnit(minutes, "минута", "минуты", "минут") + " "
                + formatUnit(remainingSeconds, "секунда", "секунды", "секунд");
        }
        if (minutes > 0) {
            return formatUnit(minutes, "минута", "минуты", "минут");
        }
        return formatUnit(remainingSeconds, "секунда", "секунды", "секунд");
    }

    private String formatUnit(long value, String one, String few, String many) {
        long mod100 = value % 100L;
        long mod10 = value % 10L;
        String word;

        if (mod100 >= 11L && mod100 <= 14L) {
            word = many;
        } else if (mod10 == 1L) {
            word = one;
        } else if (mod10 >= 2L && mod10 <= 4L) {
            word = few;
        } else {
            word = many;
        }

        return value + " " + word;
    }
}
