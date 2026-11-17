package gg.melty.cshudcraft.csmove;

import gg.melty.cshudcraft.mixin.EntityAccessor;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/** Fail-soft wrapper over the accessor mixin: if the mixin did not apply, vanilla's cobweb slowdown just stacks on top. */
final class EntityAccess {
    private EntityAccess() {}

    static void noMovementMultiplier(Entity e) {
        if (e instanceof EntityAccessor a) a.cshudcraft$setMovementMultiplier(Vec3d.ZERO);
    }
}
