package coint.player;

import java.util.Date;

import net.minecraft.server.management.BanEntry;

import com.mojang.authlib.GameProfile;

public class TempBanEntry extends BanEntry {

    public TempBanEntry(GameProfile profile, String issuer, Date end, String reason) {
        super(profile, new Date(), issuer, end, reason);
    }

}
