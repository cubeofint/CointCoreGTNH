package coint.cluster;

import net.minecraft.util.ChatComponentText;

import com.google.gson.Gson;

import coint.commands.spy.LocalSpyRegistry;
import coint.commands.spy.PersonalSpyRegistry;

/** Moderation copies, separate from public chat and sent only once by the originating node. */
public final class ClusterSpy {

    private static final Gson JSON = new Gson();
    private static final String LOCAL = "spylocal", DM = "spydm";
    // Older nodes treat this as a DM to a nonexistent player, never as public chat.
    private static final String TARGET = "$coint-spy";

    private ClusterSpy() {}

    public static String label(String name) {
        return label(name, ClusterService.isRunning() ? ClusterService.displayName() : null);
    }

    public static String label(String name, String node) {
        return name + (node == null || node.isEmpty() ? "" : " §8[" + node + "]§r");
    }

    public static void local(String name, String display, int dimension, String text) {
        Copy copy = new Copy();
        copy.type = LOCAL;
        copy.sender = name;
        copy.senderDisplay = display;
        copy.dimension = dimension;
        copy.text = text;
        ClusterService.publishTo(ClusterService.KIND_DM, name, display, TARGET, JSON.toJson(copy));
    }

    public static void dm(String sender, String target, String senderDisplay, String targetDisplay, String text) {
        Copy copy = new Copy();
        copy.type = DM;
        copy.sender = sender;
        copy.target = target;
        copy.senderDisplay = senderDisplay;
        copy.targetDisplay = targetDisplay;
        copy.text = text;
        PersonalSpyRegistry.deliver(sender, target, senderDisplay, targetDisplay, new ChatComponentText(text));
        ClusterService.publishTo(ClusterService.KIND_DM, sender, senderDisplay, TARGET, JSON.toJson(copy));
    }

    static boolean isMessage(ClusterDatabase.ChatRow row) {
        return ClusterService.KIND_DM.equals(row.kind) && TARGET.equals(row.target);
    }

    static void receive(ClusterDatabase.ChatRow row) {
        final Copy copy;
        try {
            copy = JSON.fromJson(row.text, Copy.class);
            if (copy == null || copy.sender == null || copy.senderDisplay == null || copy.text == null) return;
        } catch (RuntimeException e) {
            return;
        }
        if (LOCAL.equals(copy.type)) {
            LocalSpyRegistry.deliverRemote(label(copy.senderDisplay, row.nodeName), copy.dimension, copy.text);
        } else if (DM.equals(copy.type) && copy.target != null && copy.targetDisplay != null) {
            PersonalSpyRegistry.deliver(
                copy.sender,
                copy.target,
                copy.senderDisplay,
                copy.targetDisplay,
                new ChatComponentText(copy.text));
        }
    }

    private static final class Copy {

        String type, sender, target, senderDisplay, targetDisplay, text;
        int dimension;
    }
}
