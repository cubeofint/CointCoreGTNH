package coint.mixin.hee;

import java.io.OutputStream;

import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.performance.AsyncNbtWriter;

/**
 * Hardcore Ender Expansion gzips and writes its world data files on the server thread on every autosave (up to
 * ~300 ms per save). Keep building the NBT on the server thread, move compression and disk IO to a background writer.
 */
@Mixin(targets = "chylex.hee.system.savedata.WorldDataHandler", remap = false)
public abstract class MixinHEEWorldDataAsyncSave {

    @Redirect(
        method = "saveModified",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/nbt/CompressedStreamTools;func_74799_a(Lnet/minecraft/nbt/NBTTagCompound;Ljava/io/OutputStream;)V"),
        require = 0)
    private void cointcore$writeAsync(NBTTagCompound tag, OutputStream out) {
        AsyncNbtWriter.writeCompressed(tag, out, "HEE world data");
    }
}
