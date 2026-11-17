package gg.melty.cshudcraft.pmove;

/**
 * The only way the pmove core sees the world. Hulls are feet-centred boxes: (x, y, z) is the middle of the bottom face,
 * halfW is half the width, h the height. The Minecraft side implements this over vanilla collision shapes.
 */
public interface MoveWorld {
    /** Sweeps the hull from (x, y, z) by (dx, dy, dz). out.fraction is already pulled back by the contact epsilon. */
    void trace(double x, double y, double z, double dx, double dy, double dz, double halfW, double h, Trace out);

    /** True if the hull at this place overlaps nothing solid. */
    boolean free(double x, double y, double z, double halfW, double h);

    /** 0 dry, 1 feet wet, 2 waist wet, 3 eyes wet (any liquid). */
    int waterLevel(double x, double y, double z, double h, double eyeHeight);

    /** The liquid the player stands in is lava (Minecraft keeps burning the player itself). */
    boolean inLava(double x, double y, double z, double h);

    /** The hull touches a climbable block. normalOut = horizontal normal (x, z) pointing out of the ladder, or 0, 0. */
    boolean ladder(double x, double y, double z, double halfW, double h, double[] normalOut);

    /** Vanilla slipperiness of the block the player stands on (0.6 for normal blocks). */
    double slipperiness(double x, double y, double z);

    /** Speed multiplier of what the player is in or on (soul sand, honey, cobweb, powder snow...), 1 normally. */
    double speedScale(double x, double y, double z, double h);
}
