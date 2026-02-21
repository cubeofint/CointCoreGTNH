package coint.commands.mute;

import net.minecraft.command.ICommandSender;

public class Mute {

    public String muter;
    public String reason;
    public long expiresAt;

    public Mute() {}

    public Mute(ICommandSender sender, String reason, long durationMs) {
        this.muter = sender.getCommandSenderName();
        this.reason = reason;
        this.expiresAt = System.currentTimeMillis() + durationMs;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
}
