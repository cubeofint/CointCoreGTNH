package coint.mixin.magicbees;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.CointConfig;
import coint.performance.ChunkLoadGuard;

@Pseudo
@Mixin(targets = "magicbees.tileentity.TileEntityMagicApiary", remap = false)
public abstract class MixinTileEntityMagicApiaryChunkGuard {

    @Unique
    private static final int COINTCORE_AURA_SEARCH_RADIUS = 6;

    @Inject(method = "getChunksInSearchRange", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void cointcore$loadedAuraChunksOnly(CallbackInfoReturnable<List> cir) {
        TileEntity tile = (TileEntity) (Object) this;
        World world = tile.getWorldObj();
        if (!CointConfig.general.preventMagicApiaryChunkLoading || world == null || world.isRemote) {
            return;
        }

        List<Chunk> chunks = new ArrayList<Chunk>(4);
        cointcore$addLoadedChunk(
            chunks,
            world,
            tile.xCoord - COINTCORE_AURA_SEARCH_RADIUS,
            tile.zCoord - COINTCORE_AURA_SEARCH_RADIUS);
        cointcore$addLoadedChunk(
            chunks,
            world,
            tile.xCoord + COINTCORE_AURA_SEARCH_RADIUS,
            tile.zCoord - COINTCORE_AURA_SEARCH_RADIUS);
        cointcore$addLoadedChunk(
            chunks,
            world,
            tile.xCoord - COINTCORE_AURA_SEARCH_RADIUS,
            tile.zCoord + COINTCORE_AURA_SEARCH_RADIUS);
        cointcore$addLoadedChunk(
            chunks,
            world,
            tile.xCoord + COINTCORE_AURA_SEARCH_RADIUS,
            tile.zCoord + COINTCORE_AURA_SEARCH_RADIUS);
        cir.setReturnValue(chunks);
    }

    @Unique
    private static void cointcore$addLoadedChunk(List<Chunk> chunks, World world, int blockX, int blockZ) {
        if (!ChunkLoadGuard.isBlockChunkLoaded(world, blockX, blockZ)) {
            return;
        }
        IChunkProvider provider = world.getChunkProvider();
        Chunk chunk = provider.provideChunk(blockX >> 4, blockZ >> 4);
        if (!chunks.contains(chunk)) {
            chunks.add(chunk);
        }
    }
}
