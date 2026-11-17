package gg.melty.cshudcraft.pmove;

/**
 * Counter-Strike 1.6 style player movement (GoldSrc pmove), written from the documented behaviour: explicit friction and
 * acceleration as functions of velocity and frame time, a fixed substep, half-step gravity, ClipVelocity sliding with
 * overbounce 1.0, multi-plane clipping and step up/down. Pure Java, deterministic (StrictMath only), no Minecraft types.
 * Y is up; blocks and seconds.
 */
public final class CsPlayerMove {
    private static final double FLOOR_NY = 0.7;
    private static final int MAX_BUMPS = 4;
    private static final double TINY = 1e-6;

    private final MovementConfig c;
    private final Trace tr = new Trace();
    private final double[] ladderN = new double[2];
    private final double[][] planes = new double[MAX_BUMPS + 1][3];
    // wish scratch
    private double wdx, wdy, wdz, wspeed;
    private boolean blockedThisSlide;

    public CsPlayerMove(MovementConfig config) { this.c = config; }

    public MovementConfig config() { return c; }

    /** One Minecraft tick of input: substeps_per_tick fixed steps. */
    public void tick(PlayerMoveState s, UserCmd cmd, MoveWorld w) {
        boolean held = (cmd.buttons & UserCmd.JUMP) != 0;
        s.landed = false;
        s.jumpArmed = held && (c.autohop || (s.prevButtons & UserCmd.JUMP) == 0);
        for (int i = 0; i < c.substeps; i++) step(s, cmd, i, w);
        s.prevButtons = cmd.buttons;
    }

    /** One fixed substep (sub = 0..substeps-1 selects the interpolated view angles). */
    public void step(PlayerMoveState s, UserCmd cmd, int sub, MoveWorld w) {
        final double dt = c.dt;
        final double yaw = cmd.yawAt(sub, c.substeps), pitch = cmd.pitchAt(sub, c.substeps);
        s.yaw = yaw;
        s.pitch = pitch;
        s.impactVy = 0;
        clampVelocity(s);
        if (c.autohop && (cmd.buttons & UserCmd.JUMP) != 0) s.jumpArmed = true;
        if (s.ladderLockout > 0) s.ladderLockout = Math.max(0, s.ladderLockout - dt);
        if (s.jumpSlow > 0) s.jumpSlow = Math.max(0, s.jumpSlow - dt);
        updateDuck(s, cmd, w);
        categorize(s, w);

        double maxSpeed = Math.min(c.fromUnits(cmd.maxSpeed), c.maxSpeed);
        double scale = w.speedScale(s.x, s.y, s.z, c.hullHeight(s.ducking));
        if (scale < 1) {
            maxSpeed *= scale;
            double spd = s.speed2D();
            if (spd > maxSpeed && maxSpeed > 0) { double k = maxSpeed / spd; s.vx *= k; s.vz *= k; }
        }
        s.inLava = s.waterLevel > 0 && w.inLava(s.x, s.y, s.z, c.hullHeight(s.ducking));
        if (s.inLava) maxSpeed *= c.lavaScale;
        double gravity = c.gravity * cmd.gravityMul;
        boolean fly = cmd.levitate != 0;
        boolean plain = false; // the ground/air branch: the one that uses gravity

        if (s.waterJumpTime > 0) {
            waterJump(s, w, gravity, dt);
        } else if (s.waterLevel >= 2 && !fly) {
            waterMove(s, cmd, w, maxSpeed, yaw, pitch, dt);
        } else if (s.onLadder && !fly) {
            ladderMove(s, cmd, w, yaw, pitch, dt);
        } else {
            plain = true;
            if (s.jumpArmed && s.onGround) doJump(s, cmd, Math.min(c.fromUnits(cmd.maxSpeed), c.maxSpeed));
            if (fly) levitate(s, cmd, dt);
            else if (!s.onGround) s.vy -= gravity * dt * 0.5;
            if (s.onGround) {
                friction(s, w, dt, true);
                computeWish(cmd, yaw, pitch, maxSpeed, s, false, 0);
                accelerate(s, wdx, wdz, wspeed, c.accelerate * s.surfaceFriction, dt);
                s.vy = 0;
                stepSlide(s, w, dt);
            } else {
                computeWish(cmd, yaw, pitch, maxSpeed, s, false, 0);
                airAccelerate(s, wdx, wdz, wspeed, dt);
                slideMove(s, w, dt);
            }
        }
        categorize(s, w);
        if (plain && !fly && !s.onGround) s.vy -= gravity * dt * 0.5;
        if (s.onGround) s.vy = 0;
        clampVelocity(s);
    }

    // ------------------------------------------------------------------ helpers

    private void clampVelocity(PlayerMoveState s) {
        double m = c.maxVelocity;
        s.vx = Math.max(-m, Math.min(m, s.vx));
        s.vy = Math.max(-m, Math.min(m, s.vy));
        s.vz = Math.max(-m, Math.min(m, s.vz));
    }

    /** View-relative wish: cmd clamped to maxSpeed first, then the duck and walk multipliers. Result in wdx..wspeed. */
    private void computeWish(UserCmd cmd, double yaw, double pitch, double maxSpeed, PlayerMoveState s, boolean threeD, double extraUp) {
        double fm = cmd.forward * c.cmdSpeed, sm = cmd.side * c.cmdSpeed;
        double len = Math.sqrt(fm * fm + sm * sm);
        if (len > maxSpeed) { double k = maxSpeed / len; fm *= k; sm *= k; }
        if (s.ducking) { fm *= c.duckSpeedMul; sm *= c.duckSpeedMul; }
        if ((cmd.buttons & UserCmd.WALK) != 0) { fm *= c.walkSpeedMul; sm *= c.walkSpeedMul; }
        double yr = StrictMath.toRadians(yaw), sy = StrictMath.sin(yr), cy = StrictMath.cos(yr);
        double fx = -sy, fz = cy, rx = -cy, rz = -sy;
        double wx, wy = extraUp, wz;
        if (threeD) {
            double pr = StrictMath.toRadians(pitch), sp = StrictMath.sin(pr), cp = StrictMath.cos(pr);
            wx = fx * cp * fm + rx * sm;
            wy += -sp * fm;
            wz = fz * cp * fm + rz * sm;
        } else {
            wx = fx * fm + rx * sm;
            wz = fz * fm + rz * sm;
        }
        double spd = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (spd > TINY) { wdx = wx / spd; wdy = wy / spd; wdz = wz / spd; } else { wdx = wdy = wdz = 0; spd = 0; }
        if (spd > maxSpeed) spd = maxSpeed;
        wspeed = spd;
    }

    /** Ground/water accelerate: add speed along dir until the speed along it reaches wishSpeed. */
    public static void accelerate(PlayerMoveState s, double dx, double dz, double wishSpeed, double accel, double dt) {
        double current = s.vx * dx + s.vz * dz;
        double add = wishSpeed - current;
        if (add <= 0) return;
        double gain = Math.min(accel * dt * wishSpeed, add);
        s.vx += gain * dx;
        s.vz += gain * dz;
    }

    /** AirAccelerate semantics: the add-speed check uses min(wishspeed, cap) but the step size uses the full wishspeed. */
    private void airAccelerate(PlayerMoveState s, double dx, double dz, double wishSpeed, double dt) {
        double wishCapped = Math.min(wishSpeed, c.airWishCap);
        double current = s.vx * dx + s.vz * dz;
        double add = wishCapped - current;
        if (add <= 0) return;
        double gain = Math.min(c.airAccelerate * wishSpeed * dt, add);
        s.vx += gain * dx;
        s.vz += gain * dz;
    }

    private void accelerate3(PlayerMoveState s, double wishSpeed, double accel, double dt) {
        double current = s.vx * wdx + s.vy * wdy + s.vz * wdz;
        double add = wishSpeed - current;
        if (add <= 0) return;
        double gain = Math.min(accel * dt * wishSpeed, add);
        s.vx += gain * wdx;
        s.vy += gain * wdy;
        s.vz += gain * wdz;
    }

    private void friction(PlayerMoveState s, MoveWorld w, double dt, boolean ground) {
        double speed = Math.sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz);
        if (speed < TINY) { s.vx = s.vy = s.vz = 0; return; }
        double drop = 0;
        if (ground) {
            double f = c.friction * s.surfaceFriction;
            // about to walk off a ledge: a probe ahead finds no floor -> more friction
            double inv = 1.0 / Math.sqrt(s.vx * s.vx + s.vz * s.vz + TINY);
            w.trace(s.x + s.vx * inv * c.edgeLook, s.y, s.z + s.vz * inv * c.edgeLook, 0, -c.edgeDrop, 0, 0, 0, tr);
            if (tr.fraction >= 1) f *= c.edgeFriction;
            double control = speed < c.stopSpeed ? c.stopSpeed : speed;
            drop += control * f * dt;
        }
        if (s.waterLevel > 0) drop += speed * c.waterFriction * s.waterLevel * dt;
        double ns = Math.max(0, speed - drop) / speed;
        s.vx *= ns; s.vy *= ns; s.vz *= ns;
    }

    private void doJump(PlayerMoveState s, UserCmd cmd, double maxSpeed) {
        if (c.bhopCap) {
            double cap = c.bhopCapMul * maxSpeed, spd = s.speed2D();
            if (spd > cap && spd > 0) { double k = cap / spd; s.vx *= k; s.vz *= k; }
        }
        s.vy = c.jumpVelocity * cmd.jumpMul;
        s.onGround = false;
        s.jumpArmed = false;
        if (c.jumpSlowdownSec > 0) { // STUB (off by default), see movement.json jump_slowdown_*
            s.jumpSlow = c.jumpSlowdownSec;
            s.vx *= c.jumpSlowdownRatio;
            s.vz *= c.jumpSlowdownRatio;
        }
    }

    /** Vanilla levitation: vertical speed eases toward the effect's rise speed (20% per 50 ms), gravity off. */
    private void levitate(PlayerMoveState s, UserCmd cmd, double dt) {
        double k = 1 - StrictMath.pow(0.8, dt / 0.05);
        s.vy += (cmd.levitate - s.vy) * k;
    }

    // ------------------------------------------------------------------ ducking

    private void updateDuck(PlayerMoveState s, UserCmd cmd, MoveWorld w) {
        boolean want = (cmd.buttons & UserCmd.DUCK) != 0;
        double dt = c.dt;
        if (want) {
            s.duckTimer += dt;
            if (!s.ducking) {
                if (!s.onGround) {
                    s.ducking = true;           // air duck: legs tuck up, the box shrinks toward its middle
                    s.y += c.tuck;
                    s.addPoint();
                } else if (s.duckTimer >= c.duckTime) {
                    s.ducking = true;
                }
            }
        } else {
            s.duckTimer = 0;
            if (s.ducking) {
                if (s.onGround) {
                    if (w.free(s.x, s.y, s.z, c.halfWidth, c.standHeight)) s.ducking = false;
                } else if (w.free(s.x, s.y - c.tuck, s.z, c.halfWidth, c.standHeight)) {
                    s.y -= c.tuck;              // un-tuck in the air: the feet come back down
                    s.addPoint();
                    s.ducking = false;
                } else if (w.free(s.x, s.y, s.z, c.halfWidth, c.standHeight)) {
                    s.ducking = false;
                }
            }
        }
        double target = (s.ducking || want) ? 1 : 0;
        double rate = dt / c.duckTime;
        if (s.duckProgress < target) s.duckProgress = Math.min(target, s.duckProgress + rate);
        else if (s.duckProgress > target) s.duckProgress = Math.max(target, s.duckProgress - rate);
    }

    // ------------------------------------------------------------------ position classification

    private void categorize(PlayerMoveState s, MoveWorld w) {
        double h = c.hullHeight(s.ducking);
        s.waterLevel = w.waterLevel(s.x, s.y, s.z, h, c.eyeHeight(s));
        boolean wasGround = s.onGround;
        if (s.vy > c.groundLostVy) {
            s.onGround = false;
        } else {
            w.trace(s.x, s.y, s.z, 0, -c.groundCheck, 0, c.halfWidth, h, tr);
            if (tr.fraction < 1 && tr.ny >= FLOOR_NY) {
                if (tr.fraction > 0) { s.y -= c.groundCheck * tr.fraction; s.addPoint(); }
                s.onGround = true;
                s.surfaceFriction = surfaceFriction(w.slipperiness(s.x, s.y - c.groundCheck, s.z));
            } else {
                s.onGround = false;
            }
        }
        if (s.onGround && !wasGround) {
            s.landed = true;
            s.landSpeed = Math.max(s.impactVy, -s.vy);
        }
        if (!s.onGround) s.surfaceFriction = 1;
        boolean ladder = s.ladderLockout <= 0 && w.ladder(s.x, s.y, s.z, c.halfWidth, h, ladderN);
        s.onLadder = ladder;
    }

    /** Vanilla slipperiness -> GoldSrc surfacefriction (1 on ordinary blocks, c.iceFriction on ice, linear between). */
    public double surfaceFriction(double slipperiness) {
        if (slipperiness <= c.slipBase) return 1;
        double t = (slipperiness - c.slipBase) / (c.slipIce - c.slipBase);
        return Math.max(c.minSurfaceFriction, 1 - t * (1 - c.iceFriction));
    }

    // ------------------------------------------------------------------ ladders

    private void ladderMove(PlayerMoveState s, UserCmd cmd, MoveWorld w, double yaw, double pitch, double dt) {
        double nx = ladderN[0], nz = ladderN[1];
        if (s.jumpArmed) { // jump off: push away from the ladder (or backwards along the view)
            double yr = StrictMath.toRadians(yaw);
            double ox = nx, oz = nz;
            if (ox == 0 && oz == 0) { ox = StrictMath.sin(yr); oz = -StrictMath.cos(yr); }
            s.vx = ox * c.ladderJumpSpeed;
            s.vz = oz * c.ladderJumpSpeed;
            s.vy = 0;
            s.jumpArmed = false;
            s.onLadder = false;
            s.ladderLockout = c.ladderRegrab;
            slideMove(s, w, dt);
            return;
        }
        double fm = cmd.forward * c.ladderSpeed, sm = cmd.side * c.ladderSpeed;
        if (s.ducking) { fm *= c.duckSpeedMul; sm *= c.duckSpeedMul; }
        double yr = StrictMath.toRadians(yaw), pr = StrictMath.toRadians(pitch);
        double sy = StrictMath.sin(yr), cy = StrictMath.cos(yr), sp = StrictMath.sin(pr), cp = StrictMath.cos(pr);
        // look direction + forward = climb direction
        double wx = -sy * cp * fm + -cy * sm, wy = -sp * fm, wz = cy * cp * fm + -sy * sm;
        if (fm == 0 && sm == 0) {
            s.vx = s.vy = s.vz = 0;
        } else if (nx == 0 && nz == 0) {
            s.vx = wx; s.vy = wy; s.vz = wz;
        } else {
            double px = nz, pz = -nx;                  // along the ladder face, horizontal
            double lateral = wx * px + wz * pz, out = wx * nx + wz * nz;
            s.vx = px * lateral;
            s.vz = pz * lateral;
            s.vy = wy;
            if (s.onGround && out > 0) { s.vx += nx * out; s.vz += nz * out; } // walking away from the foot of a ladder
        }
        stepSlide(s, w, dt);
    }

    // ------------------------------------------------------------------ water

    private void waterMove(PlayerMoveState s, UserCmd cmd, MoveWorld w, double maxSpeed, double yaw, double pitch, double dt) {
        boolean jumpHeld = (cmd.buttons & UserCmd.JUMP) != 0;
        if (s.waterLevel == 2 && cmd.forward > 0 && checkWaterJump(s, w, yaw)) {
            waterJump(s, w, c.gravity * cmd.gravityMul, dt);
            return;
        }
        double up = jumpHeld ? c.waterUp : 0;
        boolean idle = cmd.forward == 0 && cmd.side == 0 && !jumpHeld;
        computeWish(cmd, yaw, pitch, maxSpeed, s, true, up);
        double wx = wdx * wspeed, wy = wdy * wspeed, wz = wdz * wspeed;
        if (idle) wy -= c.waterSink;
        double spd = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (spd > TINY) { wdx = wx / spd; wdy = wy / spd; wdz = wz / spd; } else { wdx = wdy = wdz = 0; spd = 0; }
        if (spd > maxSpeed) spd = maxSpeed;
        spd *= c.waterSpeedMul;
        friction(s, w, dt, false);
        accelerate3(s, spd, c.waterAccel, dt);
        slideMove(s, w, dt);
    }

    private boolean checkWaterJump(PlayerMoveState s, MoveWorld w, double yaw) {
        double yr = StrictMath.toRadians(yaw), fx = -StrictMath.sin(yr), fz = StrictMath.cos(yr);
        double h = c.hullHeight(s.ducking);
        w.trace(s.x, s.y, s.z, fx * c.waterJumpReach, 0, fz * c.waterJumpReach, c.halfWidth, h, tr);
        if (tr.fraction >= 1) return false;               // nothing ahead to climb out onto
        if (!w.free(s.x + fx * c.waterJumpReach, s.y + c.waterJumpLift, s.z + fz * c.waterJumpReach, c.halfWidth, h)) return false;
        s.vx = fx * c.waterJumpForward;
        s.vz = fz * c.waterJumpForward;
        s.vy = c.waterJumpUp;
        s.waterJumpTime = c.waterJumpTime;
        return true;
    }

    private void waterJump(PlayerMoveState s, MoveWorld w, double gravity, double dt) {
        s.vy -= gravity * dt * 0.5;
        slideMove(s, w, dt);
        s.vy -= gravity * dt * 0.5;
        s.waterJumpTime -= dt;
        if (s.waterJumpTime <= 0 || s.waterLevel == 0 || s.onGround) s.waterJumpTime = 0;
    }

    // ------------------------------------------------------------------ colliding moves

    /** ClipVelocity: remove the part of v that points into the plane (overbounce 1.0 = slide). */
    private static void clip(double[] v, double nx, double ny, double nz, double overbounce) {
        double back = v[0] * nx + v[1] * ny + v[2] * nz;
        back *= overbounce;
        v[0] -= nx * back; v[1] -= ny * back; v[2] -= nz * back;
        for (int i = 0; i < 3; i++) if (Math.abs(v[i]) < TINY) v[i] = 0;
    }

    /** Moves for dt along the velocity, sliding along up to MAX_BUMPS planes. */
    private void slideMove(PlayerMoveState s, MoveWorld w, double dt) {
        blockedThisSlide = false;
        double h = c.hullHeight(s.ducking);
        double[] orig = {s.vx, s.vy, s.vz}, prim = orig.clone(), v = new double[3];
        double timeLeft = dt;
        int np = 0;
        for (int bump = 0; bump < MAX_BUMPS; bump++) {
            if (s.vx == 0 && s.vy == 0 && s.vz == 0) break;
            double dx = s.vx * timeLeft, dy = s.vy * timeLeft, dz = s.vz * timeLeft;
            w.trace(s.x, s.y, s.z, dx, dy, dz, c.halfWidth, h, tr);
            if (tr.fraction > 0) {
                s.x += dx * tr.fraction; s.y += dy * tr.fraction; s.z += dz * tr.fraction;
                s.addPoint();
            }
            if (tr.fraction >= 1) break;
            blockedThisSlide = true;
            if (tr.ny >= FLOOR_NY && s.vy < 0) s.impactVy = Math.max(s.impactVy, -s.vy);
            timeLeft -= timeLeft * tr.fraction;
            if (np >= MAX_BUMPS) { s.vx = s.vy = s.vz = 0; break; }
            planes[np][0] = tr.nx; planes[np][1] = tr.ny; planes[np][2] = tr.nz;
            np++;
            // find a velocity that goes into none of the planes touched so far
            int i;
            for (i = 0; i < np; i++) {
                v[0] = orig[0]; v[1] = orig[1]; v[2] = orig[2];
                clip(v, planes[i][0], planes[i][1], planes[i][2], 1.0);
                int j;
                for (j = 0; j < np; j++) {
                    if (j != i && v[0] * planes[j][0] + v[1] * planes[j][1] + v[2] * planes[j][2] < -TINY) break;
                }
                if (j == np) break;
            }
            if (i != np) {
                s.vx = v[0]; s.vy = v[1]; s.vz = v[2];
            } else if (np == 2) {               // wedged in a crease: slide along the line where the planes meet
                double cx = planes[0][1] * planes[1][2] - planes[0][2] * planes[1][1];
                double cy = planes[0][2] * planes[1][0] - planes[0][0] * planes[1][2];
                double cz = planes[0][0] * planes[1][1] - planes[0][1] * planes[1][0];
                double d = cx * s.vx + cy * s.vy + cz * s.vz;
                s.vx = cx * d; s.vy = cy * d; s.vz = cz * d;
            } else {
                s.vx = s.vy = s.vz = 0;
                break;
            }
            // never turn back against where we started going
            if (s.vx * prim[0] + s.vy * prim[1] + s.vz * prim[2] <= 0) { s.vx = s.vy = s.vz = 0; break; }
        }
    }

    /**
     * Ground movement: slide; if that was blocked also try going up by the step height, sliding and coming back down,
     * and keep whichever got farther along the floor.
     */
    private void stepSlide(PlayerMoveState s, MoveWorld w, double dt) {
        final double sx = s.x, sy = s.y, sz = s.z, svx = s.vx, svy = s.vy, svz = s.vz;
        final int mark = s.pathLen;
        slideMove(s, w, dt);
        if (!blockedThisSlide || !s.onGround && !s.onLadder) return;
        final double fx = s.x, fy = s.y, fz = s.z, fvx = s.vx, fvy = s.vy, fvz = s.vz;
        final int flatLen = s.pathLen;
        double[] flatPath = java.util.Arrays.copyOfRange(s.path, mark, flatLen);
        final double h = c.hullHeight(s.ducking);
        // second try: up, slide, down
        s.x = sx; s.y = sy; s.z = sz; s.vx = svx; s.vy = svy; s.vz = svz;
        s.pathLen = mark;
        w.trace(s.x, s.y, s.z, 0, c.stepSize, 0, c.halfWidth, h, tr);
        if (tr.fraction > 0) { s.y += c.stepSize * tr.fraction; s.addPoint(); }
        slideMove(s, w, dt);
        w.trace(s.x, s.y, s.z, 0, -c.stepSize, 0, c.halfWidth, h, tr);
        boolean steep = tr.fraction < 1 && tr.ny < FLOOR_NY;
        if (tr.fraction < 1 && !steep) { s.y -= c.stepSize * tr.fraction; s.addPoint(); }
        else if (tr.fraction >= 1) { s.y -= c.stepSize; s.addPoint(); }
        double flatDist = (fx - sx) * (fx - sx) + (fz - sz) * (fz - sz);
        double stepDist = (s.x - sx) * (s.x - sx) + (s.z - sz) * (s.z - sz);
        if (steep || stepDist <= flatDist) {
            // the flat slide wins
            s.x = fx; s.y = fy; s.z = fz; s.vx = fvx; s.vy = fvy; s.vz = fvz;
            s.pathLen = mark;
            for (int i = 0; i < flatPath.length; i += 3) {
                s.x = flatPath[i]; s.y = flatPath[i + 1]; s.z = flatPath[i + 2];
                s.addPoint();
            }
            s.x = fx; s.y = fy; s.z = fz;
        } else {
            s.vy = fvy; // keep the vertical speed of the flat try
        }
    }
}
