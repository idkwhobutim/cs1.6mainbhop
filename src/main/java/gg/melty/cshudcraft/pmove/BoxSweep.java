package gg.melty.cshudcraft.pmove;

import java.util.List;

/** Swept box-versus-boxes test shared by the game adapter and the unit tests. Boxes are {minX, minY, minZ, maxX, maxY, maxZ}. */
public final class BoxSweep {
    private final double eps;

    public BoxSweep(double contactEpsilon) { this.eps = contactEpsilon; }

    public void sweep(List<double[]> boxes, double x, double y, double z, double dx, double dy, double dz,
                      double halfW, double h, Trace out) {
        out.reset();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) return;
        double hMinX = x - halfW, hMaxX = x + halfW, hMinY = y, hMaxY = y + h, hMinZ = z - halfW, hMaxZ = z + halfW;
        double best = 2;
        int bestAxis = -1;
        for (double[] b : boxes) {
            // already overlapping (strictly): ignored so the player can always walk out of a bad spot
            if (hMaxX > b[0] && hMinX < b[3] && hMaxY > b[1] && hMinY < b[4] && hMaxZ > b[2] && hMinZ < b[5]) {
                out.startSolid = true;
                continue;
            }
            double entry = Double.NEGATIVE_INFINITY, exit = Double.POSITIVE_INFINITY;
            int axis = -1;
            boolean miss = false;
            for (int i = 0; i < 3 && !miss; i++) {
                double d = i == 0 ? dx : i == 1 ? dy : dz;
                double hMin = i == 0 ? hMinX : i == 1 ? hMinY : hMinZ;
                double hMax = i == 0 ? hMaxX : i == 1 ? hMaxY : hMaxZ;
                double bMin = b[i], bMax = b[i + 3];
                double tEnter, tExit;
                if (d == 0) {
                    if (hMax <= bMin || hMin >= bMax) { miss = true; break; }
                    continue;
                } else if (d > 0) {
                    tEnter = (bMin - hMax) / d;
                    tExit = (bMax - hMin) / d;
                } else {
                    tEnter = (bMax - hMin) / d;
                    tExit = (bMin - hMax) / d;
                }
                if (tEnter > entry) { entry = tEnter; axis = i; }
                if (tExit < exit) exit = tExit;
            }
            if (miss || axis < 0) continue;
            if (entry >= exit || exit <= 0 || entry > 1) continue;
            if (entry < 0) entry = 0;
            if (entry < best) { best = entry; bestAxis = axis; }
        }
        if (bestAxis < 0) return;
        out.fraction = Math.max(0, best - eps / len);
        double d = bestAxis == 0 ? dx : bestAxis == 1 ? dy : dz;
        double n = d > 0 ? -1 : 1;
        out.nx = bestAxis == 0 ? n : 0;
        out.ny = bestAxis == 1 ? n : 0;
        out.nz = bestAxis == 2 ? n : 0;
    }

    public boolean free(List<double[]> boxes, double x, double y, double z, double halfW, double h) {
        for (double[] b : boxes) {
            if (x + halfW > b[0] && x - halfW < b[3] && y + h > b[1] && y < b[4] && z + halfW > b[2] && z - halfW < b[5]) return false;
        }
        return true;
    }
}
