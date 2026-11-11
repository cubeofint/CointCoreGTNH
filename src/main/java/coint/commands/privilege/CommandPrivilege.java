package coint.commands.privilege;

import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;

import coint.integration.serverutilities.CointRankConfigs;
import coint.util.TimeUtil;
import serverutils.lib.config.RankConfigAPI;
import serverutils.lib.math.Ticks;
import serverutils.lib.util.NBTUtils;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * Privilege commands carried over from the ATM10 server:
 * <ul>
 * <li>{@code /charge}: fills the energy of the item in hand.</li>
 * <li>{@code /maxcharge}: fills everything carried (inventory, armor, baubles).</li>
 * <li>{@code /expadd <levels>}: gives experience levels, up to the rank's limit.</li>
 * </ul>
 * Each needs its permission ({@code cointcore.command.<name>}); the cooldown and the /expadd limit come from the rank
 * ({@code cointcore.<name>.cooldown}, {@code cointcore.expadd.max_levels}).
 */
public class CommandPrivilege extends CommandBase {

    public enum Kind {

        CHARGE("charge", CointRankConfigs.CHARGE_COOLDOWN),
        MAXCHARGE("maxcharge", CointRankConfigs.MAXCHARGE_COOLDOWN),
        EXPADD("expadd", CointRankConfigs.EXPADD_COOLDOWN);

        final String name, cooldown;

        Kind(String name, String cooldown) {
            this.name = name;
            this.cooldown = cooldown;
        }
    }

    private final Kind kind;
    private final String permission;

    public CommandPrivilege(Kind kind) {
        this.kind = kind;
        this.permission = "cointcore.command." + kind.name;
        PermissionAPI.registerNode(permission, DefaultPermissionLevel.OP, "CointCore /" + kind.name);
    }

    @Override
    public String getCommandName() {
        return kind.name;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return kind == Kind.EXPADD ? "/expadd <уровни>" : "/" + kind.name;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return sender instanceof EntityPlayer player && PermissionAPI.hasPermission(player, permission);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        return Collections.emptyList();
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP player)) {
            return;
        }
        int levels = 0;
        if (kind == Kind.EXPADD) {
            if (args.length < 1) {
                throw new WrongUsageException(getCommandUsage(sender));
            }
            int max = RankConfigAPI.get(player, CointRankConfigs.EXPADD_MAX)
                .getInt();
            if (max <= 0) {
                error(sender, "Вашему рангу /expadd недоступна.");
                return;
            }
            levels = parseInt(sender, args[0]);
            if (levels <= 0) {
                error(sender, "Укажите число уровней больше нуля.");
                return;
            }
            if (levels > max) {
                error(sender, "За раз можно не больше " + max + " ур.");
                return;
            }
        }
        if (cooling(player)) {
            return;
        }
        boolean done = switch (kind) {
            case CHARGE -> chargeHand(player);
            case MAXCHARGE -> chargeAll(player);
            case EXPADD -> {
                player.addExperienceLevel(levels);
                ok(sender, "Получено " + levels + " ур. опыта.");
                yield true;
            }
        };
        if (done) {
            arm(player);
        }
    }

    private static boolean chargeHand(EntityPlayerMP player) {
        ItemStack held = player.getHeldItem();
        switch (ItemCharger.charge(held)) {
            case CHARGED -> {
                player.inventoryContainer.detectAndSendChanges();
                ok(player, "Предмет в руке заряжен.");
                return true;
            }
            case ALREADY_FULL -> {
                error(player, "Предмет в руке уже заряжен полностью.");
                return false;
            }
            default -> {
                error(player, "У предмета в руке нет энергии, которую можно зарядить.");
                return false;
            }
        }
    }

    private static boolean chargeAll(EntityPlayerMP player) {
        int charged = 0;
        for (ItemStack stack : ItemCharger.carried(player)) {
            if (ItemCharger.charge(stack) == ItemCharger.Result.CHARGED) {
                charged++;
            }
        }
        if (charged == 0) {
            error(player, "Нечего заряжать: всё уже полное или без энергии.");
            return false;
        }
        ItemCharger.markBaublesChanged(player);
        player.inventoryContainer.detectAndSendChanges();
        ok(player, "Заряжено предметов: " + charged + ".");
        return true;
    }

    private String tag() {
        return "cointcore_" + kind.name + "_last_ms";
    }

    /** True (and tells the player) while the cooldown runs. */
    private boolean cooling(EntityPlayerMP player) {
        long cooldownMs = cooldownMs(player);
        if (cooldownMs <= 0) {
            return false;
        }
        NBTTagCompound persisted = NBTUtils.getPersistedData(player, true);
        long last = persisted.getLong(tag());
        long elapsed = System.currentTimeMillis() - last;
        if (last > 0 && elapsed < cooldownMs) {
            error(player, "Снова можно через " + TimeUtil.formatDuration(cooldownMs - elapsed + 999L));
            return true;
        }
        return false;
    }

    private void arm(EntityPlayerMP player) {
        if (cooldownMs(player) > 0) {
            NBTUtils.getPersistedData(player, true)
                .setLong(tag(), System.currentTimeMillis());
        }
    }

    private long cooldownMs(EntityPlayerMP player) {
        long ticks = RankConfigAPI.get(player, kind.cooldown)
            .getLong();
        return ticks <= 0 ? 0
            : Ticks.get(ticks)
                .millis();
    }

    private static void ok(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText("§a" + text));
    }

    private static void error(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText("§c" + text));
    }
}
