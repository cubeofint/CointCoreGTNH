package coint.events;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.util.ChatUtil;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import ru.cube.cchat.events.ChannelMessageEvent;

@EventBusSubscriber
public class ChatColorHandler {

    @SubscribeEvent
    public static void onChannelMessage(ChannelMessageEvent event) {
        String colorCode = ChatUtil.getTextColorCode(event.sender);
        if (colorCode.isEmpty()) return;

        event.text = event.text.replaceAll("(?<=^|\\s)", colorCode);
    }
}
