package coint.chat;

/** A line's channel is independent of its text, language, rank and originating cluster node. */
public enum ChatChannel {

    LOCAL,
    GLOBAL,
    TRADE,
    PRIVATE,
    ADMIN;

    public enum View {

        ALL,
        LOCAL,
        GLOBAL,
        TRADE,
        PRIVATE,
        ADMIN;

        public boolean shows(ChatChannel channel) {
            return channel == null || this == ALL || name().equals(channel.name());
        }
    }
}
