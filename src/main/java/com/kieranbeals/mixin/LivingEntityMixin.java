package com.kieranbeals.mixin;

import com.kieranbeals.GunpowderGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(
        method = "dropFromLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;Z)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void agf_filterLoot(ServerLevel level, DamageSource source, boolean recentlyHit, CallbackInfo ci) {
        if (!GunpowderGuard.allowLootDrop((LivingEntity) (Object) this, level, source)) {
            ci.cancel();
        }
    }
}
