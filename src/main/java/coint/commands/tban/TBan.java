package coint.commands.tban;

import net.minecraft.command.ICommandSender;

public class TBan {

    public String banner;
    public String reason;
    public long expiresAt;

    public TBan() {}

    public TBan(ICommandSender sender, String reason, long durationMs) {
        this.banner = sender.getCommandSenderName();
        this.reason = reason;
        this.expiresAt = System.currentTimeMillis() + durationMs;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
}
