package coint.mixin.minecraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import net.minecraft.server.management.UserList;
import net.minecraft.server.management.UserListEntry;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.cluster.ClusterBanList;
import coint.cluster.ClusterSanctions;

/**
 * Every ban and pardon goes through these two methods, whatever command or mod made it: the cluster shares them with
 * the other servers. It tells the ban lists from the white list and the ops itself.
 */
@Mixin(UserList.class)
public abstract class MixinUserListClusterSync implements ClusterBanList {

    @Shadow
    @Final
    private Map<String, UserListEntry> field_152696_d;

    @Override
    public Collection<Object> cointcore$entries() {
        return new ArrayList<>(field_152696_d.values());
    }

    @Inject(method = "func_152687_a", at = @At("TAIL"))
    private void cointcore$shareAdded(UserListEntry entry, CallbackInfo ci) {
        ClusterSanctions.onLocalAdded(this, entry);
    }

    @Inject(method = "func_152684_c", at = @At("TAIL"))
    private void cointcore$shareRemoved(Object key, CallbackInfo ci) {
        ClusterSanctions.onLocalRemoved(this, key);
    }
}
