package coint.mixin.minecraft;

import net.minecraft.server.management.UserListEntry;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(UserListEntry.class)
public interface AccessorUserListEntry {

    /** What the entry is about: a GameProfile, or an IP. Package-private in vanilla. */
    @Invoker("func_152640_f")
    Object cointcore$invokeKey();
}
