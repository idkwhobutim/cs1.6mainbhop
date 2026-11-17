package gg.melty.cshudcraft.pmove;

import java.util.ArrayList;
import java.util.List;

/** A tiny world of boxes for the tests: floor at y = 0 by default. */
final class BoxWorld implements MoveWorld {
    final List<double[]> boxes = new ArrayList<>();
    final BoxSweep sweep;
    int water;
    boolean lava, ladder;
    double ladderNx, ladderNz, slip = 0.6, scale = 1;

    BoxWorld(MovementConfig c, boolean floor) {
        sweep = new BoxSweep(c.contactEps);
        if (floor) boxes.add(new double[]{-1000, -1, -1000, 1000, 0, 1000});
    }

    BoxWorld add(double x0, double y0, double z0, double x1, double y1, double z1) {
        boxes.add(new double[]{x0, y0, z0, x1, y1, z1});
        return this;
    }

    @Override public void trace(double x, double y, double z, double dx, double dy, double dz, double hw, double h, Trace out) {
        sweep.sweep(boxes, x, y, z, dx, dy, dz, hw, h, out);
    }
    @Override public boolean free(double x, double y, double z, double hw, double h) { return sweep.free(boxes, x, y, z, hw, h); }
    @Override public int waterLevel(double x, double y, double z, double h, double eye) { return water; }
    @Override public boolean inLava(double x, double y, double z, double h) { return lava; }
    @Override public boolean ladder(double x, double y, double z, double hw, double h, double[] n) {
        n[0] = ladderNx; n[1] = ladderNz;
        return ladder;
    }
    @Override public double slipperiness(double x, double y, double z) { return slip; }
    @Override public double speedScale(double x, double y, double z, double h) { return scale; }
}
