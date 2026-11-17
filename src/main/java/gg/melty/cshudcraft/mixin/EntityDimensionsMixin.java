package gg.melty.cshudcraft.mixin;

import gg.melty.cshudcraft.csmove.CsMove;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The CS duck hull (sheets/movement.json duck_height) through the dimensions hook. Entity, LivingEntity and PlayerEntity
 * each get the same injector because whichever of them holds the player's override is the one that counts; the result
 * does not depend on the input, so applying it twice is harmless.
 */
@Mixin(Entity.class)
public abstract class EntityDimensionsMixin {
    @Inject(method = "getDimensions", at = @At("RETURN"), cancellable = true, require = 0)
    private void cshudcraft$hull(EntityPose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        EntityDimensions d = CsMove.hull((Entity) (Object) this, cir.getReturnValue());
        if (d != null) cir.setReturnValue(d);
    }
}
