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
        return prefix(node) + name;
    }

    public static String prefix(String node) {
        return node == null || node.isEmpty() ? "" : "§8[" + node + "]§r ";
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

    public static void dm(String sender, String target, String senderDisplay, String targetDisplay, String targetNode,
        String text) {
        Copy copy = new Copy();
        copy.type = DM;
        copy.sender = sender;
        copy.target = target;
        copy.senderDisplay = senderDisplay;
        copy.targetDisplay = targetDisplay;
        copy.senderNode = ClusterService.isRunning() ? ClusterService.displayName() : null;
        copy.targetNode = targetNode == null ? copy.senderNode : targetNode;
        copy.text = text;
        deliverDm(copy, false);
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
            LocalSpyRegistry.deliverRemote(copy.senderDisplay, row.nodeName, copy.dimension, copy.text);
        } else if (DM.equals(copy.type) && copy.target != null && copy.targetDisplay != null) {
            deliverDm(copy, true);
        }
    }

    private static void deliverDm(Copy copy, boolean remote) {
        boolean crossNode = copy.senderNode != null && !copy.senderNode.equals(copy.targetNode);
        String heading = remote || crossNode ? prefix(copy.senderNode) : "";
        PersonalSpyRegistry.deliver(
            copy.sender,
            copy.target,
            copy.senderDisplay,
            crossNode ? label(copy.targetDisplay, copy.targetNode) : copy.targetDisplay,
            heading,
            new ChatComponentText(copy.text));
    }

    private static final class Copy {

        String type, sender, target, senderDisplay, targetDisplay, senderNode, targetNode, text;
        int dimension;
    }
}
