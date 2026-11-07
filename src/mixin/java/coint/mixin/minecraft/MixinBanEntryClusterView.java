package coint.mixin.minecraft;

import java.util.Date;

import net.minecraft.server.management.BanEntry;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import coint.cluster.ClusterBanEntry;

/** Lets the cluster read who made a ban and when, to share it with the other servers. */
@Mixin(BanEntry.class)
public abstract class MixinBanEntryClusterView implements ClusterBanEntry {

    @Shadow
    @Final
    protected Date banStartDate;

    @Shadow
    @Final
    protected String bannedBy;

    @Override
    public Object cointcore$key() {
        return ((AccessorUserListEntry) this).cointcore$invokeKey();
    }

    @Override
    public Date cointcore$created() {
        return banStartDate;
    }

    @Override
    public String cointcore$source() {
        return bannedBy;
    }
}
