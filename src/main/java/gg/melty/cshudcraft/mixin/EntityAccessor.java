package gg.melty.cshudcraft.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cobweb / berry-bush slowdown: vanilla multiplies the next move by this. In CS movement the sheet's block_speed table replaces it. */
@Mixin(Entity.class)
public interface EntityAccessor {
    @Accessor("movementMultiplier")
    void cshudcraft$setMovementMultiplier(Vec3d value);
}
