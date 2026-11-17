package gg.melty.cshudcraft.csmove;

import gg.melty.cshudcraft.pmove.BoxSweep;
import gg.melty.cshudcraft.pmove.MoveWorld;
import gg.melty.cshudcraft.pmove.MovementConfig;
import gg.melty.cshudcraft.pmove.MovementSheet;
import gg.melty.cshudcraft.pmove.Trace;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.VineBlock;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * The pmove core's view of the Minecraft world: vanilla block collision shapes (so blocks, slabs, stairs, fences and
 * every modded shape behave as in the game), fluid levels, climbable blocks, slipperiness and speed blocks.
 * One instance per player; {@link #gather} is called once per tick before the core runs and caches the shapes
 * around the player, so the many sweeps of a tick never touch the world.
 */
public final class McMoveWorld implements MoveWorld {
    private final MovementConfig cfg = MovementSheet.get().config;
    private final BoxSweep sweep = new BoxSweep(cfg.contactEps);
    private final List<double[]> boxes = new ArrayList<>();
    private final BlockPos.Mutable pos = new BlockPos.Mutable();
    private World world;
    private Entity self;

    /** Collects the collision boxes within reach of one tick of movement from (x, y, z) at the given speed (blocks / s). */
    public void gather(World world, Entity self, double x, double y, double z, double speed) {
        this.world = world;
        this.self = self;
        boxes.clear();
        double reach = speed * (cfg.dt * cfg.substeps) + cfg.stepSize + cfg.value("search_margin");
        double hw = cfg.halfWidth + reach;
        Box region = new Box(x - hw, y - reach, z - hw, x + hw, y + cfg.standHeight + reach, z + hw);
        for (VoxelShape shape : world.getBlockCollisions(self, region)) {
            shape.forEachBox((a, b, c, d, e, f) -> boxes.add(new double[] {a, b, c, d, e, f}));
        }
    }

    @Override
    public void trace(double x, double y, double z, double dx, double dy, double dz, double halfW, double h, Trace out) {
        sweep.sweep(boxes, x, y, z, dx, dy, dz, halfW, h, out);
    }

    @Override
    public boolean free(double x, double y, double z, double halfW, double h) {
        return sweep.free(boxes, x, y, z, halfW, h);
    }

    private boolean liquidAt(double x, double y, double z, boolean lavaOnly) {
        pos.set(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
        FluidState fs = world.getFluidState(pos);
        if (fs.isEmpty()) return false;
        if (lavaOnly && !fs.isIn(FluidTags.LAVA)) return false;
        return y < pos.getY() + fs.getHeight(world, pos);
    }

    @Override
    public int waterLevel(double x, double y, double z, double h, double eyeHeight) {
        if (!liquidAt(x, y + cfg.contactEps, z, false)) return 0;
        if (!liquidAt(x, y + h * 0.5, z, false)) return 1;
        return liquidAt(x, y + eyeHeight, z, false) ? 3 : 2;
    }

    @Override
    public boolean inLava(double x, double y, double z, double h) {
        return liquidAt(x, y + cfg.contactEps, z, true) || liquidAt(x, y + h * 0.5, z, true);
    }

    @Override
    public boolean ladder(double x, double y, double z, double halfW, double h, double[] normalOut) {
        normalOut[0] = 0;
        normalOut[1] = 0;
        double m = cfg.contactEps;
        int x0 = MathHelper.floor(x - halfW + m), x1 = MathHelper.floor(x + halfW - m);
        int y0 = MathHelper.floor(y + m), y1 = MathHelper.floor(y + h - m);
        int z0 = MathHelper.floor(z - halfW + m), z1 = MathHelper.floor(z + halfW - m);
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    pos.set(bx, by, bz);
                    BlockState st = world.getBlockState(pos);
                    if (!st.isIn(BlockTags.CLIMBABLE)) continue;
                    if (st.contains(Properties.HORIZONTAL_FACING)) {
                        Direction f = st.get(Properties.HORIZONTAL_FACING);
                        normalOut[0] = f.getOffsetX();
                        normalOut[1] = f.getOffsetZ();
                    } else if (st.getBlock() instanceof VineBlock) {
                        // a vine hangs on the wall it is attached to; "out" is the opposite way
                        for (Direction d : Direction.Type.HORIZONTAL) {
                            if (st.get(VineBlock.getFacingProperty(d))) {
                                normalOut[0] -= d.getOffsetX();
                                normalOut[1] -= d.getOffsetZ();
                            }
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public double slipperiness(double x, double y, double z) {
        pos.set(MathHelper.floor(x), MathHelper.floor(y - 0.500001), MathHelper.floor(z)); // vanilla's "block affecting velocity"
        BlockState st = world.getBlockState(pos);
        return st.getBlock().getSlipperiness();
    }

    @Override
    public double speedScale(double x, double y, double z, double h) {
        MovementSheet sheet = MovementSheet.get();
        double scale = 1;
        double[] ys = {y + cfg.contactEps, y + h * 0.5, y - 0.500001};
        for (double py : ys) {
            pos.set(MathHelper.floor(x), MathHelper.floor(py), MathHelper.floor(z));
            Block b = world.getBlockState(pos).getBlock();
            Double s = sheet.blockSpeed.get(Registries.BLOCK.getId(b).toString());
            if (s != null && s < scale) scale = s;
        }
        return scale;
    }
}
