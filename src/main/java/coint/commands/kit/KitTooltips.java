package coint.commands.kit;

import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@EventBusSubscriber
public final class KitTooltips {

    private KitTooltips() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTooltip(ItemTooltipEvent event) {
        KitOrigin origin = KitOrigin.get(event.itemStack);
        if (origin == null) {
            return;
        }
        // No font renderer here: NEI can request tooltips on a worker thread.
        event.toolTip.add(EnumChatFormatting.GOLD + "Данный предмет был получен из кита " + origin.getKit());
        event.toolTip.add(EnumChatFormatting.GOLD + "игроком " + origin.getRecipient() + "!");
        event.toolTip.add(EnumChatFormatting.RED + "Торговля и обмен данным предметом запрещены");
        event.toolTip.add(EnumChatFormatting.RED + "правилами проекта!");
    }
}
