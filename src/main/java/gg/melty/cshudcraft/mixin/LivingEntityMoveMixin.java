package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.csmove.CsMove;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.fluid.Fluid;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CS movement bypasses vanilla's travel for the player it drives (client: the local player, server: players that send
 * input) and keeps vanilla's own jump / swim-up / sink-down impulses off their velocity. All injectors are require = 0:
 * if one fails to apply, movement degrades to vanilla instead of crashing.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMoveMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true, require = 0)
    private void cshudcraft$travel(Vec3d input, CallbackInfo ci) {
        if (CsMove.cancelTravel((Entity) (Object) this)) ci.cancel();
    }

    @Inject(method = "jump", at = @At("HEAD"), cancellable = true, require = 0)
    private void cshudcraft$jump(CallbackInfo ci) {
        if (CsMove.cancelImpulse((Entity) (Object) this)) ci.cancel();
    }

    @Inject(method = "swimUpward", at = @At("HEAD"), cancellable = true, require = 0)
    private void cshudcraft$swimUp(TagKey<Fluid> fluid, CallbackInfo ci) {
        if (CsMove.cancelImpulse((Entity) (Object) this)) ci.cancel();
    }

    @Inject(method = "knockDownwards", at = @At("HEAD"), cancellable = true, require = 0)
    private void cshudcraft$sink(CallbackInfo ci) {
        if (CsMove.cancelImpulse((Entity) (Object) this)) ci.cancel();
    }

    @Inject(method = "getDimensions", at = @At("RETURN"), cancellable = true, require = 0)
    private void cshudcraft$hull(EntityPose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        EntityDimensions d = CsMove.hull((Entity) (Object) this, cir.getReturnValue());
        if (d != null) cir.setReturnValue(d);
    }
}
