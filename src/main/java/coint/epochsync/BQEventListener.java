package coint.epochsync;

import betterquesting.api.events.QuestEvent;
import betterquesting.api.utils.UuidConverter;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BQEventListener {

    public static final Map<UUID, String> epochMap = new HashMap<>();
    private static final Logger log = LogManager.getLogger(BQEventListener.class);

    static {
        epochMap.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAAAA=="), "bravebro");
        epochMap.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAADw=="), "stone");
        epochMap.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAALA=="), "steam");
        epochMap.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAAWA=="), "lv");
        epochMap.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAEfA=="), "mv");
        epochMap.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAAoA=="), "hv");
        epochMap.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAAsA=="), "ev");
        epochMap.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAA1Q=="), "iv");
        epochMap.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAF1Q=="), "luv");
        epochMap.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAKMg=="), "zpm");
        epochMap.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAKNQ=="), "uv");
        epochMap.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAKOA=="), "uhv");
        epochMap.put(UuidConverter.decodeUuid("k9PA3buhTvK1EJpPfA8ZAg=="), "uev");
        epochMap.put(UuidConverter.decodeUuid("u-1Y-eQ-TLyw-WGi5EEnVA=="), "uiv");
        epochMap.put(UuidConverter.decodeUuid("jlXvqqgIQ_Crha2tW0gEQg=="), "umv");
        epochMap.put(UuidConverter.decodeUuid("7wI7VuLKRe6lqAJpuYKKJg=="), "uxv");
        epochMap.put(UuidConverter.decodeUuid("AAAAAAAAAAAAAAAAAAAKJg=="), "stargateowner");
    }

    @SubscribeEvent
    public void onQuestApproval(QuestEvent event) {
        if (event.getType() == QuestEvent.Type.COMPLETED) {
            for (UUID questID : event.getQuestIDs()) {
                if (epochMap.containsKey(questID) ) {
                    String epoch = epochMap.get(questID);

                    UUID player = event.getPlayerID();
                    System.out.println(
                        event.getQuestIDs()
                            .toString() + " is " + epoch);

                    try {
                        SURanksManager.INST.setRank(player, epoch);
                    } catch (Exception e) {
                        log.error("Rank set error: ", e);
                    }
                }
            }
        }

        // Minecraft.getMinecraft().thePlayer.addChatComponentMessage(
        // new ChatComponentText(
        // event.getQuestIDs()
        // .toString()));
    }
}
