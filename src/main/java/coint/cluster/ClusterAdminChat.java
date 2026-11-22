package coint.cluster;

import coint.chat.AdminChat;

/** Reserved DM envelope: old nodes cannot mistake this sensitive channel for public/global chat. */
public final class ClusterAdminChat {

    private static final String TARGET = "$coint-adminchat";

    private ClusterAdminChat() {}

    public static void publish(String sender, String display, String text) {
        ClusterService.publishTo(ClusterService.KIND_DM, sender, display, TARGET, text);
    }

    static boolean isMessage(ClusterDatabase.ChatRow row) {
        return ClusterService.KIND_DM.equals(row.kind) && TARGET.equals(row.target);
    }

    static void receive(ClusterDatabase.ChatRow row) {
        AdminChat.receive(row.senderFormatted, row.text, row.nodeName);
    }
}
