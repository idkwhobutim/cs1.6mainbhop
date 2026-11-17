package gg.melty.cshudcraft.pmove;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static gg.melty.cshudcraft.pmove.TestSupport.*;

import java.util.Random;
import org.junit.Test;

public class CsPlayerMoveTest {
    private static PlayerMoveState onFloor() {
        PlayerMoveState s = new PlayerMoveState();
        s.onGround = true;
        return s;
    }

    private static double units(MovementConfig c, double blocksPerSecond) { return c.toUnits(blocksPerSecond); }

    /** Runs whole ticks of the same cmd. */
    private static void run(CsPlayerMove m, PlayerMoveState s, UserCmd cmd, BoxWorld w, int ticks) {
        for (int i = 0; i < ticks; i++) m.tick(s, cmd, w);
    }

    // ------------------------------------------------------------------ jump

    private static double apexUnits(MovementConfig c) {
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true);
        PlayerMoveState s = onFloor();
        UserCmd cmd = idle(250);
        m.step(s, cmd, 0, w); // settle
        double max = 0;
        s.jumpArmed = true;
        for (int i = 0; i < 200; i++) {
            m.step(s, cmd, 0, w);
            max = Math.max(max, s.y);
        }
        return max / c.unitScale;
    }

    @Test public void jumpApexIs45UnitsAt10ms() {
        assertEquals(45.0, apexUnits(cfg()), 1.0);
    }

    @Test public void jumpApexStaysWithin2UnitsAt20ms() {
        assertEquals(45.0, apexUnits(cfg().with("substep_ms", 20)), 2.0);
    }

    @Test public void jumpTakeoffSpeedMatchesSqrt2gh() {
        MovementConfig c = cfg();
        assertEquals(268.33, units(c, c.jumpVelocity), 0.01);
    }

    // ------------------------------------------------------------------ ground

    @Test public void knifeAccelerateReaches250In0_2s() {
        MovementConfig c = cfg();
        PlayerMoveState s = new PlayerMoveState();
        double wish = bps(c, 250);
        int steps = 0;
        while (s.vx < wish - 1e-9 && steps < 1000) { CsPlayerMove.accelerate(s, 1, 0, wish, c.accelerate, c.dt); steps++; }
        assertEquals(0.2, steps * c.dt, 0.011);
    }

    @Test public void knifeRunWithFrictionReaches250InAboutPointFourSeconds() {
        // the acceleration alone is 0.2 s; friction (4 x speed per second) makes the full ground start take about twice that
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true);
        PlayerMoveState s = onFloor();
        UserCmd cmd = cmd(0, 1, 0, 0f, 250);
        double t = 0;
        while (units(c, s.speed2D()) < 249.9 && t < 2) { m.step(s, cmd, 0, w); t += c.dt; }
        assertEquals(0.42, t, 0.06);
    }

    private static double stopTime(MovementConfig c) {
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true);
        PlayerMoveState s = onFloor();
        s.vx = bps(c, 250);
        UserCmd cmd = idle(250);
        double t = 0;
        while (s.speed2D() > 0 && t < 5) { m.step(s, cmd, 0, w); t += c.dt; }
        return t;
    }

    @Test public void stopFrom250WithStopspeed100TakesAbout0_48s() {
        MovementConfig c = cfg();
        assertEquals(0.48, stopTime(c.with("stopspeed", bps(c, 100))), 0.03);
    }

    @Test public void stopFrom250WithShippedStopspeedTakesAbout0_55s() {
        assertEquals(0.545, stopTime(cfg()), 0.03);
    }

    private static double cruise(MovementConfig c, int buttons, float fwd, float side, double maxUnits) {
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true);
        PlayerMoveState s = onFloor();
        UserCmd cmd = cmd(buttons, fwd, side, 0f, (float) maxUnits);
        run(m, s, cmd, w, 60);
        return units(c, s.speed2D());
    }

    @Test public void ak47RunsAt221() { assertEquals(221, cruise(cfg(), 0, 1, 0, 221), 0.5); }
    @Test public void diagonalIsNotFaster() { assertEquals(221, cruise(cfg(), 0, 1, 1, 221), 0.5); }
    @Test public void walkIs0_52OfMaxSpeed() { assertEquals(221 * 0.52, cruise(cfg(), UserCmd.WALK, 1, 0, 221), 0.5); }
    @Test public void duckIs0_333OfMaxSpeed() { assertEquals(221 * 0.333, cruise(cfg(), UserCmd.DUCK, 1, 0, 221), 0.5); }
    @Test public void maxSpeedIsClampedToGlobalMaxspeed() { assertEquals(320, cruise(cfg(), 0, 1, 0, 900), 0.5); }

    @Test public void crouchWalkWithAkNeedsStopspeedBelow74() {
        MovementConfig c = cfg();
        // with the task sheet's stopspeed 100 friction (4/step) beats acceleration (3.7/step): a ducked AK never gets going
        assertTrue(cruise(c.with("stopspeed", bps(c, 100)), UserCmd.DUCK, 1, 0, 221) < 10);
        assertTrue(cruise(c, UserCmd.DUCK, 1, 0, 221) > 70);
    }

    @Test public void iceKeepsSlidingLongerThanStone() {
        MovementConfig c = cfg();
        BoxWorld stone = new BoxWorld(c, true), ice = new BoxWorld(c, true);
        ice.slip = 0.98;
        double a = stopTimeIn(c, stone), b = stopTimeIn(c, ice);
        assertTrue(b > a * 2);
    }

    private static double stopTimeIn(MovementConfig c, BoxWorld w) {
        CsPlayerMove m = new CsPlayerMove(c);
        PlayerMoveState s = onFloor();
        s.surfaceFriction = 1;
        s.vx = bps(c, 250);
        double t = 0;
        while (s.speed2D() > 0 && t < 20) { m.step(s, idle(250), 0, w); t += c.dt; }
        return t;
    }

    // ------------------------------------------------------------------ bhop and air

    @Test public void bhopCapHoldsAt1_2xMaxSpeed() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true);
        PlayerMoveState s = onFloor();
        s.vx = bps(c, 600);
        s.jumpArmed = true;
        m.step(s, idle(250), 0, w);
        assertEquals(300, units(c, s.speed2D()), 1.0);
        assertFalse(s.onGround);
    }

    @Test public void bhopCapCanBeSwitchedOff() {
        MovementConfig c = cfg().with("bhop_cap", 0);
        CsPlayerMove m = new CsPlayerMove(c);
        PlayerMoveState s = onFloor();
        s.vx = bps(c, 600);
        s.jumpArmed = true;
        m.step(s, idle(250), 0, new BoxWorld(c, true));
        assertTrue(units(c, s.speed2D()) > 590);
    }

    @Test public void idealAirStrafeGains25PerStepWhileProjectionUnder5() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, false);
        PlayerMoveState s = new PlayerMoveState();
        s.y = 100;
        s.vz = bps(c, 300);                       // flying "forward" (yaw 0 faces +z)
        UserCmd right = cmd(0, 0, 1, 0f, 250);    // strafe right = -x, perpendicular to the velocity
        double before = s.vx;
        m.step(s, right, 0, w);
        assertEquals(25.0, units(c, Math.abs(s.vx - before)), 0.01);
    }

    @Test public void airAddSpeedIsCappedAt30() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, false);
        PlayerMoveState s = new PlayerMoveState();
        s.y = 100;
        UserCmd right = cmd(0, 0, 1, 0f, 250);
        for (int i = 0; i < 10; i++) m.step(s, right, 0, w);
        assertEquals(30.0, units(c, -s.vx), 0.01);   // projection stops at the 30 cap
        double v = s.vx;
        m.step(s, right, 0, w);
        assertEquals(v, s.vx, 1e-12);
    }

    @Test public void strafeGainPerSecondScalesWithStepCount() {
        MovementConfig c = cfg();
        double perSecond10 = gainPerSecond(c);
        double perSecond50 = gainPerSecond(c.with("substep_ms", 50));
        assertTrue("10 ms steps should strafe at least 4x faster than 50 ms steps: " + perSecond10 + " vs " + perSecond50,
                perSecond10 > perSecond50 * 4);
    }

    private static double gainPerSecond(MovementConfig c) {
        // always strafe perpendicular to the velocity (the ideal strafe) and measure how much speed one second adds
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, false);
        PlayerMoveState s = new PlayerMoveState();
        s.y = 1000;
        s.vz = bps(c, 300);
        double start = s.speed2D();
        int steps = (int) Math.round(1.0 / c.dt);
        for (int i = 0; i < steps; i++) {
            double ang = Math.toDegrees(Math.atan2(s.vx, s.vz)) ;
            // yaw such that "right" (= -cos y, -sin y) is perpendicular and turning toward the strafe side
            float yaw = (float) (-Math.toDegrees(Math.atan2(s.vz, -s.vx)) + 180 + 0 * ang);
            m.step(s, cmd(0, 0, 1, yaw, 250), 0, w);
        }
        return units(c, s.speed2D() - start);
    }

    // ------------------------------------------------------------------ jump button

    private static int countJumps(MovementConfig c, boolean holdWholeTime) {
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true);
        PlayerMoveState s = onFloor();
        run(m, s, idle(250), w, 2);
        int jumps = 1;  // the first press
        for (int i = 0; i < 200; i++) {
            m.tick(s, cmd(UserCmd.JUMP, 0, 0, 0f, 250), w);
            if (s.landed) jumps++;   // every landing that is followed by a jump while held is an extra jump
            if (!holdWholeTime) break;
        }
        return jumps;
    }

    @Test public void heldSpaceDoesNotRejump() {
        // one jump, one landing, then the player stays on the ground for the rest of the 10 s of held space
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true);
        PlayerMoveState s = onFloor();
        run(m, s, idle(250), w, 2);
        int landings = 0;
        for (int i = 0; i < 200; i++) {
            m.tick(s, cmd(UserCmd.JUMP, 0, 0, 0f, 250), w);
            if (s.landed) landings++;
        }
        assertEquals(1, landings);
        assertTrue(s.onGround);
    }

    @Test public void autohopOptionRejumps() { assertTrue(countJumps(cfg().with("autohop", 1), true) > 5); }

    @Test public void autobhopButtonRejumpsOnLanding() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true);
        PlayerMoveState s = onFloor();
        run(m, s, idle(250), w, 2);
        int landings = 0;
        for (int i = 0; i < 200; i++) { // 10 s of held Space with the option on
            m.tick(s, cmd(UserCmd.JUMP | UserCmd.AUTOBHOP, 0, 0, 0f, 250), w);
            if (s.landed) landings++;
        }
        assertTrue(landings > 5);
        // and without the bit the same input still jumps once (no autohop)
        PlayerMoveState t = onFloor();
        run(m, t, idle(250), w, 2);
        int plain = 0;
        for (int i = 0; i < 200; i++) {
            m.tick(t, cmd(UserCmd.JUMP, 0, 0, 0f, 250), w);
            if (t.landed) plain++;
        }
        assertEquals(1, plain);
    }

    @Test public void freshPressAfterReleaseJumpsAgain() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true);
        PlayerMoveState s = onFloor();
        UserCmd press = cmd(UserCmd.JUMP, 0, 0, 0f, 250), up = idle(250);
        m.tick(s, press, w);
        assertFalse(s.onGround);
        run(m, s, press, w, 40);                     // lands while still holding
        assertTrue(s.onGround);
        m.tick(s, up, w);                            // release
        m.tick(s, press, w);                         // press again
        assertFalse(s.onGround);
    }

    @Test public void tapThatEndsBeforeLandingStillJumpsOnLandingInsideTheTick() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true);
        PlayerMoveState s = new PlayerMoveState();
        s.y = 0.02;                                   // about to touch down
        s.vy = -bps(c, 300);
        m.tick(s, cmd(UserCmd.JUMP, 0, 0, 0f, 250), w);
        assertTrue("jump was buffered inside the tick and fired on touchdown", s.vy > 0 || !s.onGround);
    }

    // ------------------------------------------------------------------ duck

    @Test public void groundDuckFlipsAfterDuckTimeAndTapDoesNot() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true);
        PlayerMoveState s = onFloor();
        UserCmd duck = cmd(UserCmd.DUCK, 0, 0, 0f, 250);
        m.tick(s, duck, w);                           // 50 ms: not yet
        assertFalse(s.ducking);
        run(m, s, duck, w, 5);
        assertTrue(s.ducking);
        assertEquals(1.0, s.duckProgress, 1e-9);
        run(m, s, idle(250), w, 10);
        assertFalse(s.ducking);
        // a tap shorter than duck_time never changes the hull
        PlayerMoveState t = onFloor();
        m.tick(t, duck, w);
        m.tick(t, idle(250), w);
        assertFalse(t.ducking);
    }

    @Test public void airDuckTucksLegsUp0_45AndReleaseLowersThem() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, false);
        PlayerMoveState s = new PlayerMoveState();
        s.y = 50;
        m.step(s, cmd(UserCmd.DUCK, 0, 0, 0f, 250), 0, w);
        assertTrue(s.ducking);
        assertEquals(50 + 0.45, s.y, 1e-3);
        m.step(s, idle(250), 0, w);
        assertFalse(s.ducking);
        assertEquals(50.0, s.y, 0.02);
    }

    @Test public void cannotUnduckUnderLowCeiling() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true).add(-5, 1.2, -5, 5, 2, 5);
        PlayerMoveState s = onFloor();
        s.ducking = true;
        s.duckProgress = 1;
        run(m, s, idle(250), w, 5);
        assertTrue(s.ducking);
        assertTrue(new BoxWorld(c, true).free(0, 0, 0, c.halfWidth, c.standHeight));
    }

    @Test public void duckJumpClearsAHigherLedge() {
        MovementConfig c = cfg();
        // a ledge whose top is 1.4 blocks up: a plain jump (apex 1.125) cannot reach it, jump + air duck can
        double plain = apexOnLedge(c, false), ducked = apexOnLedge(c, true);
        assertTrue(plain < 1.4 && ducked >= 1.4);
    }

    private static double apexOnLedge(MovementConfig c, boolean duck) {
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true).add(0.5, 0, -1, 3, 1.4, 1);
        PlayerMoveState s = onFloor();
        s.x = 0; s.vx = bps(c, 120);
        run(m, s, idle(250), w, 0);
        s.jumpArmed = true;
        double best = 0;
        for (int i = 0; i < 150; i++) {
            UserCmd cmd = (i > 3 && duck) ? cmd(UserCmd.DUCK, 1, 0, -90f, 250) : cmd(0, 1, 0, -90f, 250);
            m.step(s, cmd, 0, w);
            best = Math.max(best, s.y);
            if (s.x > 0.8 && s.onGround) break;
        }
        return best;
    }

    // ------------------------------------------------------------------ collision

    @Test public void stepsUpHalfBlockSlabsButNotFullBlocks() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true).add(1, 0, -5, 5, 0.5, 5);
        PlayerMoveState s = onFloor();
        s.yaw = -90;
        run(m, s, cmd(0, 1, 0, -90f, 250), w, 14);     // yaw -90 faces +x
        assertTrue("walked up onto the slab, y=" + s.y, s.x > 2 && Math.abs(s.y - 0.5) < 0.01);
        BoxWorld wall = new BoxWorld(c, true).add(1, 0, -5, 5, 1.0, 5);
        PlayerMoveState t = onFloor();
        run(m, t, cmd(0, 1, 0, -90f, 250), wall, 40);
        assertTrue("a full block is a wall, x=" + t.x, t.x < 1.0 && t.y < 0.01);
    }

    @Test public void slidesAlongAWallKeepingTheParallelSpeed() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true).add(1, 0, -50, 2, 5, 50);
        PlayerMoveState s = onFloor();
        s.x = 0.69;
        s.vx = bps(c, 200);
        s.vz = bps(c, 200);
        UserCmd cmd = idle(250);
        m.step(s, cmd, 0, w);
        assertEquals(0, s.vx, 1e-9);
        assertTrue(s.vz > 0);
        assertTrue(s.x < 1.0);
    }

    @Test public void landingReportsImpactSpeed() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true);
        PlayerMoveState s = new PlayerMoveState();
        s.y = 5;
        boolean landed = false;
        for (int i = 0; i < 30 && !landed; i++) { m.tick(s, idle(250), w); landed = s.landed; }
        assertTrue(landed);
        assertTrue(s.onGround);
        double expected = Math.sqrt(2 * c.gravity * 5);
        assertEquals(expected, s.landSpeed, 0.3);
    }

    @Test public void csFallDamageIsLinearBetweenSafeAndFatal() {
        MovementConfig c = cfg();
        assertEquals(0, c.csFallFraction(580), 1e-9);
        assertEquals(1, c.csFallFraction(1024), 1e-9);
        assertEquals(0.5, c.csFallFraction(802), 1e-9);
        assertEquals(0, c.csFallFraction(300), 1e-9);
    }

    // ------------------------------------------------------------------ ladder and water

    @Test public void lookUpAndForwardClimbsLookDownDescendsJumpLeaves() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, true);
        w.ladder = true; w.ladderNx = 0; w.ladderNz = -1;
        PlayerMoveState s = new PlayerMoveState();
        s.y = 2;
        UserCmd up = new UserCmd(0, 1, 0, 0f, 0f, -45f, -45f, 250, 1, 1, 0);
        m.tick(s, up, w);
        assertEquals(200 * Math.sin(Math.toRadians(45)), units(c, s.vy), 0.5);
        double y = s.y;
        UserCmd down = new UserCmd(0, 1, 0, 0f, 0f, 45f, 45f, 250, 1, 1, 0);
        m.tick(s, down, w);
        assertTrue(s.y < y);
        m.tick(s, idle(250), w);
        assertEquals(0, s.vy, 1e-9);
        m.tick(s, cmd(UserCmd.JUMP, 0, 0, 0f, 250), w);
        assertEquals(270, units(c, Math.hypot(s.vx, s.vz)), 5.0);
        assertTrue(s.vz < 0);
    }

    @Test public void swimmingSinksSlowlyWhenIdleAndMovesWhereYouLook() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        BoxWorld w = new BoxWorld(c, false);
        w.water = 3;
        PlayerMoveState s = new PlayerMoveState();
        s.y = 10;
        run(m, s, idle(250), w, 40);
        assertTrue(s.vy < 0 && units(c, s.vy) > -61);
        PlayerMoveState t = new PlayerMoveState();
        UserCmd up = new UserCmd(0, 1, 0, 0f, 0f, -90f, -90f, 250, 1, 1, 0);
        run(m, t, up, w, 20);
        assertTrue(t.vy > 0 && units(c, t.vy) <= 250 * 0.8 + 0.5);
    }

    @Test public void waterJumpLiftsYouOntoALedge() {
        MovementConfig c = cfg();
        CsPlayerMove m = new CsPlayerMove(c);
        // water up to y=1.2 in front of a ledge whose top is at 1.0 (hull reaches it)
        BoxWorld w = new BoxWorld(c, false).add(-5, -5, 0.4, 5, 0.7, 5);
        w.water = 2;
        PlayerMoveState s = new PlayerMoveState();
        s.y = 0;
        m.tick(s, cmd(0, 1, 0, 0f, 250), w);          // yaw 0 faces +z, toward the ledge
        assertTrue("water jump started", s.waterJumpTime > 0 && s.vy > 0);
    }

    // ------------------------------------------------------------------ determinism

    @Test public void clientAndServerCoresAgreeOnAnyCmdStream() {
        MovementConfig c = cfg();
        CsPlayerMove client = new CsPlayerMove(c), server = new CsPlayerMove(c);
        BoxWorld wc = new BoxWorld(c, true).add(3, 0, -3, 4, 0.5, 3).add(-4, 0, -4, -3, 2, 4);
        BoxWorld ws = new BoxWorld(c, true).add(3, 0, -3, 4, 0.5, 3).add(-4, 0, -4, -3, 2, 4);
        PlayerMoveState a = onFloor(), b = onFloor();
        Random r = new Random(1234);
        float yaw = 0;
        for (int i = 0; i < 2000; i++) {
            float ny = yaw + (r.nextFloat() - 0.5f) * 90f;
            int buttons = (r.nextInt(4) == 0 ? UserCmd.JUMP : 0) | (r.nextInt(5) == 0 ? UserCmd.DUCK : 0) | (r.nextInt(7) == 0 ? UserCmd.WALK : 0);
            UserCmd cmd = new UserCmd(buttons, r.nextInt(3) - 1, r.nextInt(3) - 1, yaw, ny, 0f, 5f, 250f, 1f, 1f, 0f);
            yaw = ny;
            client.tick(a, cmd, wc);
            server.tick(b, cmd, ws);
            assertTrue("diverged at tick " + i, a.sameAs(b));
            a.clearPath(); b.clearPath();
        }
    }

    // ------------------------------------------------------------------ sheet

    @Test public void sheetLoadsAndWeaponSpeedsAreThere() {
        MovementSheet sh = sheet();
        assertEquals(0.025, sh.config.unitScale, 1e-12);
        assertEquals(5, sh.config.substeps);
        assertEquals(0.01, sh.config.dt, 1e-12);
        assertTrue(sh.scopedSpeed.containsKey("awp"));
        assertEquals(0.4, sh.blockSpeed.get("minecraft:soul_sand"), 1e-12);
    }

    @Test
    public void packUnpackRoundTripsEveryField() {
        PlayerMoveState a = new PlayerMoveState();
        a.x = 1.5; a.y = -2; a.z = 3.25; a.vx = 4; a.vy = -5; a.vz = 6;
        a.onGround = true; a.ducking = true; a.onLadder = true; a.inLava = true;
        a.duckProgress = 0.4; a.duckTimer = 0.1; a.waterJumpTime = 0.7; a.ladderLockout = 0.05; a.jumpSlow = 0.3;
        a.surfaceFriction = 0.25; a.waterLevel = 2; a.prevButtons = 5; a.landSpeed = 9; a.yaw = 12; a.pitch = -3; a.landed = true;
        double[] packed = a.pack();
        assertEquals(PlayerMoveState.PACK, packed.length);
        PlayerMoveState b = new PlayerMoveState();
        b.unpack(packed);
        assertTrue(a.sameAs(b));
        assertEquals(a.yaw, b.yaw, 0);
        assertEquals(a.pitch, b.pitch, 0);
        assertEquals(a.landed, b.landed);
    }
}
