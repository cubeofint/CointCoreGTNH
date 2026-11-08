package coint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import coint.cards.PacketCardState;

/** The card table this client sits at, as the server last told it. */
public final class ClientCards {

    /** Null when not seated. */
    static PacketCardState state;
    /** When the state came, for the turn timer. */
    static long receivedAt;

    private ClientCards() {}

    /** Leaving the server leaves its table too. */
    public static final class Disconnect {

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onDisconnect(cpw.mods.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            state = null;
        }
    }

    public static void receive(PacketCardState m) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> apply(mc, m));
    }

    /** A sound heard only by this player, not placed in the world. */
    static void sound(String name, float pitch) {
        Minecraft.getMinecraft()
            .getSoundHandler()
            .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation(name), pitch));
    }

    private static boolean myTurn(PacketCardState s) {
        return s != null && s.phase == PacketCardState.PHASE_PLAYING && s.turn == s.you;
    }

    private static void apply(Minecraft mc, PacketCardState m) {
        PacketCardState prev = state;
        receivedAt = System.currentTimeMillis();
        if (m.phase == PacketCardState.PHASE_NONE) {
            state = null;
            if (mc.currentScreen instanceof GuiCardTable) {
                mc.displayGuiScreen(null);
            }
            if (!m.error.isEmpty() && mc.thePlayer != null) {
                ChatComponentTranslation text = new ChatComponentTranslation(m.error);
                text.getChatStyle()
                    .setColor(EnumChatFormatting.RED);
                mc.thePlayer.addChatMessage(text);
            }
            return;
        }
        state = m;
        boolean turnBegins = myTurn(m) && !myTurn(prev);
        if (mc.currentScreen instanceof GuiCardTable gui) {
            gui.update(m);
            if (turnBegins) {
                gui.cueYourTurn();
            }
        } else if (m.open) {
            mc.displayGuiScreen(new GuiCardTable(m));
        } else if (prev != null) {
            // Window closed: one sound for what happened, so the table is heard from afar.
            int fresh = Math.min(m.logSeq - prev.logSeq, m.log.size());
            for (int i = m.log.size() - fresh; i < m.log.size(); i++) {
                switch (m.log.get(i).kind) {
                    case 7 -> sound("random.levelup", 1F);
                    case 8 -> sound("note.pling", 2F);
                    case 9 -> sound("note.bass", 0.6F);
                    default -> {}
                }
            }
            if (turnBegins) {
                sound("note.pling", 1.2F);
            }
        }
    }
}
