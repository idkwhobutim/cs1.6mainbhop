package gg.melty.cshudcraft.pmove;

/** Result of sweeping a hull through the world: how far it got (0..1) and the plane it stopped on. */
public final class Trace {
    public double fraction = 1;
    public double nx, ny, nz;
    /** The hull already overlapped something solid where it started. */
    public boolean startSolid;

    public void reset() {
        fraction = 1;
        nx = ny = nz = 0;
        startSolid = false;
    }
}
