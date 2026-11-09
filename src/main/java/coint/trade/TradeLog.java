package coint.trade;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.minecraft.item.ItemStack;

import coint.CointCore;
import coint.integration.discord.DiscordModerationWebhook;
import cpw.mods.fml.common.registry.GameRegistry;

/** Finished trades: to {@code logs/trades.log} and to the trade channel in Discord. */
final class TradeLog {

    private TradeLog() {}

    static void log(String a, String b, List<ItemStack> fromA, List<ItemStack> fromB) {
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String line = "[" + when
            + "] "
            + a
            + " <-> "
            + b
            + " | "
            + a
            + " отдал: "
            + list(fromA, true)
            + " | "
            + b
            + " отдал: "
            + list(fromB, true);
        write(line);
        CointCore.LOG.info("[Trade] {}", line);
        String discord = "**Обмен** " + when
            + "\n**"
            + a
            + "** ⇄ **"
            + b
            + "**\n"
            + a
            + " отдал → "
            + b
            + ": "
            + list(fromA, false)
            + "\n"
            + b
            + " отдал → "
            + a
            + ": "
            + list(fromB, false);
        DiscordModerationWebhook.logTrade(discord);
    }

    /** Same items merged: "64x Iron Ingot (minecraft:iron_ingot@0)"; "ничего" for an empty side. */
    private static String list(List<ItemStack> stacks, boolean ids) {
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack stack : stacks) {
            ItemStack into = null;
            for (ItemStack m : merged) {
                if (TradeManager.same(m, stack)) {
                    into = m;
                    break;
                }
            }
            if (into != null) {
                into.stackSize += stack.stackSize;
            } else {
                merged.add(stack.copy());
            }
        }
        if (merged.isEmpty()) {
            return "ничего";
        }
        StringBuilder out = new StringBuilder();
        for (ItemStack stack : merged) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(stack.stackSize)
                .append("x ")
                .append(name(stack));
            if (ids) {
                GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(stack.getItem());
                out.append(" (")
                    .append(uid == null ? "?" : uid.modId + ":" + uid.name)
                    .append('@')
                    .append(stack.getItemDamage())
                    .append(stack.hasTagCompound() ? " +nbt" : "")
                    .append(')');
            }
        }
        return out.toString();
    }

    private static String name(ItemStack stack) {
        try {
            return stack.getDisplayName()
                .replaceAll("§.", "");
        } catch (RuntimeException e) {
            return String.valueOf(stack.getItem());
        }
    }

    private static synchronized void write(String line) {
        File dir = new File("logs");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        try (Writer out = new OutputStreamWriter(
            new FileOutputStream(new File(dir, "trades.log"), true),
            StandardCharsets.UTF_8)) {
            out.write(line);
            out.write(System.lineSeparator());
        } catch (Exception e) {
            CointCore.LOG.warn("[Trade] Could not write the trade log", e);
        }
    }
}
