package coint.commands.warn;

import java.time.Instant;

import net.minecraft.command.ICommandSender;

public class Warn {

    public String warner;
    public String reason;
    public String timestamp;

    public Warn() {}

    public Warn(ICommandSender sender, String reason) {
        this.warner = sender.getCommandSenderName();
        this.reason = reason;
        this.timestamp = Instant.now()
            .toString();
    }
}
