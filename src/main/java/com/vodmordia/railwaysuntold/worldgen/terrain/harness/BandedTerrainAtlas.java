package com.vodmordia.railwaysuntold.worldgen.terrain.harness;

/**
 * Fixed coordinate-band terrain map for gametests. A flat baseline everywhere, with one terrain
 * feature centred in each of several disjoint coordinate bands so independent features never
 * interfere. Each feature is a pure function of (x, z), so chunks stay stable for the duration of a
 * run while the planner and placement pipeline get something other than flat ground to react to.
 *
 * The world floor is Y=-64 and the flat baseline sits at Y=-61 (just above it, so the gametest
 * arenas placed near Y=-59 stay above ground). That leaves no room to carve downward, so the
 * down-features (the dip and the river) sit on a locally raised shelf - terrain ramps up from the
 * baseline, the feature is cut into the shelf, then it ramps back down - keeping every block above
 * the world floor while still presenting the planner a dip/channel relative to its approaches.
 *
 * Feature sizes deliberately exceed the planner's detector thresholds so the corresponding logic
 * triggers: the peak rises above PEAK_RISE_THRESHOLD (25) within MAX_TUNNEL_SPAN (384); the dip
 * exceeds MIN_DIP_DEPTH (12) at more than MIN_DIP_STEEPNESS (0.35); the slope's sides are steeper
 * than the config max slope ratio (0.25); the river carries real water for the water-bridge check.
 */
public final class BandedTerrainAtlas implements TerrainAtlas {

    /** Top solid block Y of the flat baseline. Mirrors the vanilla flat preset's grass-block top. */
    public static final int FLAT_TOP_Y = -61;

    // Radial hill near the origin: a general "non-flat solid terrain renders" anchor.
    public static final int HILL_CENTER_X = 80;
    public static final int HILL_CENTER_Z = 16;
    public static final int HILL_RADIUS = 48;
    public static final int HILL_HEIGHT = 56;

    // Peak ridge (tunnel candidate): rise 40 > 25, footprint 120 < 384. Runs along z.
    public static final int PEAK_CENTER_X = 4096;
    public static final int PEAK_HALF_WIDTH = 60;
    public static final int PEAK_RISE = 40;

    // Boundary-case peaks: bracket the tunnel rise threshold (25). A sub-threshold peak is
    // ramped over (no tunnel); a just-above peak tunnels. Same half-width as the main peak.
    public static final int PEAK_SUB_CENTER_X = 20480;
    public static final int PEAK_SUB_RISE = 22;  // < 25 -> no tunnel
    public static final int PEAK_AT_CENTER_X = 24576;
    public static final int PEAK_AT_RISE = 27;   // > 25 -> tunnel

    // Dip valley (bridge candidate): a notch cut into a raised, flat-topped mesa. The flat mesa
    // shoulders (rather than a sharp ridge) keep the planner from inserting a dense run of vertical-
    // curve waypoints over the descent - that densification inflates CamelHumpDetector's
    // index-based span estimate and pushes the measured steepness below MIN_DIP_STEEPNESS (0.35),
    // so the dip reads as a gentle slope instead of a bridgeable ravine. Floor sits DIP_DEPTH (16 >
    // 12) below the mesa; the notch wall slopes 16/24 ≈ 0.67 > 0.35.
    // The notch floor must be wide enough that the planner's advised-Y actually reaches the bottom
    // (a narrow pit gets smoothed away), and the wall steep enough that - after step3_5 roughly
    // doubles waypoint density on slopes, halving CamelHumpDetector's index-based steepness - the
    // measured descent still clears MIN_DIP_STEEPNESS (0.35). A 20-drop over a 20-wide wall is a 1.0
    // terrain slope (~0.5 measured), over a 48-wide floor; depth 20 > MIN_DIP_DEPTH (12).
    public static final int DIP_CENTER_X = 8192;
    public static final int DIP_SHELF_RISE = 20;   // mesa top above the flat baseline (-41)
    public static final int DIP_FLOOR_HALF = 24;   // half-width of the notch floor (48 wide)
    public static final int DIP_WALL = 20;         // notch wall width (20 drop -> 1.0 slope)
    public static final int DIP_MESA = 40;         // flat mesa shoulder each side of the notch
    public static final int DIP_OUTER_RAMP = 80;   // gentle ramp baseline -> mesa (20/80 = 0.25)
    public static final int DIP_DEPTH = 20;

    // Slope band (ramp enforcement): a flat-topped step whose sides slope 16/40 = 0.4 > 0.25.
    public static final int SLOPE_CENTER_X = 12288;
    public static final int SLOPE_PLATEAU_HALF = 24;
    public static final int SLOPE_RAMP_WIDTH = 40;
    public static final int SLOPE_RISE = 16;

    // River band (water-bridge candidate): a channel cut into a raised shelf and filled with water
    // flush to the banks. Bed sits RIVER_DEPTH (8) below the shelf, well above the world floor.
    public static final int RIVER_CENTER_X = 16384;
    public static final int RIVER_SHELF_RISE = 20;
    public static final int RIVER_HALF_WIDTH = 24;
    public static final int RIVER_BANK = 12;
    public static final int RIVER_OUTER_RAMP = 48;
    public static final int RIVER_DEPTH = 8;

    // Drift band (planner-drift / SM2): real blocks rise into a hump, but plannerHeight stays flat -
    // the planner plans a flat route where the world actually rises. When placement scans the real
    // ground and follows it up, the head ends above the next flat-planned segment's start, tripping
    // the [PLANNER-DRIFT] guard and forcing a replan. Runs along z so a head can't steer around it.
    public static final int DRIFT_CENTER_X = 28672;
    public static final int DRIFT_HALF_WIDTH = 40;
    public static final int DRIFT_RISE = 10;

    // Seeded parameter sweep: SWEEP_N feature bands whose shapes (kind/height/width + a
    // small surface jitter) are drawn from a fixed, logged seed. The fixed seed keeps the always-on
    // sweep deterministic and gate-safe while still covering varied shapes the fixed cases don't; a
    // failure prints the offending band's params and the seed so it replays exactly. A heavier or
    // varying-seed sweep is left as opt-in exploration.
    public static final long SWEEP_SEED = 0x5241494C57415953L; // "RAILWAYS"
    public static final int SWEEP_N = 12;
    public static final int SWEEP_BASE_X = 32768;
    public static final int SWEEP_BAND_WIDTH = 4096;
    private static final int SWEEP_FOOTPRINT = 220; // feature half-extent; < SWEEP_BAND_WIDTH / 2

    private final int[] sweepType = new int[SWEEP_N];
    private final int[] sweepAmp = new int[SWEEP_N];
    private final int[] sweepWidth = new int[SWEEP_N];
    private final int[] sweepJitter = new int[SWEEP_N];

    public BandedTerrainAtlas() {
        for (int i = 0; i < SWEEP_N; i++) {
            java.util.Random rng = new java.util.Random(SWEEP_SEED + i * 0x9E3779B97F4A7C15L);
            sweepType[i] = rng.nextInt(3);        // 0 peak, 1 slope step, 2 double peak
            sweepAmp[i] = 15 + rng.nextInt(40);   // 15..54 rise
            sweepWidth[i] = 36 + rng.nextInt(64); // 36..99 feature half-width
            sweepJitter[i] = rng.nextInt(3);      // 0..2 blocks of surface jitter
        }
    }

    /** World X of sweep band {@code i}'s centre. */
    public static int sweepBandCenterX(int i) {
        return SWEEP_BASE_X + i * SWEEP_BAND_WIDTH;
    }

    /** Human-readable params for sweep band {@code i} - printed on failure so the shape replays. */
    public String sweepBandDesc(int i) {
        String[] kinds = {"peak", "slope", "doublePeak"};
        return kinds[sweepType[i]] + " amp=" + sweepAmp[i] + " width=" + sweepWidth[i]
                + " jitter=" + sweepJitter[i];
    }

    @Override
    public int groundHeight(int x, int z) {
        int hill = hillGround(x, z);
        if (hill != Integer.MIN_VALUE) {
            return hill;
        }
        int dx = Math.abs(x - PEAK_CENTER_X);
        if (dx < PEAK_HALF_WIDTH) {
            return FLAT_TOP_Y + cosineBump(dx, PEAK_HALF_WIDTH, PEAK_RISE);
        }
        int dxSub = Math.abs(x - PEAK_SUB_CENTER_X);
        if (dxSub < PEAK_HALF_WIDTH) {
            return FLAT_TOP_Y + cosineBump(dxSub, PEAK_HALF_WIDTH, PEAK_SUB_RISE);
        }
        int dxAt = Math.abs(x - PEAK_AT_CENTER_X);
        if (dxAt < PEAK_HALF_WIDTH) {
            return FLAT_TOP_Y + cosineBump(dxAt, PEAK_HALF_WIDTH, PEAK_AT_RISE);
        }
        if (Math.abs(x - DIP_CENTER_X) <= DIP_FLOOR_HALF + DIP_WALL + DIP_OUTER_RAMP) {
            return dipGround(Math.abs(x - DIP_CENTER_X));
        }
        if (Math.abs(x - SLOPE_CENTER_X) <= SLOPE_PLATEAU_HALF + SLOPE_RAMP_WIDTH) {
            return FLAT_TOP_Y + slopeStep(Math.abs(x - SLOPE_CENTER_X));
        }
        if (Math.abs(x - RIVER_CENTER_X) <= RIVER_HALF_WIDTH + RIVER_BANK + RIVER_OUTER_RAMP) {
            return riverGround(Math.abs(x - RIVER_CENTER_X));
        }
        int dxDrift = Math.abs(x - DRIFT_CENTER_X);
        if (dxDrift < DRIFT_HALF_WIDTH) {
            return FLAT_TOP_Y + cosineBump(dxDrift, DRIFT_HALF_WIDTH, DRIFT_RISE);
        }
        if (x >= SWEEP_BASE_X - SWEEP_BAND_WIDTH / 2) {
            int i = Math.round((float) (x - SWEEP_BASE_X) / SWEEP_BAND_WIDTH);
            if (i >= 0 && i < SWEEP_N) {
                int d = x - sweepBandCenterX(i);
                if (Math.abs(d) <= SWEEP_FOOTPRINT) {
                    return FLAT_TOP_Y + sweepFeature(i, d) + sweepJitterAt(i, x);
                }
            }
        }
        return FLAT_TOP_Y;
    }

    @Override
    public int plannerHeight(int x, int z) {
        // The drift band's real ground rises into a hump, but the planner sees flat baseline here -
        // it plans flat where the world actually rises, so placement drifts off the plan.
        if (Math.abs(x - DRIFT_CENTER_X) < DRIFT_HALF_WIDTH) {
            return FLAT_TOP_Y;
        }
        return groundHeight(x, z);
    }

    @Override
    public boolean isWater(int x, int z) {
        return Math.abs(x - RIVER_CENTER_X) <= RIVER_HALF_WIDTH;
    }

    @Override
    public int waterSurface(int x, int z) {
        // Flush with the banks: water fills the carved channel up to the top of the raised shelf.
        return FLAT_TOP_Y + RIVER_SHELF_RISE;
    }

    /** Radial cosine hill; returns {@link Integer#MIN_VALUE} when (x, z) is outside the hill. */
    private static int hillGround(int x, int z) {
        long dx = (long) x - HILL_CENTER_X;
        long dz = (long) z - HILL_CENTER_Z;
        double dist = Math.sqrt((double) (dx * dx + dz * dz));
        if (dist >= HILL_RADIUS) {
            return Integer.MIN_VALUE;
        }
        return FLAT_TOP_Y + (int) Math.round(HILL_HEIGHT * Math.cos((dist / HILL_RADIUS) * (Math.PI / 2.0)));
    }

    /** A smooth cosine bump of the given amplitude at horizontal distance {@code d} from a centre. */
    private static int cosineBump(int d, int halfWidth, int amplitude) {
        if (d >= halfWidth) {
            return 0;
        }
        return (int) Math.round(amplitude * Math.cos(((double) d / halfWidth) * (Math.PI / 2.0)));
    }

    /** A flat-topped step: a plateau of {@code SLOPE_RISE} with linearly sloped sides. */
    private static int slopeStep(int d) {
        if (d <= SLOPE_PLATEAU_HALF) {
            return SLOPE_RISE;
        }
        if (d <= SLOPE_PLATEAU_HALF + SLOPE_RAMP_WIDTH) {
            double t = (double) (d - SLOPE_PLATEAU_HALF) / SLOPE_RAMP_WIDTH;
            return (int) Math.round(SLOPE_RISE * (1.0 - t));
        }
        return 0;
    }

    /** Height (above baseline) of sweep band {@code i}'s feature at signed offset {@code d}. */
    private int sweepFeature(int i, int d) {
        int amp = sweepAmp[i];
        int w = sweepWidth[i];
        switch (sweepType[i]) {
            case 0: // single cosine peak
                return cosineBump(Math.abs(d), w, amp);
            case 1: { // flat-topped slope step (plateau 2w/3, ramped sides width w)
                int ad = Math.abs(d);
                int plateauHalf = w / 3;
                if (ad <= plateauHalf) {
                    return amp;
                }
                if (ad <= plateauHalf + w) {
                    double t = (double) (ad - plateauHalf) / w;
                    return (int) Math.round(amp * (1.0 - t));
                }
                return 0;
            }
            default: // two cosine peaks separated by w, each half-width w/2 (a saddle between them)
                int half = Math.max(8, w / 2);
                return cosineBump(Math.abs(d - w), half, amp)
                        + cosineBump(Math.abs(d + w), half, amp);
        }
    }

    /** A few blocks of deterministic surface jitter for sweep band {@code i} at world X {@code x},
     *  to mimic bumpy real terrain and exercise the planner's smoothing passes. */
    private int sweepJitterAt(int i, int x) {
        if (sweepJitter[i] == 0) {
            return 0;
        }
        return (int) Math.round(sweepJitter[i] * Math.sin(x * 0.6));
    }

    /** A notch cut into a raised, flat-topped mesa: floor at the centre, a steep wall up to the flat
     *  mesa, then a gentle ramp back down to the flat baseline. The flat mesa between the wall and
     *  the outer ramp avoids a sharp ridge, so the route stays cleanly sampled across the descent.
     *  {@code d} is the horizontal distance from the band centre. */
    private static int dipGround(int d) {
        int mesaTop = FLAT_TOP_Y + DIP_SHELF_RISE;
        int floor = mesaTop - DIP_DEPTH;
        int wallEnd = DIP_FLOOR_HALF + DIP_WALL;
        int mesaEnd = wallEnd + DIP_MESA;
        if (d <= DIP_FLOOR_HALF) {
            return floor;
        }
        if (d <= wallEnd) {
            double t = (double) (d - DIP_FLOOR_HALF) / DIP_WALL;
            return (int) Math.round(floor + (mesaTop - floor) * t);
        }
        if (d <= mesaEnd) {
            return mesaTop;
        }
        double t = (double) (d - mesaEnd) / DIP_OUTER_RAMP;
        return (int) Math.round(mesaTop - (mesaTop - FLAT_TOP_Y) * t);
    }

    /** A water channel cut into a raised shelf: bed at the centre, flat banks, then a ramp back down
     *  to the flat baseline. {@code d} is the horizontal distance from the band centre. */
    private static int riverGround(int d) {
        int shelfTop = FLAT_TOP_Y + RIVER_SHELF_RISE;
        if (d <= RIVER_HALF_WIDTH) {
            return shelfTop - RIVER_DEPTH;
        }
        if (d <= RIVER_HALF_WIDTH + RIVER_BANK) {
            return shelfTop;
        }
        double t = (double) (d - (RIVER_HALF_WIDTH + RIVER_BANK)) / RIVER_OUTER_RAMP;
        return (int) Math.round(shelfTop - (shelfTop - FLAT_TOP_Y) * t);
    }
}
