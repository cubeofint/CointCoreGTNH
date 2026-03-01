package coint.commands.tban;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.EnumChatFormatting;

public class TBan {

    public String banner;
    public String reason;
    public long expiresAt;

    public TBan() {}

    public TBan(ICommandSender sender, String reason, long durationMs) {
        this.banner = sender.getCommandSenderName();
        this.reason = reason;
        this.expiresAt = durationMs < 0 ? durationMs : System.currentTimeMillis() + durationMs;
    }

    public boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }

    public String getBanMessage() {
        String strDur = this.expiresAt < 0 ? "навсегда" : "до " + expireDate();
        return "Вы забанены " + strDur + " по причине: " + EnumChatFormatting.YELLOW + this.reason;
    }

    private String expireDate() {
        Instant instant = Instant.ofEpochMilli(this.expiresAt);
        ZonedDateTime zonedDateTime = instant.atZone(ZoneId.systemDefault());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
        return zonedDateTime.format(formatter);
    }
}
