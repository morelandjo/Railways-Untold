package com.vodmordia.railwaysuntold.gametest;

import com.vodmordia.railwaysuntold.Config;
import com.vodmordia.railwaysuntold.RailwaysUntold;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.worldgen.branching.BranchTrackCreator;
import com.vodmordia.railwaysuntold.worldgen.head.ExpansionHeadManager;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.integration.create.CreateTrackConnector;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor.BezierSegmentData;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.*;
import com.vodmordia.railwaysuntold.worldgen.placement.companion.CompanionTrackPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.TunnelEscapeSystem;
import com.vodmordia.railwaysuntold.worldgen.placement.handler.*;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.StartingTrainPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.support.BridgeSchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.support.WaterBridgeDetector;
import com.vodmordia.railwaysuntold.worldgen.placement.village.StationCommitter;
import com.vodmordia.railwaysuntold.worldgen.planner.PathExecutor;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.*;
import com.vodmordia.railwaysuntold.worldgen.siding.SidingTrackCreator;
import com.vodmordia.railwaysuntold.worldgen.survey.*;
import com.vodmordia.railwaysuntold.worldgen.survey.persist.SurveySavedData;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import com.vodmordia.railwaysuntold.worldgen.terrain.harness.BandedTerrainAtlas;
import com.vodmordia.railwaysuntold.worldgen.terrain.harness.ColumnProfileAtlas;
import com.vodmordia.railwaysuntold.worldgen.terrain.harness.OverlayTerrainAtlas;
import com.vodmordia.railwaysuntold.worldgen.terrain.harness.ReplayTerrainAtlas;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectionType;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import com.vodmordia.railwaysuntold.worldgen.village.StationPlan;
import com.vodmordia.railwaysuntold.worldgen.village.StationSchematicCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

// GameTest holders for Railways Untold. Tests run via `./gradlew runGameTestServer`, which boots
// a headless server, places each test's structure template in a fresh arena, and runs the method
// against a live world with Create on the classpath. This is the layer the pure planner goldens
// cannot reach (placement -> Create track-graph stitching -> train traversal).
@GameTestHolder(RailwaysUntold.MODID)
public final class RailwaysUntoldGameTests {

    private RailwaysUntoldGameTests() {
    }

    // Smoke test: proves the gametest harness boots, discovers @GameTestHolder, loads a template,
    // and runs a method to completion. The template is the empty 3x3x3 arena in
    // data/railwaysuntold/structure/empty.nbt; the test succeeds immediately with no world checks.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty")
    public static void smoke(GameTestHelper helper) {
        helper.succeed();
    }

    // End-to-end train assembly: StartingTrainPlacer.placeTrain loads the built-in train.nbt, validates it
    // (TrainSchematicValidator), clears + places the Create track, and assembles the train on it. A
    // success result exercises the whole world-bound half of worldgen/train (the half the unit tests
    // can't reach - TrainBuilder, VirtualTrainAssembler, the Create track-graph walk) against the real
    // shipped schematic. Anchored far away so its cleared corridor can't collide with another test.
    private static final int TRAIN_ANCHOR_X = 8_000_000;
    private static final int TRAIN_ANCHOR_Z = 8_000_000;

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void startingTrainAssemblesFromShippedSchematic(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction dir = Direction.EAST;

        int y = NoiseTerrainSampler.forLevel(level).getBaseHeight(TRAIN_ANCHOR_X, TRAIN_ANCHOR_Z);
        BlockPos pos = new BlockPos(TRAIN_ANCHOR_X, y, TRAIN_ANCHOR_Z);

        // Force the corridor placeTrain clears + tracks (it spans the train's length east + lateral) to
        // FULL up front, so it never defers on an ungenerated chunk.
        for (int cx = (TRAIN_ANCHOR_X - 48) >> 4; cx <= (TRAIN_ANCHOR_X + 400) >> 4; cx++) {
            for (int cz = (TRAIN_ANCHOR_Z - 48) >> 4; cz <= (TRAIN_ANCHOR_Z + 48) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    StartingTrainPlacer.TrainPlacementResult result =
                            StartingTrainPlacer.placeTrain(level, pos, dir);
                    RailwaysUntold.LOGGER.info("[GAMETEST/train] placeTrain success={} frontExit={} reason={}",
                            result.success, result.trackExitPoint, result.failureReason);
                    helper.assertTrue(result.success,
                            "starting-train placement failed: " + result.failureReason);
                    helper.assertTrue(result.trackExitPoint != null, "no front track exit point returned");
                })
                .thenSucceed();
    }

    // Event entity placement: a schematic's saved entities (e.g. a minecart) must actually spawn into the
    // world when the event is placed. SchematicPlacer.placeEntities parses the StructureTemplate `entities`
    // list, transforms each entity's local position by the placement rotation, and adds it to the level.
    // Before the fix the loader dropped the entities entirely, so the minecart never appeared. Far anchor
    // so the spawned cart can't collide with another test's arena.
    private static final int MINECART_ANCHOR_X = 7_000_000;
    private static final int MINECART_ANCHOR_Z = 7_000_000;

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void eventSchematicSpawnsSavedMinecart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int y = NoiseTerrainSampler.forLevel(level).getBaseHeight(MINECART_ANCHOR_X, MINECART_ANCHOR_Z);
        BlockPos origin = new BlockPos(MINECART_ANCHOR_X, y, MINECART_ANCHOR_Z);

        int cx = MINECART_ANCHOR_X >> 4;
        int cz = MINECART_ANCHOR_Z >> 4;
        level.setChunkForced(cx, cz, true);
        level.getChunk(cx, cz);

        // A 3x1x3 all-air schematic carrying one minecart at local (1.5, 0.0625, 2.5), saved in the same
        // StructureTemplate entity format the exporter writes: a `pos` list plus the entity `nbt`.
        int w = 3, h = 1, l = 3;
        BlockState[] blocks = new BlockState[w * h * l];
        Arrays.fill(blocks, Blocks.AIR.defaultBlockState());

        double localX = 1.5, localY = 0.0625, localZ = 2.5;
        CompoundTag entityNbt = new CompoundTag();
        entityNbt.putString("id", "minecraft:minecart");

        CompoundTag entry = new CompoundTag();
        ListTag posTag = new ListTag();
        posTag.add(DoubleTag.valueOf(localX));
        posTag.add(DoubleTag.valueOf(localY));
        posTag.add(DoubleTag.valueOf(localZ));
        entry.put("pos", posTag);
        entry.put("nbt", entityNbt);

        NbtSchematicLoader.LoadedSchematic schematic = new NbtSchematicLoader.LoadedSchematic(
                w, h, l, blocks, Collections.emptyMap(), List.of(entry));

        // Rotation.NONE: the entity's world position is simply origin + local offset.
        Vec3 expected = new Vec3(origin.getX() + localX, origin.getY() + localY, origin.getZ() + localZ);

        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    SchematicPlacer.placeEntities(level, schematic, origin, Rotation.NONE);

                    AABB box = new AABB(expected, expected).inflate(2.0);
                    List<AbstractMinecart> carts = level.getEntitiesOfClass(AbstractMinecart.class, box);
                    helper.assertTrue(carts.size() == 1,
                            "expected exactly 1 spawned minecart, found " + carts.size());
                    // Asserted on the spawn tick, before gravity moves it: position must be exact.
                    Vec3 actual = carts.get(0).position();
                    helper.assertTrue(actual.distanceTo(expected) < 1.0e-3,
                            "minecart spawned at " + actual + " expected " + expected);
                })
                .thenSucceed();
    }

    // World-bound branch creation: BranchTrackCreator.buildBranch curves a new branch off a parent track
    // (the half of worldgen/branching the unit tests can't reach - the curve geometry + Create bezier
    // track placement). Driven deterministically with a fixed branch direction, bypassing the
    // non-deterministic direction selector (which the unit tests cover). Far anchor so its curve can't
    // collide with another test.
    private static final int BRANCH_ANCHOR_X = 9_000_000;
    private static final int BRANCH_ANCHOR_Z = 9_000_000;

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void branchTrackCreatorCurvesABranchOffTheParent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction parentDir = Direction.EAST;
        Direction branchDir = Direction.SOUTH; // a right turn from EAST

        int y = NoiseTerrainSampler.forLevel(level).getBaseHeight(BRANCH_ANCHOR_X, BRANCH_ANCHOR_Z);
        BlockPos parentPos = new BlockPos(BRANCH_ANCHOR_X, y, BRANCH_ANCHOR_Z);

        for (int cx = (BRANCH_ANCHOR_X - 64) >> 4; cx <= (BRANCH_ANCHOR_X + 64) >> 4; cx++) {
            for (int cz = (BRANCH_ANCHOR_Z - 64) >> 4; cz <= (BRANCH_ANCHOR_Z + 64) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        final BlockPos[] anchor = {null};
        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    // The branch curves off a real parent track, so place + settle one first.
                    StartingTrainPlacer.placeInitialTrack(level, parentPos, parentDir);
                    anchor[0] = parentPos;
                })
                .thenExecuteAfter(6, () -> {
                    BranchTrackCreator.BranchResult result = BranchTrackCreator.buildBranch(
                            level, anchor[0], parentDir, branchDir, RailwaysUntoldConfig.getDefault());
                    RailwaysUntold.LOGGER.info("[GAMETEST/branch] buildBranch success={} startPos={} dir={}",
                            result.success, result.branchStartPos, result.branchDirection);
                    helper.assertTrue(result.success, "branch creation failed");
                    helper.assertTrue(result.branchStartPos != null, "no branch start position returned");
                    helper.assertTrue(result.branchDirection == branchDir,
                            "branch direction mismatch: " + result.branchDirection);
                    // A right turn EAST->SOUTH must curve both forward (east, +x) and laterally (south,
                    // +z) off the parent - that geometric offset is the proof a real curve was laid.
                    BlockPos start = result.branchStartPos;
                    helper.assertTrue(start.getX() > anchor[0].getX(), "branch did not advance east: " + start);
                    helper.assertTrue(start.getZ() > anchor[0].getZ(), "branch did not turn south: " + start);
                    // The curve is a Create bezier (endpoint nodes + virtual span), so assert the nodes
                    // exist rather than a dense run (see the replay gametest for the same bezier caveat).
                    Set<BlockPos> track = scanTrack(level, anchor[0], 48);
                    helper.assertTrue(track.size() >= 2, "branch placed no track nodes: " + track.size());
                })
                .thenSucceed();
    }

    // World-bound bridge-pillar crossing skip: a bridge pillar must not be driven down through an existing
    // track that passes beneath it - the deck spans the gap so the under-track stays clear (the over/under
    // crossing). Registers a cross-head under-track in the boundary tracker (data only, no blocks) through
    // one column and places a pillar over it, plus a control pillar over an empty column. The control fills
    // its column; the column over the under-track must stay empty (the pillar was skipped). Without the
    // skip, the test column fills too. Far anchor so it can't collide with other tests.
    private static final int PILLAR_SKIP_ANCHOR_X = 9_000_000;
    private static final int PILLAR_SKIP_ANCHOR_Z = 9_000_000;

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void bridgePillarSkipsColumnOverExistingTrack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int ax = PILLAR_SKIP_ANCHOR_X;
        int az = PILLAR_SKIP_ANCHOR_Z;
        int tx = ax + 32;             // a separate column for the under-track crossing

        // Deterministic ground: a stone platform under each column (the harness generates no terrain this
        // far out, so findGroundY would otherwise bail). Track deck 8 above; the under-track sits in between.
        final int groundY = 100;
        final int trackY = groundY + 8;
        final int underY = groundY + 3;
        final int loProbe = trackY - 5;   // mid/upper column - air before a pillar, filled by a placed pillar
        final int hiProbe = trackY - 2;   // the pillar's top block sits here when placed

        for (int cx = (ax - 48) >> 4; cx <= (ax + 48) >> 4; cx++) {
            for (int cz = (az - 48) >> 4; cz <= (az + 48) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        BlockPos controlTrack = new BlockPos(ax, trackY, az);
        BlockPos testTrack = new BlockPos(tx, trackY, az);
        BlockPos underA = new BlockPos(tx - 8, underY, az);
        BlockPos underB = new BlockPos(tx + 8, underY, az);

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    // Stone platform under both columns; clear the air gap above so the pillar has room.
                    for (int cxz0 : new int[]{ax, tx}) {
                        for (int dx = -6; dx <= 6; dx++) {
                            for (int dz = -6; dz <= 6; dz++) {
                                level.setBlock(new BlockPos(cxz0 + dx, groundY, az + dz), Blocks.STONE.defaultBlockState(), 2);
                                for (int y = groundY + 1; y <= trackY; y++) {
                                    level.setBlock(new BlockPos(cxz0 + dx, y, az + dz), Blocks.AIR.defaultBlockState(), 2);
                                }
                            }
                        }
                    }
                    // Register the under-track in the boundary tracker (data only - no blocks placed),
                    // owned by a different head so the pillar logic treats it as a real crossing.
                    ConnectedBoundaryTracker.confirmSegment(level, underA, underB,
                            ConnectionType.STRAIGHT, List.of(), UUID.randomUUID());
                })
                .thenExecuteAfter(4, () -> {
                    new BridgeSchematicPlacer().placePillarAt(level, controlTrack, new Vec3(1, 0, 0));
                    new BridgeSchematicPlacer().placePillarAt(level, testTrack, new Vec3(1, 0, 0));
                })
                .thenExecuteAfter(6, () -> {
                    boolean controlFilled = columnHasSolid(level, ax, az, loProbe, hiProbe);
                    helper.assertTrue(controlFilled,
                            "control pillar placed no blocks in its column - test setup or pillar schematic broken");
                    boolean testFilled = columnHasSolid(level, tx, az, loProbe, hiProbe);
                    helper.assertFalse(testFilled,
                            "pillar was driven through the existing under-track instead of skipping the crossing gap");
                })
                .thenSucceed();
    }

    // A pillar must also clear the crossing track's full envelope, not just its centerline: the under-track
    // carries decking and a cleared shoulder ~deckHalfWidth blocks to each side, so a pillar dropped beside
    // the centerline still spears the under-track's deck edge. Registers the under-track centerline 3 blocks
    // laterally off the pillar column - outside the old 1-block buffer but inside the decking envelope - and
    // asserts the pillar is skipped. With the centerline-only buffer the test column fills (the deck-edge clip).
    private static final int PILLAR_ENVELOPE_ANCHOR_X = 9_500_000;
    private static final int PILLAR_ENVELOPE_ANCHOR_Z = 9_500_000;

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void bridgePillarSkipsColumnBesideLowerTrackEnvelope(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int ax = PILLAR_ENVELOPE_ANCHOR_X;
        int az = PILLAR_ENVELOPE_ANCHOR_Z;
        int tx = ax + 32;             // a separate column for the under-track crossing

        final int groundY = 100;
        final int trackY = groundY + 8;
        final int underY = groundY + 3;
        final int loProbe = trackY - 5;
        final int hiProbe = trackY - 2;

        // The under-track centerline runs along X but 4 blocks off the pillar column in Z - beyond the
        // pillar footprint plus the old 1-block buffer, yet its decking envelope (~deckHalfWidth to each
        // side) still overlaps the pillar footprint, so the pillar must skip rather than clip the deck edge.
        final int underZ = az + 4;

        for (int cx = (ax - 48) >> 4; cx <= (ax + 48) >> 4; cx++) {
            for (int cz = (az - 48) >> 4; cz <= (az + 48) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        BlockPos controlTrack = new BlockPos(ax, trackY, az);
        BlockPos testTrack = new BlockPos(tx, trackY, az);
        BlockPos underA = new BlockPos(tx - 8, underY, underZ);
        BlockPos underB = new BlockPos(tx + 8, underY, underZ);

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    for (int cxz0 : new int[]{ax, tx}) {
                        for (int dx = -6; dx <= 6; dx++) {
                            for (int dz = -6; dz <= 6; dz++) {
                                level.setBlock(new BlockPos(cxz0 + dx, groundY, az + dz), Blocks.STONE.defaultBlockState(), 2);
                                for (int y = groundY + 1; y <= trackY; y++) {
                                    level.setBlock(new BlockPos(cxz0 + dx, y, az + dz), Blocks.AIR.defaultBlockState(), 2);
                                }
                            }
                        }
                    }
                    ConnectedBoundaryTracker.confirmSegment(level, underA, underB,
                            ConnectionType.STRAIGHT, List.of(), UUID.randomUUID());
                })
                .thenExecuteAfter(4, () -> {
                    new BridgeSchematicPlacer().placePillarAt(level, controlTrack, new Vec3(1, 0, 0));
                    new BridgeSchematicPlacer().placePillarAt(level, testTrack, new Vec3(1, 0, 0));
                })
                .thenExecuteAfter(6, () -> {
                    boolean controlFilled = columnHasSolid(level, ax, az, loProbe, hiProbe);
                    helper.assertTrue(controlFilled,
                            "control pillar placed no blocks in its column - test setup or pillar schematic broken");
                    boolean testFilled = columnHasSolid(level, tx, az, loProbe, hiProbe);
                    helper.assertFalse(testFilled,
                            "pillar was driven into the under-track's decking envelope instead of skipping beside it");
                })
                .thenSucceed();
    }

    /** True if any block in the column (x,z) between loY and hiY inclusive is non-air. */
    private static boolean columnHasSolid(ServerLevel level, int x, int z, int loY, int hiY) {
        for (int y = loY; y <= hiY; y++) {
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                return true;
            }
        }
        return false;
    }

    // World-bound siding placement: SidingTrackCreator.buildSiding lays a siding off a parent track - an
    // S-curve diverging from the parent, a parallel straight, then an S-curve rejoining it. The unit test
    // pins the pure forward-footprint helper; this proves the real Create track gets laid. Drives buildSiding
    // directly with a fixed side (the non-deterministic side selection is SidingValidator's, unit-covered).
    // Far anchor so the cleared footprint can't collide with another test.
    private static final int SIDING_ANCHOR_X = 7_000_000;
    private static final int SIDING_ANCHOR_Z = 7_000_000;

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void sidingTrackCreatorLaysADivergingSidingOffTheParent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction parentDir = Direction.EAST;
        boolean placeLeft = false; // a fixed side; the side-selection logic is SidingValidator's (unit-tested).
        int straightLength = 24;

        int y = NoiseTerrainSampler.forLevel(level).getBaseHeight(SIDING_ANCHOR_X, SIDING_ANCHOR_Z);
        BlockPos sidingOrigin = new BlockPos(SIDING_ANCHOR_X, y, SIDING_ANCHOR_Z);

        // The siding's footprint runs forward (parentDir) and laterally (the perpendicular). Force a wide
        // chunk band so buildSiding's own chunk-loaded gates don't bounce it into needsRetry.
        for (int cx = (SIDING_ANCHOR_X - 96) >> 4; cx <= (SIDING_ANCHOR_X + 96) >> 4; cx++) {
            for (int cz = (SIDING_ANCHOR_Z - 96) >> 4; cz <= (SIDING_ANCHOR_Z + 96) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    // The siding diverges from a real parent track, so place + settle one first.
                    StartingTrainPlacer.placeInitialTrack(level, sidingOrigin, parentDir);
                })
                .thenExecuteAfter(6, () -> {
                    SidingTrackCreator.SidingResult result = SidingTrackCreator.buildSiding(
                            level, sidingOrigin, parentDir, placeLeft, straightLength, UUID.randomUUID());
                    RailwaysUntold.LOGGER.info("[GAMETEST/siding] buildSiding success={} needsRetry={}",
                            result.success, result.needsRetry);
                    helper.assertTrue(result.success, "siding creation failed (needsRetry=" + result.needsRetry + ")");

                    // The S-curves carry track off the parent's straight line, so at least one laid node must
                    // sit laterally off the parent (z != origin z) - the geometric proof the siding diverged
                    // rather than running straight. The curves are Create beziers (endpoint nodes), so assert
                    // the offset node exists, not a dense run (same bezier caveat as the branch/replay tests).
                    Set<BlockPos> track = scanTrack(level, sidingOrigin, 64);
                    helper.assertTrue(track.size() >= 2, "siding placed no track nodes: " + track.size());
                    boolean diverged = track.stream().anyMatch(p -> p.getZ() != sidingOrigin.getZ());
                    helper.assertTrue(diverged, "siding laid no laterally-offset node - it did not diverge");
                })
                .thenSucceed();
    }

    // Proves GameTestTerrainMixin swaps the gametest world's overworld generator for
    // TestTerrainChunkGenerator, so the gametest world has controllable, non-flat terrain.
    // Asserts both halves of the seam the rest of the harness depends on:
    //   (a) the planner sees terrain - NoiseTerrainSampler.getBaseHeight (which reads the chunk
    //       generator, not placed blocks) reports the hill much higher than the flat baseline; and
    //   (b) placement sees ground - the hill chunk fills with real solid blocks up to that height,
    //       with air above, so the block-reading placers/detectors have something to read.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void terrainHarnessProducesHill(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NoiseTerrainSampler sampler = NoiseTerrainSampler.forLevel(level);

        final int hillX = BandedTerrainAtlas.HILL_CENTER_X;
        final int hillZ = BandedTerrainAtlas.HILL_CENTER_Z;

        // (a) Planner sees terrain: getBaseHeight needs no loaded chunks (it reads the generator).
        int flatH = sampler.getBaseHeight(-2000, -2000);
        int hillH = sampler.getBaseHeight(hillX, hillZ);
        helper.assertTrue(hillH > flatH + 40,
                "planner does not see the hill: flat baseHeight=" + flatH + " hill baseHeight=" + hillH);

        // (b) Placement sees ground: force the hill chunk to FULL, then read real blocks. Defer the
        // reads a few ticks so generation has definitely settled before we scan the column.
        ChunkPos hillChunk = new ChunkPos(hillX >> 4, hillZ >> 4);
        level.setChunkForced(hillChunk.x, hillChunk.z, true);
        level.getChunk(hillChunk.x, hillChunk.z);

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    int surfaceTop = hillH - 1; // getBaseHeight is the first air; solid top is one below.

                    helper.assertTrue(level.getBlockState(new BlockPos(hillX, surfaceTop, hillZ)).is(Blocks.STONE),
                            "hill surface block at y=" + surfaceTop + " is not solid stone: "
                                    + level.getBlockState(new BlockPos(hillX, surfaceTop, hillZ)));
                    helper.assertTrue(level.getBlockState(new BlockPos(hillX, hillH, hillZ)).isAir(),
                            "block above hill surface at y=" + hillH + " is not air: "
                                    + level.getBlockState(new BlockPos(hillX, hillH, hillZ)));
                    helper.assertTrue(level.getBlockState(new BlockPos(hillX, surfaceTop - 10, hillZ)).is(Blocks.STONE),
                            "hill interior at y=" + (surfaceTop - 10) + " is not solid stone");
                })
                .thenSucceed();
    }

    // Exercises the water seam the atlas adds: the river band carves a channel and fills it with
    // real water flush to the banks. Asserts (a) the planner classifies the channel as a river via
    // the heightmap gap (NoiseTerrainSampler.hasWaterAtPosition / isLikelyRiver) while the bank
    // reads dry, and (b) the channel chunk fills with real WATER blocks over a stone bed, so the
    // block-scanning WaterBridgeDetector has fluid to find.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void terrainHarnessProducesRiver(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NoiseTerrainSampler sampler = NoiseTerrainSampler.forLevel(level);

        final int riverX = BandedTerrainAtlas.RIVER_CENTER_X;
        final int riverZ = 0;
        final int bankX = riverX + BandedTerrainAtlas.RIVER_HALF_WIDTH + 24;

        // (a) Planner detects the river at the channel centre but not on the dry bank.
        helper.assertTrue(sampler.hasWaterAtPosition(riverX, riverZ),
                "planner sees no water at river centre");
        helper.assertTrue(sampler.isLikelyRiver(riverX, riverZ),
                "river centre not classified as a river");
        helper.assertTrue(!sampler.hasWaterAtPosition(bankX, riverZ),
                "dry bank wrongly reports water");

        // (b) Real water blocks fill the channel over a stone bed.
        ChunkPos riverChunk = new ChunkPos(riverX >> 4, riverZ >> 4);
        level.setChunkForced(riverChunk.x, riverChunk.z, true);
        level.getChunk(riverChunk.x, riverChunk.z);

        BandedTerrainAtlas atlas = new BandedTerrainAtlas();
        int bedY = atlas.groundHeight(riverX, riverZ);            // top of the solid channel bed
        int waterY = atlas.waterSurface(riverX, riverZ) - 1;      // a block inside the water column

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    helper.assertTrue(level.getBlockState(new BlockPos(riverX, bedY, riverZ)).is(Blocks.STONE),
                            "river bed at y=" + bedY + " is not stone: "
                                    + level.getBlockState(new BlockPos(riverX, bedY, riverZ)));
                    helper.assertTrue(level.getBlockState(new BlockPos(riverX, waterY, riverZ))
                                    .getFluidState().is(Fluids.WATER),
                            "river channel at y=" + waterY + " holds no water: "
                                    + level.getBlockState(new BlockPos(riverX, waterY, riverZ)));
                })
                .thenSucceed();
    }

    // Phase-2 scenario tests: prove the LIVE planner makes terrain-dependent decisions against real
    // generator terrain (the gap goldens cannot reach - they run on a stub sampler). Each drives the
    // real CoarseRouteFactory to plan a route across one feature band toward a fixed target, then
    // reads the installed precision route and asserts the planner chose the right segment kind. This
    // asserts the decision directly from the route, which is deterministic (no placement drive,
    // unlike expansionPlacesConnectedRun, which drives the full placement pipeline end-to-end).

    // Peak ridge -> TUNNEL. The peak is a z-infinite ridge (rise 40 > the 25 threshold), so the
    // planner cannot detour around it laterally and must tunnel through.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void plannerTunnelsThroughPeak(GameTestHelper helper) {
        TrackExpansionHead head = planRouteAcrossBand(helper, BandedTerrainAtlas.PEAK_CENTER_X);
        helper.startSequence()
                .thenIdle(ROUTE_INSTALL_TICKS)
                .thenExecute(() -> {
                    List<PathSegment.Type> segs = installedSegmentTypes(helper, head, "tunnel");
                    boolean hasTunnel = segs.contains(PathSegment.Type.TUNNEL)
                            || segs.contains(PathSegment.Type.ELEVATED_TUNNEL);
                    helper.assertTrue(hasTunnel,
                            "planner did not tunnel through the peak ridge; segments: " + segs);
                })
                .thenSucceed();
    }

    // Dry ravine -> ramped, grade-compliant crossing. The planner does NOT camel-hump-bridge a dry
    // dip here - it ramps down into the 20-deep ravine and back out, staying within the max slope.
    // (Bridging a dry ravine via CamelHumpDetector proved unreachable with synthetic terrain: the
    // planner pre-smooths the dive and curve-insertion density inflates the detector's steepness
    // span estimate. See docs/planner-future-investigation.md. Water ravines ARE bridged - see
    // plannerBridgesOverRiver.) This asserts the planner produces a valid route that descends into
    // the ravine and climbs out without exceeding the grade, rather than a cliff dive or failed plan.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void plannerRampsThroughRavine(GameTestHelper helper) {
        TrackExpansionHead head = planRouteAcrossBand(helper, BandedTerrainAtlas.DIP_CENTER_X);
        helper.startSequence()
                .thenIdle(ROUTE_INSTALL_TICKS)
                .thenExecute(() -> {
                    assertInstalledRoute(helper, head, "ravine");
                    PlannedPath path = head.getTerrainPlanState().getPrecisionRoute().getPrecisionPath();
                    double maxSlope = RailwaysUntoldConfig.getMaxSlopeRatio();
                    double steepest = 0.0;
                    for (PathSegment seg : path.segments) {
                        BlockPos s = seg.getStart();
                        BlockPos e = seg.getEndPosition();
                        int horizontal = Math.abs(e.getX() - s.getX()) + Math.abs(e.getZ() - s.getZ());
                        if (horizontal > 0) {
                            steepest = Math.max(steepest, (double) Math.abs(e.getY() - s.getY()) / horizontal);
                        }
                    }
                    RailwaysUntold.LOGGER.info("[GAMETEST/ravine] segments={} steepest={}",
                            path.segments.size(), steepest);
                    // Ramped rather than cliff-diving: steepest segment within grade, with tolerance
                    // below the 1.0 terrain wall an un-ramped dive would show.
                    helper.assertTrue(steepest <= maxSlope + 0.1,
                            "planner cliff-dived the ravine: steepest segment " + steepest
                                    + " exceeds max slope " + maxSlope);
                })
                .thenSucceed();
    }

    // River -> water BRIDGE. The channel carries real water, so applyWaterBridgeDetection marks the
    // crossing as a bridge over the water rather than dropping the track to the riverbed.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void plannerBridgesOverRiver(GameTestHelper helper) {
        TrackExpansionHead head = planRouteAcrossBand(helper, BandedTerrainAtlas.RIVER_CENTER_X);
        helper.startSequence()
                .thenIdle(ROUTE_INSTALL_TICKS)
                .thenExecute(() -> {
                    List<PathSegment.Type> segs = installedSegmentTypes(helper, head, "bridge-river");
                    helper.assertTrue(segs.contains(PathSegment.Type.BRIDGE),
                            "planner did not bridge the river; segments: " + segs);
                })
                .thenSucceed();
    }

    // Slope step -> ramped climb. The step's sides slope 0.4, steeper than the config max (0.25), so
    // the planner must ramp the climb: no installed segment may exceed the max slope ratio.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void plannerRampsUpSlope(GameTestHelper helper) {
        TrackExpansionHead head = planRouteAcrossBand(helper, BandedTerrainAtlas.SLOPE_CENTER_X);
        helper.startSequence()
                .thenIdle(ROUTE_INSTALL_TICKS)
                .thenExecute(() -> {
                    assertInstalledRoute(helper, head, "slope");
                    PlannedPath path = head.getTerrainPlanState().getPrecisionRoute().getPrecisionPath();
                    double maxSlope = RailwaysUntoldConfig.getMaxSlopeRatio();
                    double steepest = 0.0;
                    for (PathSegment seg : path.segments) {
                        BlockPos s = seg.getStart();
                        BlockPos e = seg.getEndPosition();
                        int horizontal = Math.abs(e.getX() - s.getX()) + Math.abs(e.getZ() - s.getZ());
                        if (horizontal > 0) {
                            steepest = Math.max(steepest, (double) Math.abs(e.getY() - s.getY()) / horizontal);
                        }
                    }
                    RailwaysUntold.LOGGER.info("[GAMETEST/slope] steepest segment slope={} (max={})",
                            steepest, maxSlope);
                    // Tolerance below the 0.4 terrain slope: a ramped route stays near maxSlope, an
                    // un-ramped one that just followed the terrain would read ~0.4 and fail.
                    helper.assertTrue(steepest <= maxSlope + 0.1,
                            "planner did not ramp the slope: steepest segment " + steepest
                                    + " exceeds max slope " + maxSlope);
                })
                .thenSucceed();
    }

    // Boundary case (tier 2): a peak just below PEAK_RISE_THRESHOLD (rise 22 < 25) must NOT tunnel -
    // the planner ramps over it. Brackets the threshold from below; breaks if the threshold drops.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void plannerRampsOverSubThresholdPeak(GameTestHelper helper) {
        TrackExpansionHead head = planRouteAcrossBand(helper, BandedTerrainAtlas.PEAK_SUB_CENTER_X);
        helper.startSequence()
                .thenIdle(ROUTE_INSTALL_TICKS)
                .thenExecute(() -> {
                    List<PathSegment.Type> segs = installedSegmentTypes(helper, head, "peak-sub");
                    boolean hasTunnel = segs.contains(PathSegment.Type.TUNNEL)
                            || segs.contains(PathSegment.Type.ELEVATED_TUNNEL);
                    helper.assertTrue(!hasTunnel,
                            "planner tunnelled a sub-threshold peak that should be ramped; segments: " + segs);
                })
                .thenSucceed();
    }

    // Boundary case (tier 2): a peak just above PEAK_RISE_THRESHOLD (rise 27 > 25) must tunnel.
    // Brackets the threshold from above; breaks if the threshold rises past 27.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void plannerTunnelsAtThreshold(GameTestHelper helper) {
        TrackExpansionHead head = planRouteAcrossBand(helper, BandedTerrainAtlas.PEAK_AT_CENTER_X);
        helper.startSequence()
                .thenIdle(ROUTE_INSTALL_TICKS)
                .thenExecute(() -> {
                    List<PathSegment.Type> segs = installedSegmentTypes(helper, head, "peak-at");
                    boolean hasTunnel = segs.contains(PathSegment.Type.TUNNEL)
                            || segs.contains(PathSegment.Type.ELEVATED_TUNNEL);
                    helper.assertTrue(hasTunnel,
                            "planner did not tunnel a peak just above the threshold; segments: " + segs);
                })
                .thenSucceed();
    }

    // Planner-drift / SM2 PRECONDITION. Drift happens in production when the planner's terrain
    // sampler under-reports the real ground (it plans flat where the world rises). The flat gametest
    // world could never create that mismatch, so the drift path was unverifiable. The drift band now
    // reproduces the precondition deterministically: getBaseHeight reports flat baseline while
    // fillFromNoise places a real rock rise above it. This asserts that mismatch exists.
    //
    // The drift EVENT itself is NOT asserted here: driving placement through the band showed the
    // placement layer absorbs the unexpected rise by tunnelling at the planned Y (the head stayed on
    // plan, the drift guard did not fire), and the residual drift/halt path is rare and
    // non-deterministic. See docs/planner-future-investigation.md.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void driftBandHidesRealRiseFromPlanner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NoiseTerrainSampler sampler = NoiseTerrainSampler.forLevel(level);

        final int driftX = BandedTerrainAtlas.DRIFT_CENTER_X;
        final int driftZ = 0;
        final int humpTop = BandedTerrainAtlas.FLAT_TOP_Y + BandedTerrainAtlas.DRIFT_RISE;

        // The planner sees flat baseline here (it under-reports the real rise).
        int plannerSurface = sampler.getBaseHeight(driftX, driftZ);
        helper.assertTrue(plannerSurface == BandedTerrainAtlas.FLAT_TOP_Y + 1,
                "planner should see flat baseline at the drift band, got surface=" + plannerSurface);

        // But the real world rises into a rock hump well above what the planner sees.
        ChunkPos driftChunk = new ChunkPos(driftX >> 4, driftZ >> 4);
        level.setChunkForced(driftChunk.x, driftChunk.z, true);
        level.getChunk(driftChunk.x, driftChunk.z);

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    helper.assertTrue(level.getBlockState(new BlockPos(driftX, humpTop, driftZ)).is(Blocks.STONE),
                            "drift hump top at y=" + humpTop + " is not stone: "
                                    + level.getBlockState(new BlockPos(driftX, humpTop, driftZ)));
                    // A block the planner thinks is air (above its flat view) is actually solid rock -
                    // the sampler-vs-actual mismatch that drives planner drift in production.
                    int hiddenY = plannerSurface + 2;
                    helper.assertTrue(level.getBlockState(new BlockPos(driftX, hiddenY, driftZ)).is(Blocks.STONE),
                            "planner-invisible block at y=" + hiddenY + " should be solid rock: "
                                    + level.getBlockState(new BlockPos(driftX, hiddenY, driftZ)));
                })
                .thenSucceed();
    }

    // Seeded parameter sweep (tier 3): plans a route across each of N feature bands whose shapes are
    // drawn from a fixed, logged seed, and asserts the planner produces a valid route that crosses
    // the feature. This covers the shape-space tails the fixed cases under-sample (varied
    // height/width, slope steps, double peaks, surface jitter) and would catch a planner failure on
    // an awkward shape. The seed is logged and each failure prints its band's params, so a failing
    // shape replays deterministically and can be promoted into a fixed Phase-2 case.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void seededSweepPlansValidRoutes(GameTestHelper helper) {
        BandedTerrainAtlas atlas = new BandedTerrainAtlas();
        List<TrackExpansionHead> heads = new java.util.ArrayList<>();
        for (int i = 0; i < BandedTerrainAtlas.SWEEP_N; i++) {
            heads.add(planRouteAcrossBand(helper, BandedTerrainAtlas.sweepBandCenterX(i)));
        }
        RailwaysUntold.LOGGER.info("[GAMETEST/sweep] seed={} iterations={}",
                Long.toHexString(BandedTerrainAtlas.SWEEP_SEED), BandedTerrainAtlas.SWEEP_N);

        helper.startSequence()
                // One wait covers all N async plans (they share the single planner thread and install
                // within a few ticks of each other).
                .thenIdle(120)
                .thenExecute(() -> {
                    for (int i = 0; i < heads.size(); i++) {
                        String ctx = "sweep band " + i + " [" + atlas.sweepBandDesc(i)
                                + "] seed=" + Long.toHexString(BandedTerrainAtlas.SWEEP_SEED);
                        TrackExpansionHead h = heads.get(i);
                        PrecisionRoute route = h.getTerrainPlanState().getPrecisionRoute();
                        helper.assertTrue(route != null && route.isValid(),
                                ctx + ": planner produced no valid route");
                        PlannedPath path = route.getPrecisionPath();
                        int center = BandedTerrainAtlas.sweepBandCenterX(i);
                        helper.assertTrue(path.finalPosition != null && path.finalPosition.getX() >= center,
                                ctx + ": route did not cross the feature (finalPos=" + path.finalPosition + ")");
                        RailwaysUntold.LOGGER.info("[GAMETEST/sweep] band {} [{}]: {} segments, finalPos={}",
                                i, atlas.sweepBandDesc(i), path.segments.size(), path.finalPosition);
                    }
                })
                .thenSucceed();
    }

    // Ticks to wait for CoarseRouteFactory's async plan to compute and install on a later server tick.
    private static final int ROUTE_INSTALL_TICKS = 60;

    // Sets up an orchestrator and a single eastbound head west of the given band centre, then asks
    // the real planner to route to a fixed target east of it so the route crosses the feature.
    // Planning reads terrain via the generator (NoiseTerrainSampler), not loaded blocks, so no chunk
    // loading is needed. Returns the head whose installed route the caller inspects after a wait.
    private static TrackExpansionHead planRouteAcrossBand(GameTestHelper helper, int bandCenterX) {
        ServerLevel level = helper.getLevel();
        Direction dir = Direction.EAST;
        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        NoiseTerrainSampler sampler = NoiseTerrainSampler.forLevel(level);

        int z = 0;
        int startX = bandCenterX - 250;
        int targetX = bandCenterX + 250;
        BlockPos start = new BlockPos(startX, sampler.getBaseHeight(startX, z), z);
        BlockPos target = new BlockPos(targetX, sampler.getBaseHeight(targetX, z), z);

        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, orchestrator);

        ExpansionHeadManager headManager = orchestrator.getHeadManager();
        headManager.initializeHeadsWithCustomStart(start, start.relative(dir), dir, level, config, null);
        TrackExpansionHead forward = null;
        for (TrackExpansionHead h : headManager.getActiveHeads()) {
            if (h.getDirection() == dir) {
                forward = h;
            } else {
                h.markComplete();
            }
        }
        helper.assertTrue(forward != null, "no forward (east) head was created");

        forward.getVillageState().setTarget(java.util.UUID.randomUUID(), target, forward.getPosition());
        CoarseRouteFactory.createAndAttach(level, forward, target);
        return forward;
    }

    // Asserts a valid precision route is installed (planning finished and produced segments).
    private static void assertInstalledRoute(GameTestHelper helper, TrackExpansionHead head, String label) {
        helper.assertTrue(!head.getTerrainPlanState().isRouteRebuilding(),
                label + ": route still rebuilding after " + ROUTE_INSTALL_TICKS + " ticks");
        PrecisionRoute route = head.getTerrainPlanState().getPrecisionRoute();
        helper.assertTrue(route != null && route.isValid(),
                label + ": planner installed no valid precision route to the target");
    }

    // The installed route's segment types, after asserting the route is present. Logs them for diagnosis.
    private static List<PathSegment.Type> installedSegmentTypes(GameTestHelper helper, TrackExpansionHead head, String label) {
        assertInstalledRoute(helper, head, label);
        PlannedPath path = head.getTerrainPlanState().getPrecisionRoute().getPrecisionPath();
        List<PathSegment.Type> types = new java.util.ArrayList<>();
        for (PathSegment seg : path.segments) {
            types.add(seg.type);
        }
        RailwaysUntold.LOGGER.info("[GAMETEST/{}] {} segments: {}", label, types.size(), types);
        return types;
    }

    // Connection primitive: places two real Create tracks a fixed run apart with a steep elevation
    // gain and asks our reflective Create integration to stitch them, the same call the placement
    // layer makes (CreateTrackConnector.connectTracksWithSlopes). Asserts Create accepts the
    // geometry (non-null bezier), the bezier is gap-free, and it climbs the full elevation to the
    // target Y rather than landing short. This exercises the live-world seam goldens cannot reach
    // and is the basis for settling PC7: it shows whether Create connects an exact-target steep
    // approach (the un-clamped endpoint) without a gap.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "connect_arena")
    public static void connectsSteepApproachToExactTargetY(GameTestHelper helper) {
        final int run = 12;
        final int rise = 3; // 3/12 = 0.25, the default config max slope ratio
        final double slope = (double) rise / run;
        final BlockPos relStart = new BlockPos(4, 10, 2);
        final BlockPos relEnd = relStart.offset(run, rise, 0);

        runConnectionCheck(helper, relStart, relEnd, Direction.EAST, rise, slope);
    }

    // Flat control: same primitive with no elevation change. Confirms the seam works on the simple
    // case, so a failure in the steep test points at slope handling rather than the integration.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "connect_arena")
    public static void connectsFlatApproach(GameTestHelper helper) {
        final BlockPos relStart = new BlockPos(4, 10, 2);
        final BlockPos relEnd = relStart.offset(12, 0, 0);

        runConnectionCheck(helper, relStart, relEnd, Direction.EAST, 0, 0.0);
    }

    private static void runConnectionCheck(GameTestHelper helper, BlockPos relStart, BlockPos relEnd,
                                           Direction axis, int elevationChange, double slope) {
        // Place both endpoints as straight Create tracks with block entities (HAS_BE), then give the
        // block entities a couple of ticks to initialize before asking Create to connect them.
        helper.setBlock(relStart, CreateTrackUtil.getStraightTrack(axis));
        helper.setBlock(relEnd, CreateTrackUtil.getStraightTrack(axis));

        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    BlockPos start = helper.absolutePos(relStart);
                    BlockPos end = helper.absolutePos(relEnd);

                    Object bezier = CreateTrackConnector.connectTracksWithSlopes(
                            level, start, end, elevationChange, slope, slope);
                    helper.assertTrue(bezier != null,
                            "Create returned no connection (null bezier) for slope " + slope);

                    List<BezierSegmentData> segments = BezierSegmentExtractor.extractSegments(bezier);
                    helper.assertTrue(!segments.isEmpty(), "bezier produced no segments");

                    // No gap: each sampled point is within ~1.5 blocks of the previous one.
                    Vec3 prev = segments.get(0).position();
                    double maxGap = 0.0;
                    for (BezierSegmentData seg : segments) {
                        maxGap = Math.max(maxGap, seg.position().distanceTo(prev));
                        prev = seg.position();
                    }
                    helper.assertTrue(maxGap <= 1.5,
                            "bezier has a discontinuity: max sample gap " + maxGap + " blocks");

                    // Reaches the target elevation: the rendered curve climbs the full rise rather
                    // than landing short (the PC7 derail risk). Compare the vertical span of the
                    // sampled path against the requested elevation change.
                    double firstY = segments.get(0).position().y;
                    double lastY = segments.get(segments.size() - 1).position().y;
                    double span = lastY - firstY;
                    helper.assertTrue(Math.abs(span - elevationChange) <= 1.0,
                            "connection did not reach target Y: vertical span " + span
                                    + " for requested elevation change " + elevationChange);
                })
                .thenSucceed();
    }

    // Pipeline drive: seeds one expansion head on the flat superflat surface and drives the real
    // expansion pipeline (TrackExpansionOrchestrator.stepHead) across ticks - the same code path
    // normal gameplay runs - letting async route planning and chunk deferrals resolve between ticks.
    // seedRouteFollowingHead pins the head to a fixed exploration target far past
    // EXPLORATION_TARGET_FORWARD_THRESHOLD, so it follows its installed coarse route in a straight
    // cardinal line instead of free-steering - the run is the same every boot. The assertion is
    // therefore direction-specific: the placed Create track forms a single connected, gap-free run
    // that advances along `dir` by ~RUN_LENGTH and stays on its lateral axis. This is the end-to-end
    // guard for the whole placement layer (planner -> precision compile -> placement -> Create
    // track-graph stitching) that goldens structurally cannot cover.
    private static final int RUN_LENGTH = 40;
    private static final int DRIVE_TICKS = 220;

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void expansionPlacesConnectedRun(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction dir = Direction.EAST;

        // Deterministic placement driver, part 1 of 2 (part 2 is the fixed target + route-following
        // in seedRouteFollowingHead). Three behaviours steer the head off a clean straight route in a
        // position/seed-dependent way, so disable all three for the run (restored at the end):
        //   - STRUCTURE_TARGETING: if the boot's random spawn has villages nearby, the head abandons
        //     its route to seek and path toward one (NETWORK-PLANNER / VILLAGE-ASSIGN), replanning
        //     repeatedly and derailing into a few-blocks stub - the dominant flake.
        //   - ALL_AVOIDANCE and BRANCHES_ENABLED: both steer using seed/position-derived structure
        //     predictions that detour the route by varying amounts at the random arena position.
        // With all three off the head follows a position-independent cardinal route on the flat
        // baseline. (ConfigValue.set is in-memory only - it does not write the config file.)
        boolean prevAvoidance = Config.ALL_AVOIDANCE.get();
        boolean prevBranches = Config.BRANCHES_ENABLED.get();
        boolean prevStructureTargeting = Config.STRUCTURE_TARGETING.get();
        Config.ALL_AVOIDANCE.set(false);
        Config.BRANCHES_ENABLED.set(false);
        Config.STRUCTURE_TARGETING.set(false);
        Config.PARALLEL_MERGE_ENABLED.set(false);

        // Anchor EAST of this arena, in empty flat baseline well clear of the test-arena cluster. The
        // head lays a long run (a few hundred blocks) per step, so its corridor extends far past the
        // arena footprint; keeping it in freshly-generated flat baseline means placement runs over
        // clean chunks rather than any blocks a neighbouring arena might have left in the corridor.
        //
        // Lane isolation: same-template ("empty") arenas are packed at the same X and spread along Z,
        // and batched gametests run CONCURRENTLY in the shared world. Two driver tests at the same
        // clearance + Z lane therefore race for the same corridor (the old ~1/10 flake: this test shared
        // +128/z0 with corridorRenderPlacesGapFreeRun). A clearance no other driver reaches (the +128/+160
        // groups net at most ~+172 chunks east) isolates this corridor with zero overlap - X has no
        // arena jitter, so clearance separation is exact.
        final int ARENA_CLEARANCE = 256;        // chunks east of the arena - unique lane, clear of all other drivers
        final int CORRIDOR_AHEAD_CHUNKS = 24;   // covers the head's eastward reach (one long segment) + margin
        final int CORRIDOR_SIDE_CHUNKS = 3;      // lateral / behind margin
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        ChunkPos initialChunk = new ChunkPos(((origin.getX() + 24) >> 4) + ARENA_CLEARANCE, origin.getZ() >> 4);

        // Force-load the east corridor the head drives through and bring every chunk to FULL
        // synchronously (the blocking getChunk call placeInitialTrack itself uses): the anchor's
        // ground Y is read from the surface heightmap, which is unpopulated on a force-loaded-but-
        // ungenerated chunk - that floats the anchor above the real surface, and every plan from the
        // true surface then drifts in Y so the head never connects. Bringing the whole reach to FULL
        // up front also means the head never defers mid-run for an ungenerated chunk ahead.
        for (int cx = initialChunk.x - CORRIDOR_SIDE_CHUNKS; cx <= initialChunk.x + CORRIDOR_AHEAD_CHUNKS; cx++) {
            for (int cz = initialChunk.z - CORRIDOR_SIDE_CHUNKS; cz <= initialChunk.z + CORRIDOR_SIDE_CHUNKS; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, orchestrator);

        final TrackExpansionHead[] head = new TrackExpansionHead[1];
        final BlockPos[] anchor = new BlockPos[1];

        helper.startSequence()
                // Chunks are already generated to FULL above, so the surface heightmap is populated.
                .thenExecuteAfter(4, () -> {
                    // Place the anchor track the first head expands from. Anchor it at the Y the
                    // planner's terrain sampler reports (NoiseTerrainSampler.getBaseHeight, the
                    // deterministic FlatLevelSource surface), NOT at getInitialTrackPosition's
                    // heightmap-derived Y: in the shared GameTestServer world, adjacent test arenas
                    // leave solid blocks above the flat surface, so the heightmap can read ~22 blocks
                    // high. Anchoring there but planning from the true flat surface drifts every plan
                    // in Y, and the head never connects (the old "placed too few track blocks" flake).
                    BlockPos rawPos = StartingTrainPlacer.getInitialTrackPosition(
                            level, initialChunk, config, level.getSharedSpawnPos(), dir);
                    int plannerY = com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler
                            .forLevel(level).getBaseHeight(rawPos.getX(), rawPos.getZ());
                    BlockPos initialTrackPos = new BlockPos(rawPos.getX(), plannerY, rawPos.getZ());
                    StartingTrainPlacer.placeInitialTrack(level, initialTrackPos, dir);
                    anchor[0] = initialTrackPos;
                })
                // Give the anchor's deferred block entity + Create track graph a few ticks to settle
                // before the head expands from it.
                .thenExecuteAfter(6, () -> {
                    TrackExpansionHead forward = seedRouteFollowingHead(
                            level, config, orchestrator, anchor[0], dir);
                    helper.assertTrue(forward != null, "no forward (" + dir + ") head was created");
                    head[0] = forward;
                })
                // Pump one expansion step per tick; skip while an async replan is in flight, and
                // stop once the head has travelled RUN_LENGTH from the anchor so the run stays bounded.
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    TrackExpansionHead h = head[0];
                    if (h == null || h.isComplete()) {
                        return;
                    }
                    if (chebyshev(h.getPosition(), anchor[0]) >= RUN_LENGTH) {
                        return;
                    }
                    if (h.getTerrainPlanState().isRouteRebuilding()) {
                        return;
                    }
                    orchestrator.stepHead(h);
                })
                .thenExecute(() -> {
                    // Restore the toggled config before asserting, so a failing assertion can't leak
                    // the disabled state into other tests sharing this server boot.
                    Config.ALL_AVOIDANCE.set(prevAvoidance);
                    Config.BRANCHES_ENABLED.set(prevBranches);
                    Config.STRUCTURE_TARGETING.set(prevStructureTargeting);

                    BlockPos a = anchor[0];
                    BlockPos finalPos = head[0].getPosition();
                    // Scan a box centred between the anchor and wherever the head ended up, sized to
                    // cover both with margin for path bulges - the head can lay its whole run past a
                    // fixed anchor-centred window in a single step.
                    BlockPos mid = new BlockPos((a.getX() + finalPos.getX()) / 2, a.getY(),
                            (a.getZ() + finalPos.getZ()) / 2);
                    int half = chebyshev(a, finalPos) / 2 + 24;
                    Set<BlockPos> track = scanTrack(level, mid, half);
                    Set<BlockPos> run = largestComponent(track);
                    int span = spanOf(run);
                    RailwaysUntold.LOGGER.info(
                            "[GAMETEST/pipeline] anchor={} head={} within +-{}: {} track blocks; largest connected run {} blocks spanning {}",
                            a, finalPos, half, track.size(), run.size(), span);

                    helper.assertTrue(track.size() >= 16,
                            "expansion placed too few track blocks: " + track.size());

                    // Core invariant: the expansion places a single gap-free connected run of
                    // meaningful length (26-neighbour adjacency, so diagonal/sloped track counts).
                    // This is what the whole placement layer must guarantee end-to-end: planner ->
                    // precision compile -> placement -> Create stitching yields continuous,
                    // traversable track rather than disconnected fragments.
                    helper.assertTrue(span >= RUN_LENGTH - 8,
                            "longest connected run is too short: spans " + span
                                    + " blocks (need " + (RUN_LENGTH - 8) + ")");
                    helper.assertTrue(run.size() >= RUN_LENGTH,
                            "longest connected run is too sparse: " + run.size()
                                    + " connected blocks (need " + RUN_LENGTH + ")");

                    // Direction guard: the head followed its route along `dir`, net-advancing at least
                    // RUN_LENGTH. With no exploration target set the head cannot free-steer, so the
                    // projection of its travel onto `dir` is the meaningful directional check. Lateral
                    // position is deliberately NOT pinned: the precision compiler emits SCurve45
                    // corrections that excurse a variable amount (tens of blocks on a short run) and
                    // return, so the path is not a straight line - only its forward progress and
                    // connectedness are invariant.
                    int dx = finalPos.getX() - a.getX();
                    int dz = finalPos.getZ() - a.getZ();
                    int forwardAdvance = dx * dir.getStepX() + dz * dir.getStepZ();
                    helper.assertTrue(forwardAdvance >= RUN_LENGTH - 8,
                            "head did not advance " + dir + ": forward advance " + forwardAdvance
                                    + " (need " + (RUN_LENGTH - 8) + ")");
                })
                .thenSucceed();
    }

    // How far line head A drives east to lay the target line L (long enough that head B's whole converge
    // approach - forwardMargin ~ lane + 2*minRadius - lands on it).
    private static final int CONVERGE_LINE_RUN = 64;
    // Lateral offset of head B's lane from line L: inside the convergence band (9..30) and past the
    // touching-merge band (<=8), so the merge happens via long-range converge, not the adjacent merge.
    private static final int CONVERGE_LANE = 20;

    /**
     * Long-range parallel convergence, end to end: head A lays a straight line L; head B is seeded one
     * lane ({@link #CONVERGE_LANE} blocks) to the side running parallel. B must detect L as a sustained
     * parallel line, curve over, and MERGE into it - completing (stopping) at the join rather than
     * running alongside or onto it (the track-on-track regression). Asserts B completed and that the two
     * lanes became a single connected run (the converge curve bridged the gap). Parallel-merge stays
     * enabled; avoidance/branches/structure-targeting off so B's only reason to leave its lane is the merge.
     */
    // Own batch: parallel-merge is a shared global Config flag that the deterministic-driver tests set
    // false; running this test in a separate batch (batches run sequentially) keeps them from toggling it
    // off concurrently while this test needs it ON.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600, batch = "parallelConverge")
    public static void convergingHeadMergesIntoParallelLine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction dir = Direction.EAST;
        Direction lat = Direction.SOUTH; // +Z: the side head B sits on, relative to line L

        boolean prevAvoidance = Config.ALL_AVOIDANCE.get();
        boolean prevBranches = Config.BRANCHES_ENABLED.get();
        boolean prevStructureTargeting = Config.STRUCTURE_TARGETING.get();
        boolean prevParallelMerge = Config.PARALLEL_MERGE_ENABLED.get();
        Config.ALL_AVOIDANCE.set(false);
        Config.BRANCHES_ENABLED.set(false);
        Config.STRUCTURE_TARGETING.set(false);
        Config.PARALLEL_MERGE_ENABLED.set(true);

        // Unique far-east lane, clear of every other driver's reach (the +128/+160/+256 groups net at most
        // ~+172 chunks), so the only parallel line head B can see is the one this test lays.
        final int ARENA_CLEARANCE = 320;
        final int AHEAD_CHUNKS = 12;
        final int SIDE_CHUNKS = 4;
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        ChunkPos initialChunk = new ChunkPos(((origin.getX() + 24) >> 4) + ARENA_CLEARANCE, origin.getZ() >> 4);
        for (int cx = initialChunk.x - SIDE_CHUNKS; cx <= initialChunk.x + AHEAD_CHUNKS; cx++) {
            for (int cz = initialChunk.z - SIDE_CHUNKS; cz <= initialChunk.z + SIDE_CHUNKS; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, orchestrator);

        final TrackExpansionHead[] lineHead = new TrackExpansionHead[1];   // A - lays line L
        final TrackExpansionHead[] mergeHead = new TrackExpansionHead[1];  // B - converges into L
        final BlockPos[] anchorA = new BlockPos[1];
        final BlockPos[] anchorB = new BlockPos[1];

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    BlockPos rawPos = StartingTrainPlacer.getInitialTrackPosition(
                            level, initialChunk, config, level.getSharedSpawnPos(), dir);
                    int plannerY = NoiseTerrainSampler.forLevel(level).getBaseHeight(rawPos.getX(), rawPos.getZ());
                    anchorA[0] = new BlockPos(rawPos.getX(), plannerY, rawPos.getZ());
                    StartingTrainPlacer.placeInitialTrack(level, anchorA[0], dir);
                })
                .thenExecuteAfter(6, () -> {
                    lineHead[0] = seedRouteFollowingHead(level, config, orchestrator, anchorA[0], dir);
                    helper.assertTrue(lineHead[0] != null, "line head A was not created");
                })
                // Drive A east to lay the straight target line L.
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    TrackExpansionHead h = lineHead[0];
                    if (h == null || h.isComplete()) return;
                    if (chebyshev(h.getPosition(), anchorA[0]) >= CONVERGE_LINE_RUN) return;
                    if (h.getTerrainPlanState().isRouteRebuilding()) return;
                    orchestrator.stepHead(h);
                })
                // Freeze A; seed B one lane to the side, running parallel to L.
                .thenExecute(() -> {
                    lineHead[0].markComplete();
                    orchestrator.getHeadManager().invalidateActiveHeadsCache();
                    anchorB[0] = anchorA[0].relative(lat, CONVERGE_LANE);
                    StartingTrainPlacer.placeInitialTrack(level, anchorB[0], dir);
                })
                .thenExecuteAfter(6, () -> {
                    ExpansionHeadManager hm = orchestrator.getHeadManager();
                    hm.initializeHeadsWithCustomStart(anchorB[0], anchorB[0].relative(dir), dir, level, config, null);
                    // Pick B by proximity to its anchor (A's completed head may still surface); complete any
                    // other head seeded near B so only B drives.
                    TrackExpansionHead b = null;
                    double best = Double.MAX_VALUE;
                    for (TrackExpansionHead c : hm.getActiveHeads()) {
                        if (c.getDirection() != dir) continue;
                        double d = c.getPosition().distSqr(anchorB[0]);
                        if (d < best) { best = d; b = c; }
                    }
                    helper.assertTrue(b != null, "merge head B was not created");
                    for (TrackExpansionHead other : hm.getActiveHeads()) {
                        if (other != b && other != lineHead[0]
                                && other.getPosition().distSqr(anchorB[0]) < 64 * 64) {
                            other.markComplete();
                        }
                    }
                    hm.invalidateActiveHeadsCache();
                    mergeHead[0] = b;
                    CoarseRouteFactory.createAndAttach(level, b, anchorB[0].relative(dir, DRIVER_TARGET_DISTANCE));
                })
                // Drive B: it should detect L, converge, curve in, and complete.
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    TrackExpansionHead h = mergeHead[0];
                    if (h == null || h.isComplete()) return;
                    if (h.getTerrainPlanState().isRouteRebuilding()) return;
                    orchestrator.stepHead(h);
                })
                .thenExecute(() -> {
                    Config.ALL_AVOIDANCE.set(prevAvoidance);
                    Config.BRANCHES_ENABLED.set(prevBranches);
                    Config.STRUCTURE_TARGETING.set(prevStructureTargeting);
                    Config.PARALLEL_MERGE_ENABLED.set(prevParallelMerge);

                    BlockPos a = anchorA[0];
                    int finalDz = Math.abs(mergeHead[0].getPosition().getZ() - a.getZ());
                    // Connectivity incl. the bezier filler: the converge join curve is create:fake_track, so
                    // a real merge bridges B's lane to line L into one component; an unmerged (straight) B
                    // leaves the two lanes as separate components with nothing in the gap between them.
                    BlockPos mid = new BlockPos(a.getX() + DRIVER_TARGET_DISTANCE / 2, a.getY(),
                            a.getZ() + CONVERGE_LANE / 2);
                    int half = DRIVER_TARGET_DISTANCE / 2 + CONVERGE_LANE + 24;
                    Set<BlockPos> track = scanTrackInclFiller(level, mid, half, 16);
                    Set<BlockPos> run = largestComponent(track);
                    boolean touchesLine = run.stream().anyMatch(p -> Math.abs(p.getZ() - a.getZ()) <= 2);
                    boolean touchesMergeLane = run.stream()
                            .anyMatch(p -> Math.abs(p.getZ() - (a.getZ() + CONVERGE_LANE)) <= 2);
                    RailwaysUntold.LOGGER.info(
                            "[GAMETEST/converge] anchorA={} laneB=+{} Bfinal={} Bcomplete={} finalDz={} track={} largestRun={} touchesLine={} touchesLane={}",
                            a, CONVERGE_LANE, mergeHead[0].getPosition(), mergeHead[0].isComplete(),
                            finalDz, track.size(), run.size(), touchesLine, touchesMergeLane);

                    // The head curved INTO the line and stopped (the regression was running on past it),
                    helper.assertTrue(mergeHead[0].isComplete(),
                            "converging head did not merge into the parallel line (still active)");
                    // moved laterally toward the line (a straight, unmerged head stays at its start offset),
                    helper.assertTrue(finalDz < CONVERGE_LANE,
                            "converging head did not move toward the line (finalDz=" + finalDz + ")");
                    // and the join bridged the two lanes into one connected run.
                    helper.assertTrue(touchesLine && touchesMergeLane,
                            "converge did not join the two lanes into one connected run (touchesLine="
                                    + touchesLine + " touchesMergeLane=" + touchesMergeLane + ")");
                })
                .thenSucceed();
    }

    /**
     * Corridor-render driver: generates a long route by force-loading a moving window of chunks AHEAD
     * of the head each tick (the synchronous CI analogue of {@code /railways autoload}), then asserts
     * the placed track is a single gap-free run with no orphaned stub. A disconnected seam - a replan
     * stub that doesn't join the prior run, the village-skirt bug class - splits the track into two
     * components, caught here via the orphan-block count. Flat baseline, avoidance/branches/structure
     * targeting off, same as {@link #expansionPlacesConnectedRun}, but driven far enough to span
     * multiple placement segments.
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void corridorRenderPlacesGapFreeRun(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction dir = Direction.EAST;
        Direction lat = dir.getClockWise();

        boolean prevAvoidance = Config.ALL_AVOIDANCE.get();
        boolean prevBranches = Config.BRANCHES_ENABLED.get();
        boolean prevStructureTargeting = Config.STRUCTURE_TARGETING.get();
        boolean prevParallelMerge = Config.PARALLEL_MERGE_ENABLED.get();
        Config.ALL_AVOIDANCE.set(false);
        Config.BRANCHES_ENABLED.set(false);
        Config.STRUCTURE_TARGETING.set(false);
        Config.PARALLEL_MERGE_ENABLED.set(false);

        final int ARENA_CLEARANCE = 128;
        final int AHEAD = 24;   // chunks ahead to keep loaded (covers one long placement step)
        final int SIDE = 3;
        final int RENDER_RUN = 200;        // blocks the head must net-advance
        final int RENDER_DRIVE_TICKS = 400;

        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        ChunkPos initialChunk = new ChunkPos(((origin.getX() + 24) >> 4) + ARENA_CLEARANCE, origin.getZ() >> 4);
        forceCorridor(level, initialChunk.x, initialChunk.z, dir, lat, AHEAD, SIDE);

        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, orchestrator);

        final TrackExpansionHead[] head = new TrackExpansionHead[1];
        final BlockPos[] anchor = new BlockPos[1];

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    BlockPos rawPos = StartingTrainPlacer.getInitialTrackPosition(
                            level, initialChunk, config, level.getSharedSpawnPos(), dir);
                    int plannerY = com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler
                            .forLevel(level).getBaseHeight(rawPos.getX(), rawPos.getZ());
                    BlockPos initialTrackPos = new BlockPos(rawPos.getX(), plannerY, rawPos.getZ());
                    StartingTrainPlacer.placeInitialTrack(level, initialTrackPos, dir);
                    anchor[0] = initialTrackPos;
                })
                .thenExecuteAfter(6, () -> {
                    TrackExpansionHead forward = seedRouteFollowingHeadToTarget(
                            level, config, orchestrator, anchor[0], dir,
                            anchor[0].relative(dir, RENDER_RUN + 64));
                    helper.assertTrue(forward != null, "no forward (" + dir + ") head was created");
                    head[0] = forward;
                })
                .thenExecuteFor(RENDER_DRIVE_TICKS, () -> {
                    TrackExpansionHead h = head[0];
                    if (h == null || h.isComplete()) {
                        return;
                    }
                    if (chebyshev(h.getPosition(), anchor[0]) >= RENDER_RUN) {
                        return;
                    }
                    if (h.getTerrainPlanState().isRouteRebuilding()) {
                        return;
                    }
                    // Moving window: keep the corridor AHEAD of the head's current tip generated.
                    ChunkPos hc = new ChunkPos(h.getPosition());
                    forceCorridor(level, hc.x, hc.z, dir, lat, AHEAD, SIDE);
                    orchestrator.stepHead(h);
                })
                .thenExecute(() -> {
                    Config.ALL_AVOIDANCE.set(prevAvoidance);
                    Config.BRANCHES_ENABLED.set(prevBranches);
                    Config.STRUCTURE_TARGETING.set(prevStructureTargeting);
                    Config.PARALLEL_MERGE_ENABLED.set(prevParallelMerge);

                    BlockPos a = anchor[0];
                    BlockPos finalPos = head[0].getPosition();
                    BlockPos mid = new BlockPos((a.getX() + finalPos.getX()) / 2, a.getY(),
                            (a.getZ() + finalPos.getZ()) / 2);
                    int half = chebyshev(a, finalPos) / 2 + 24;
                    Set<BlockPos> track = scanTrack(level, mid, half);
                    Set<BlockPos> run = largestComponent(track);
                    int span = spanOf(run);
                    int orphans = track.size() - run.size();
                    RailwaysUntold.LOGGER.info(
                            "[GAMETEST/corridor-render] anchor={} head={} track={} largestRun={} span={} orphans={}",
                            a, finalPos, track.size(), run.size(), span, orphans);

                    // The driver's invariant: the corridor render lays a single long connected run that
                    // spans the driven distance - i.e. generation self-completes along the corridor and
                    // the dominant component is gap-free over its length.
                    helper.assertTrue(span >= RENDER_RUN - 8,
                            "corridor-render run too short: spans " + span + " (need " + (RENDER_RUN - 8) + ")");
                    helper.assertTrue(run.size() >= RENDER_RUN,
                            "corridor-render largest run too sparse: " + run.size() + " (need " + RENDER_RUN + ")");
                    // `orphans` (track blocks outside the largest component) is logged, not asserted:
                    // block-adjacency over-counts SCurve45 lateral excursions on a long run, so it is a
                    // noisy seam proxy. Compiled-path seam detection (RouteSeamInspector / [COMPILE]
                    // capture -> CompileCaseTest) is the precise disconnect gate.
                })
                .thenSucceed();
    }

    /**
     * Force-loads (and generates to FULL) a moving window: {@code ahead} chunks forward of {@code (cx,cz)}
     * in {@code dir}, spread {@code ±side} on the {@code lat} axis. The synchronous CI analogue of the
     * autoload corridor; chunks behind are left for the normal release sweep / world teardown.
     */
    private static void forceCorridor(ServerLevel level, int cx, int cz, Direction dir, Direction lat,
                                      int ahead, int side) {
        for (int f = 0; f <= ahead; f++) {
            for (int s = -side; s <= side; s++) {
                int x = cx + dir.getStepX() * f + lat.getStepX() * s;
                int z = cz + dir.getStepZ() * f + lat.getStepZ() * s;
                level.setChunkForced(x, z, true);
                level.getChunk(x, z);
            }
        }
    }

    // Directly exercises BranchPlacementExecutor end-to-end. The decision pipeline only emits a BRANCH
    // behind a hardcoded 10% per-position roll (forced only after a 700-block run), so it can't be coaxed
    // into firing deterministically within a bounded drive. Instead the shared driver below seeds a head
    // on the flat baseline, synthesizes the BRANCH decision the pipeline would produce, and invokes the
    // executor directly - reaching the handler the band-drive drivers deliberately disable, as a fast,
    // deterministic reproduction for future debugging. The two tests differ only in whether the head is
    // branch-eligible, which selects the executor's success path vs. its validation-failure fallback.

    // Receives the executor's outcome (after config is restored) so each test asserts
    // its own expectations against the placed world.
    @FunctionalInterface
    private interface BranchOutcomeAssertion {
        void check(GameTestHelper helper, ServerLevel level, BlockPos anchor,
                   HandlerResult result, TrackExpansionHead branchHead);
    }

    // Seeds an eastbound head, builds a SOUTH-branch decision + a real PlacementContext, invokes
    // BranchPlacementExecutor, then hands the outcome to `assertion`. Eligibility is driven entirely by the
    // head's own blocksSinceLastBranch against a fixed MIN_BRANCH_SPACING: an eligible head clears the gate
    // (validateSpawn passes -> branch), an ineligible head sits below it (validateSpawn fails -> the
    // executor's straight-fallback path). GameTests in a batch run concurrently and share Config and
    // TrackPlacementSavedData, so the two scenarios must NOT diverge on any global: both set the same
    // spacing, the seed+invoke+assert happen in one synchronous step (no server tick lets the auto-expander
    // drive the head mid-scenario), and the head is selected by proximity to this scenario's own anchor
    // rather than "the first east head" (which restoreFromSavedData can cross with the other scenario's).
    private static final int BRANCH_FIXED_SPACING = 100;

    private static void runBranchExecutorScenario(GameTestHelper helper, boolean eligible,
                                                  boolean sideBySide, int laneZChunks,
                                                  BranchOutcomeAssertion assertion) {
        ServerLevel level = helper.getLevel();
        Direction dir = Direction.EAST;
        // SOUTH is both the branch direction and (for an east head with side-by-side on) the companion
        // side, so an eligible sideBySide run routes through handleCompanionBranch rather than handleMainBranch.
        Direction branchDir = Direction.SOUTH;

        // Both scenarios set the SAME spacing, so concurrent races on this global are harmless.
        // (ConfigValue.set is in-memory only - it does not write the config file.)
        int prevSpacing = Config.MIN_BRANCH_SPACING.get();
        Config.MIN_BRANCH_SPACING.set(BRANCH_FIXED_SPACING);

        // Own lane far east of the arena cluster; force-load the parent corridor (east) and the branch
        // corridor (south) to FULL so neither the parent segments nor the branch curve defer mid-place.
        // laneZChunks separates the two scenarios in Z: GameTest packs same-template arenas at the same X,
        // so without it both corridors would land on the same far-east chunks and overwrite each other.
        final int ARENA_CLEARANCE = 160;
        final int AHEAD_CHUNKS = 6;
        final int SOUTH_CHUNKS = 6;
        final int MARGIN_CHUNKS = 3;
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        ChunkPos initialChunk = new ChunkPos(
                ((origin.getX() + 24) >> 4) + ARENA_CLEARANCE, (origin.getZ() >> 4) + laneZChunks);
        for (int cx = initialChunk.x - MARGIN_CHUNKS; cx <= initialChunk.x + AHEAD_CHUNKS; cx++) {
            for (int cz = initialChunk.z - MARGIN_CHUNKS; cz <= initialChunk.z + SOUTH_CHUNKS; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());

        final BlockPos[] anchor = new BlockPos[1];

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    // Anchor at the planner's flat-surface Y (not the heightmap Y) for the same reason as
                    // expansionPlacesConnectedRun: adjacent arenas leave blocks that float the heightmap.
                    BlockPos rawPos = StartingTrainPlacer.getInitialTrackPosition(
                            level, initialChunk, config, level.getSharedSpawnPos(), dir);
                    int plannerY = NoiseTerrainSampler.forLevel(level).getBaseHeight(rawPos.getX(), rawPos.getZ());
                    BlockPos initialTrackPos = new BlockPos(rawPos.getX(), plannerY, rawPos.getZ());
                    StartingTrainPlacer.placeInitialTrack(level, initialTrackPos, dir);
                    anchor[0] = initialTrackPos;

                    if (sideBySide) {
                        // The companion track sits `separation` blocks to the companion side (SOUTH for an
                        // east head). Anchor it too so handleCompanionBranch's companion segment has a node
                        // to connect from, the way a real side-by-side run would have laid it alongside.
                        int sep = CompanionTrackPlacer.getCompanionSeparation();
                        StartingTrainPlacer.placeInitialTrack(level, initialTrackPos.relative(branchDir, sep), dir);
                    }
                })
                // Seed, invoke, and assert all in one step: no server tick passes, so the tick-driven
                // auto-expander (ChunkLoadTrackExpander) never drives the head, and the outcome is purely
                // this single executor call's. (scanTrack reads block states, which the executor places
                // synchronously, so no settle wait is needed.)
                .thenExecuteAfter(6, () -> {
                    ExpansionHeadManager headManager = orchestrator.getHeadManager();
                    headManager.initializeHeadsWithCustomStart(
                            anchor[0], anchor[0].relative(dir), dir, level, config, null);

                    // Pick THIS scenario's east head by proximity to its anchor - restoreFromSavedData may
                    // surface the other (concurrent) scenario's head, which lives a lane away in Z.
                    TrackExpansionHead forward = null;
                    double bestDist = Double.MAX_VALUE;
                    for (TrackExpansionHead candidate : headManager.getActiveHeads()) {
                        if (candidate.getDirection() != dir) {
                            continue;
                        }
                        double d = candidate.getPosition().distSqr(anchor[0]);
                        if (d < bestDist) {
                            bestDist = d;
                            forward = candidate;
                        }
                    }
                    helper.assertTrue(forward != null, "no forward (" + dir + ") head was created");

                    // Complete this scenario's other seeded heads (e.g. the west sibling at the same anchor)
                    // so they don't trip validateSpawn's adequate-spacing check against the branch point.
                    // Restrict to heads near THIS anchor so the concurrent scenario's head (a lane away) is
                    // left untouched.
                    for (TrackExpansionHead sibling : headManager.getActiveHeads()) {
                        if (sibling != forward && sibling.getPosition().distSqr(anchor[0]) < 64 * 64) {
                            sibling.markComplete();
                        }
                    }
                    // markComplete doesn't touch the manager's cached active-head list; refresh it so the
                    // completed siblings drop out of validateSpawn's adequate-spacing scan below.
                    headManager.invalidateActiveHeadsCache();

                    // Eligibility is per-head: reset to 0, then (eligible) clear the spacing gate.
                    forward.resetBranchCounter();
                    if (eligible) {
                        forward.incrementBranchDistance(BRANCH_FIXED_SPACING * 10);
                        forward.getVillageState().setExplorationTarget(anchor[0].relative(dir, 400));
                    }

                    BlockPos currentPos = forward.getPosition();
                    TerrainScanner.TerrainScan scan = TerrainScanner.scanAhead(
                            level, currentPos, dir, PlacementConstants.STANDARD_SEGMENT_LENGTH + 1, config);
                    PlacementDecision decision = PlacementDecision.branch(
                            currentPos, PlacementConstants.STANDARD_SEGMENT_LENGTH, dir, branchDir, scan);
                    TrackExpansionHead[] branchHead = new TrackExpansionHead[1];
                    PlacementContext ctx = new PlacementContext(
                            level, forward, decision, scan, currentPos, dir, config,
                            headManager, TrackPlacementSavedData.get(level),
                            bh -> branchHead[0] = bh,
                            () -> { },
                            TunnelEscapeSystem::tryTunnelEscape,
                            new Random(level.getSeed()));

                    // SIDE_BY_SIDE_TRACKS is global, but this whole step is one synchronous server-tick
                    // callback, so setting and restoring it here is invisible to the concurrent scenarios.
                    boolean prevSideBySide = Config.SIDE_BY_SIDE_TRACKS.get();
                    Config.SIDE_BY_SIDE_TRACKS.set(sideBySide);
                    HandlerResult result = new BranchPlacementExecutor().handle(ctx);
                    Config.SIDE_BY_SIDE_TRACKS.set(prevSideBySide);

                    Config.MIN_BRANCH_SPACING.set(prevSpacing);
                    assertion.check(helper, level, anchor[0], result, branchHead[0]);
                })
                .thenSucceed();
    }

    // Success path: an eligible head branches. Asserts the executor reports success, spawns a depth-1
    // branch head off the parent, and lays track laterally (SOUTH) off the main line.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void branchExecutorPlacesBranchOffMainLine(GameTestHelper helper) {
        runBranchExecutorScenario(helper, true, false, 0, (h, level, anchor, result, branchHead) -> {
            h.assertTrue(result != null && result.success(),
                    "branch executor did not report success: "
                            + (result == null ? "null result" : result.deferReason()));
            h.assertTrue(branchHead != null, "executor created no branch head");
            h.assertTrue(branchHead.getBranchDepth() == 1,
                    "branch head depth should be 1, was " + branchHead.getBranchDepth());

            // The branch curve turns SOUTH off the parent: assert track exists laterally off the main line.
            BlockPos scanCentre = anchor.relative(Direction.EAST, 5).relative(Direction.SOUTH, 12);
            Set<BlockPos> track = scanTrack(level, scanCentre, 40);
            int maxLateral = 0;
            for (BlockPos p : track) {
                maxLateral = Math.max(maxLateral, p.getZ() - anchor.getZ());
            }
            RailwaysUntold.LOGGER.info(
                    "[GAMETEST/branch] anchor={} branchHeadDepth={} {} track blocks near junction; max south offset {}",
                    anchor, branchHead.getBranchDepth(), track.size(), maxLateral);
            h.assertTrue(track.size() >= 16, "branch placed too few track blocks: " + track.size());
            h.assertTrue(maxLateral >= 3,
                    "no track placed laterally off the main line (max south offset " + maxLateral + ")");
        });
    }

    // Validation-failure fallback: an ineligible head (branch distance 0, below the fixed spacing) fails
    // validateSpawn, so the executor places a straight continuation instead of branching. Asserts it still
    // reports success, creates NO branch head, and lays track along the main line, not laterally.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void branchExecutorFallsBackToStraightWhenSpawnInvalid(GameTestHelper helper) {
        runBranchExecutorScenario(helper, false, false, 200, (h, level, anchor, result, branchHead) -> {
            h.assertTrue(result != null && result.success(),
                    "validation-failure fallback did not report success: "
                            + (result == null ? "null result" : result.deferReason()));
            h.assertTrue(branchHead == null, "validation should have failed, but a branch head was created");

            // The fallback lays a straight EAST continuation: track along the main line, none laterally.
            BlockPos scanCentre = anchor.relative(Direction.EAST, 8);
            Set<BlockPos> track = scanTrack(level, scanCentre, 40);
            int maxLateral = 0;
            for (BlockPos p : track) {
                maxLateral = Math.max(maxLateral, Math.abs(p.getZ() - anchor.getZ()));
            }
            RailwaysUntold.LOGGER.info(
                    "[GAMETEST/branch-fallback] anchor={} {} track blocks; max lateral offset {}",
                    anchor, track.size(), maxLateral);
            h.assertTrue(track.size() >= 16, "fallback placed too few track blocks: " + track.size());
            h.assertTrue(maxLateral <= 2,
                    "fallback should stay on the main line, but track strayed " + maxLateral + " laterally");
        });
    }

    // Companion-branch path: with side-by-side tracks enabled, an eligible east head's SOUTH branch matches
    // its companion side, so the executor routes through handleCompanionBranch - the main line runs straight
    // through the junction while the branch curves off the companion track. Asserts a depth-1 branch head and
    // track laid off the companion (SOUTH) side, beyond the companion separation.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void branchExecutorPlacesBranchFromCompanionTrack(GameTestHelper helper) {
        runBranchExecutorScenario(helper, true, true, 400, (h, level, anchor, result, branchHead) -> {
            h.assertTrue(result != null && result.success(),
                    "companion branch did not report success: "
                            + (result == null ? "null result" : result.deferReason()));
            h.assertTrue(branchHead != null, "executor created no branch head on the companion path");
            h.assertTrue(branchHead.getBranchDepth() == 1,
                    "branch head depth should be 1, was " + branchHead.getBranchDepth());

            // The companion track and its branch curve lie SOUTH of the main line, past the separation.
            BlockPos scanCentre = anchor.relative(Direction.EAST, 5).relative(Direction.SOUTH, 16);
            Set<BlockPos> track = scanTrack(level, scanCentre, 48);
            int maxLateral = 0;
            for (BlockPos p : track) {
                maxLateral = Math.max(maxLateral, p.getZ() - anchor.getZ());
            }
            RailwaysUntold.LOGGER.info(
                    "[GAMETEST/branch-companion] anchor={} branchHeadDepth={} {} track blocks; max south offset {}",
                    anchor, branchHead.getBranchDepth(), track.size(), maxLateral);
            h.assertTrue(track.size() >= 16, "companion branch placed too few track blocks: " + track.size());
            h.assertTrue(maxLateral >= CompanionTrackPlacer.getCompanionSeparation(),
                    "no branch track placed off the companion side (max south offset " + maxLateral + ")");
        });
    }

    // Diagonal placement: the planner won't emit a diagonal chain on a direct off-axis target (the
    // forward-axis prefix forces a cardinal run + 90° turn), so a normal drive never reaches the diagonal
    // executors. Instead this compiles a real 45° coarse run into a DIAGONAL_ENTRY -> DIAGONAL_STRAIGHT ->
    // DIAGONAL_EXIT chain (DiagonalCompilerTest pins that emission), sets it on the head, and drives the
    // segments through the real dispatch (executeNextSegment -> registry -> executor.handle) - the same path
    // the orchestrator uses, but synchronously so the auto-expander can't interfere. Asserts all three
    // diagonal executors run successfully and lay a connected run advancing BOTH axes (a real diagonal).
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void diagonalExecutorsPlaceConnectedDiagonalRun(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction dir = Direction.EAST;

        // Own lane; force-load a box covering the anchor, the NE diagonal reach (+96 X / +96 Z) and the
        // cardinal tail (+152 X) to FULL so the diagonal placers never defer mid-run.
        final int ARENA_CLEARANCE = 160;
        final int MARGIN_CHUNKS = 3;
        final int EAST_CHUNKS = 12;
        final int SOUTH_CHUNKS = 9;
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        ChunkPos initialChunk = new ChunkPos(((origin.getX() + 24) >> 4) + ARENA_CLEARANCE, origin.getZ() >> 4);
        for (int cx = initialChunk.x - MARGIN_CHUNKS; cx <= initialChunk.x + EAST_CHUNKS; cx++) {
            for (int cz = initialChunk.z - MARGIN_CHUNKS; cz <= initialChunk.z + SOUTH_CHUNKS; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        final BlockPos[] anchor = new BlockPos[1];

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    BlockPos rawPos = StartingTrainPlacer.getInitialTrackPosition(
                            level, initialChunk, config, level.getSharedSpawnPos(), dir);
                    int plannerY = NoiseTerrainSampler.forLevel(level).getBaseHeight(rawPos.getX(), rawPos.getZ());
                    BlockPos initialTrackPos = new BlockPos(rawPos.getX(), plannerY, rawPos.getZ());
                    StartingTrainPlacer.placeInitialTrack(level, initialTrackPos, dir);
                    anchor[0] = initialTrackPos;
                })
                // Seed, set the compiled diagonal route, and drive it all in one synchronous step.
                .thenExecuteAfter(6, () -> {
                    ExpansionHeadManager headManager = orchestrator.getHeadManager();
                    headManager.initializeHeadsWithCustomStart(
                            anchor[0], anchor[0].relative(dir), dir, level, config, null);
                    TrackExpansionHead forward = null;
                    double bestDist = Double.MAX_VALUE;
                    for (TrackExpansionHead candidate : headManager.getActiveHeads()) {
                        if (candidate.getDirection() != dir) {
                            continue;
                        }
                        double d = candidate.getPosition().distSqr(anchor[0]);
                        if (d < bestDist) {
                            bestDist = d;
                            forward = candidate;
                        }
                    }
                    helper.assertTrue(forward != null, "no forward (" + dir + ") head was created");

                    BlockPos a = anchor[0];
                    // A 45° run (NE = +X +Z) off the anchor, plus a short cardinal EAST tail so the diagonal
                    // run isn't the last run (which would force the entry+exit-fits fallback). Compiled with
                    // isFromTrackTip=false so no forward-axis prefix flattens the diagonal.
                    List<CoarseRoute.CoarseWaypoint> wps = new ArrayList<>();
                    for (int i = 0; i <= 12; i++) {
                        wps.add(new CoarseRoute.CoarseWaypoint(
                                a.offset(i * 8, 0, i * 8), a.getY(), CoarseRoute.WaypointType.TERRAIN_FOLLOW));
                    }
                    for (int j = 1; j <= 7; j++) {
                        wps.add(new CoarseRoute.CoarseWaypoint(
                                a.offset(96 + j * 8, 0, 96), a.getY(), CoarseRoute.WaypointType.TERRAIN_FOLLOW));
                    }
                    BlockPos target = wps.get(wps.size() - 1).position();
                    PlannedPath path = PrecisionRouteCompiler.compile(wps, a, target, dir, false);
                    helper.assertTrue(path.valid, "diagonal route failed to compile");
                    boolean hasDiagonal = false;
                    for (PathSegment seg : path.segments) {
                        if (seg.type == PathSegment.Type.DIAGONAL_STRAIGHT) {
                            hasDiagonal = true;
                        }
                    }
                    helper.assertTrue(hasDiagonal, "compiled route has no DIAGONAL_STRAIGHT to drive");

                    PrecisionRoute route = new PrecisionRoute(new CoarseRoute(forward.getHeadId(), wps), path);
                    forward.getTerrainPlanState().setPrecisionRoute(route);

                    // Drive the route segment-by-segment through the real dispatch, synchronously.
                    Set<PlacementDecision.Type> executed = EnumSet.noneOf(PlacementDecision.Type.class);
                    int guard = 0;
                    while (route.hasRemainingPath() && guard++ < 40) {
                        BlockPos pos = forward.getPosition();
                        Direction d = forward.getDirection();
                        TerrainScanner.TerrainScan scan = TerrainScanner.scanAhead(
                                level, pos, d, PlacementConstants.STANDARD_SEGMENT_LENGTH + 1, config);
                        PlacementDecision decision = PathExecutor.executeNextSegment(route, pos, d, scan, level);
                        if (decision == null) {
                            break;
                        }
                        executed.add(decision.getType());
                        PlacementExecutor executor = PlacementExecutorRegistry.getExecutor(decision.getType());
                        helper.assertTrue(executor != null, "no executor for " + decision.getType());
                        PlacementContext ctx = new PlacementContext(
                                level, forward, decision, scan, pos, d, config,
                                headManager, TrackPlacementSavedData.get(level),
                                bh -> { }, () -> { },
                                TunnelEscapeSystem::tryTunnelEscape, new Random(level.getSeed()));
                        HandlerResult res = executor.handle(ctx);
                        helper.assertTrue(res != null && res.success(),
                                "segment " + decision.getType() + " did not succeed: "
                                        + (res == null ? "null" : res.deferReason()));
                    }

                    helper.assertTrue(
                            executed.contains(PlacementDecision.Type.DIAGONAL_ENTRY)
                                    && executed.contains(PlacementDecision.Type.DIAGONAL_STRAIGHT)
                                    && executed.contains(PlacementDecision.Type.DIAGONAL_EXIT),
                            "the three diagonal executors did not all run: " + executed);

                    // Diagonal track is laid as Create beziers (endpoint nodes + a virtual span, not a dense
                    // block run), so assert the placed track's EXTENT reaches out in both axes - proving the
                    // chain ran a real 45° leg, not just the cardinal lead-in - rather than a dense-component
                    // span, which the sparse bezier nodes wouldn't satisfy.
                    Set<BlockPos> track = scanTrack(level, a.offset(60, 0, 48), 96);
                    int maxXoff = 0, maxZoff = 0;
                    for (BlockPos p : track) {
                        maxXoff = Math.max(maxXoff, p.getX() - a.getX());
                        maxZoff = Math.max(maxZoff, p.getZ() - a.getZ());
                    }
                    RailwaysUntold.LOGGER.info(
                            "[GAMETEST/diagonal-exec] anchor={} segments={} {} track blocks; maxXoff={} maxZoff={}",
                            a, executed, track.size(), maxXoff, maxZoff);
                    helper.assertTrue(track.size() >= 20, "diagonal route placed too few track blocks: " + track.size());
                    helper.assertTrue(maxXoff >= 40 && maxZoff >= 40,
                            "diagonal chain did not advance both axes: maxXoff=" + maxXoff + " maxZoff=" + maxZoff);
                })
                .thenSucceed();
    }

    // Same deterministic driver as expansionPlacesConnectedRun, but routed to a 45-degree off-axis
    // target. An off-axis route forces the precision compiler to emit DIAGONAL segments (and the
    // curve/SCurve45 corrections that join them), driving the DiagonalStraight/DiagonalCurve/Curve/
    // SCurve45 placement executors that a purely-cardinal run never touches. Asserts a connected run
    // that advances BOTH along `dir` and laterally - i.e. the head actually placed a diagonal path.
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void expansionPlacesDiagonalRun(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction dir = Direction.EAST;
        Direction lateral = dir.getClockWise(); // SOUTH

        boolean prevAvoidance = Config.ALL_AVOIDANCE.get();
        boolean prevBranches = Config.BRANCHES_ENABLED.get();
        boolean prevStructureTargeting = Config.STRUCTURE_TARGETING.get();
        boolean prevParallelMerge = Config.PARALLEL_MERGE_ENABLED.get();
        Config.ALL_AVOIDANCE.set(false);
        Config.BRANCHES_ENABLED.set(false);
        Config.STRUCTURE_TARGETING.set(false);
        Config.PARALLEL_MERGE_ENABLED.set(false);

        // Anchor far EAST of this arena in flat baseline, well clear of other arenas' corridors, and
        // offset on Z from expansionPlacesConnectedRun's lane so the two drivers never share chunks.
        final int ARENA_CLEARANCE = 160;        // chunks east of the arena
        final int DIAG_LEN = 80;                // forward + lateral reach of the diagonal target
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        ChunkPos initialChunk = new ChunkPos(((origin.getX() + 24) >> 4) + ARENA_CLEARANCE, (origin.getZ() >> 4) + 16);

        // Force-load the bounding box from the anchor chunk to past the diagonal target, fully to FULL.
        int diagChunks = (DIAG_LEN >> 4) + 3;
        for (int cx = initialChunk.x - 3; cx <= initialChunk.x + diagChunks; cx++) {
            for (int cz = initialChunk.z - 3; cz <= initialChunk.z + diagChunks; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, orchestrator);

        final TrackExpansionHead[] head = new TrackExpansionHead[1];
        final BlockPos[] anchor = new BlockPos[1];

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    BlockPos rawPos = StartingTrainPlacer.getInitialTrackPosition(
                            level, initialChunk, config, level.getSharedSpawnPos(), dir);
                    int plannerY = com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler
                            .forLevel(level).getBaseHeight(rawPos.getX(), rawPos.getZ());
                    BlockPos initialTrackPos = new BlockPos(rawPos.getX(), plannerY, rawPos.getZ());
                    StartingTrainPlacer.placeInitialTrack(level, initialTrackPos, dir);
                    anchor[0] = initialTrackPos;
                })
                .thenExecuteAfter(6, () -> {
                    // 45-degree target: equal forward and lateral offset forces a genuinely diagonal route.
                    BlockPos target = anchor[0].relative(dir, DIAG_LEN).relative(lateral, DIAG_LEN);
                    TrackExpansionHead forward = seedRouteFollowingHeadToTarget(
                            level, config, orchestrator, anchor[0], dir, target);
                    helper.assertTrue(forward != null, "no forward (" + dir + ") head was created");
                    head[0] = forward;
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    TrackExpansionHead h = head[0];
                    if (h == null || h.isComplete()) {
                        return;
                    }
                    if (chebyshev(h.getPosition(), anchor[0]) >= RUN_LENGTH) {
                        return;
                    }
                    if (h.getTerrainPlanState().isRouteRebuilding()) {
                        return;
                    }
                    orchestrator.stepHead(h);
                })
                .thenExecute(() -> {
                    Config.ALL_AVOIDANCE.set(prevAvoidance);
                    Config.BRANCHES_ENABLED.set(prevBranches);
                    Config.STRUCTURE_TARGETING.set(prevStructureTargeting);
                    Config.PARALLEL_MERGE_ENABLED.set(prevParallelMerge);

                    BlockPos a = anchor[0];
                    BlockPos finalPos = head[0].getPosition();
                    BlockPos mid = new BlockPos((a.getX() + finalPos.getX()) / 2, a.getY(),
                            (a.getZ() + finalPos.getZ()) / 2);
                    int half = chebyshev(a, finalPos) / 2 + 24;
                    Set<BlockPos> track = scanTrack(level, mid, half);
                    Set<BlockPos> run = largestComponent(track);
                    int span = spanOf(run);
                    RailwaysUntold.LOGGER.info(
                            "[GAMETEST/diagonal] anchor={} head={}: {} track blocks; largest run {} blocks spanning {}",
                            a, finalPos, track.size(), run.size(), span);

                    helper.assertTrue(track.size() >= 16,
                            "diagonal expansion placed too few track blocks: " + track.size());
                    helper.assertTrue(run.size() >= RUN_LENGTH,
                            "longest connected run too sparse: " + run.size() + " (need " + RUN_LENGTH + ")");

                    int dx = finalPos.getX() - a.getX();
                    int dz = finalPos.getZ() - a.getZ();
                    int forwardAdvance = dx * dir.getStepX() + dz * dir.getStepZ();
                    int lateralAdvance = dx * lateral.getStepX() + dz * lateral.getStepZ();
                    // Both components must advance: a diagonal route progresses forward AND sideways.
                    helper.assertTrue(forwardAdvance >= 16,
                            "head did not advance " + dir + ": " + forwardAdvance);
                    helper.assertTrue(lateralAdvance >= 8,
                            "head did not advance laterally (not diagonal): " + lateralAdvance);
                })
                .thenSucceed();
    }

    // In-world replay (increment 4b): reproduces a captured [REPLAY] profile as real terrain via a
    // ReplayTerrainAtlas overlay, then drives a head across it and asserts placement lays a connected run
    // over the reproduced ridge. This reaches what the pure-planner replay (ReplayCaseTest) cannot:
    // placement over the actual terrain a production plan saw. The embedded record is the same ridge as
    // sample-ridge.replay - a 40-high ridge peaking at record-x 128, steep enough (>0.25) that the route
    // tunnels the crest - so a connected run that advances the full span proves the head crossed it.
    //
    // Anchored at a fixed far-away coordinate (like the pipeline test's ~4.5M anchor) so the overlay
    // region and its force-loaded chunks can never collide with another test's arena or corridor.
    private static final String REPLAY_RIDGE = """
            REPLAY v2
            start 0 72 0
            target 256 72 0
            headId 00000000-0000-0000-0000-000000000001
            headDir EAST
            arrivalDir -
            descentHintY -1
            isFromTrackTip false
            seaLevel 63
            maxSlopeRatio 0.25
            structures 0
            tracks 0
            oceans 0
            waters 0
            heights 33 0,0,72 8,0,72 16,0,72 24,0,72 32,0,72 40,0,72 48,0,72 56,0,72 64,0,72 72,0,75 80,0,80 88,0,85 96,0,91 104,0,96 112,0,101 120,0,107 128,0,112 136,0,107 144,0,101 152,0,96 160,0,91 168,0,85 176,0,80 184,0,75 192,0,72 200,0,72 208,0,72 216,0,72 224,0,72 232,0,72 240,0,72 248,0,72 256,0,72
            """;

    private static final int REPLAY_ANCHOR_X = 6_000_000;
    private static final int REPLAY_ANCHOR_Z = 6_000_000;

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void replayReproducesRidgeRunInWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ReplayRecord rec = ReplayRecord.parse(REPLAY_RIDGE);
        Direction dir = rec.headDir;

        // Same deterministic placement driver as expansionPlacesConnectedRun: disable the three
        // position/seed-dependent steering behaviours so the head follows its cardinal route, restored
        // at the end so a failure can't leak the disabled state into another test.
        boolean prevAvoidance = Config.ALL_AVOIDANCE.get();
        boolean prevBranches = Config.BRANCHES_ENABLED.get();
        boolean prevStructureTargeting = Config.STRUCTURE_TARGETING.get();
        boolean prevParallelMerge = Config.PARALLEL_MERGE_ENABLED.get();
        Config.ALL_AVOIDANCE.set(false);
        Config.BRANCHES_ENABLED.set(false);
        Config.STRUCTURE_TARGETING.set(false);
        Config.PARALLEL_MERGE_ENABLED.set(false);

        // Map record (0,0) -> the fixed anchor so the captured profile lands at this corridor.
        final int offsetX = REPLAY_ANCHOR_X - rec.start.getX();
        final int offsetZ = REPLAY_ANCHOR_Z - rec.start.getZ();
        // Bound the drive to just past the captured ridge's descent so the head crosses the feature but
        // never reaches a target - like expansionPlacesConnectedRun, which stops short so the head never
        // enters infinite exploration (which would wander off the captured region and drift-halt).
        final int ridgeEnd = rec.target.getX() - rec.start.getX();   // 256, end of captured data
        final int RIDGE_RUN = ridgeEnd - 32;                          // cross peak (x=128) + descent
        // Target far past the captured region (clamped flat by the atlas) so the bounded drive can't
        // reach it. The planner samples to here via getBaseHeight (no chunk load needed).
        final int worldTargetX = offsetX + ridgeEnd + 256;
        final int worldTargetZ = offsetZ + rec.start.getZ();
        final int LATERAL = 64; // room for the precision compiler's lateral curve excursions
        final int MARGIN = 48;

        // Register the replay terrain BEFORE force-loading so the corridor chunks generate from it. The
        // region spans all the way to the target so the planner's sampling there stays on replay terrain.
        OverlayTerrainAtlas.clear();
        OverlayTerrainAtlas.register(new OverlayTerrainAtlas.Region(
                REPLAY_ANCHOR_X - MARGIN, REPLAY_ANCHOR_Z - LATERAL,
                worldTargetX + MARGIN, REPLAY_ANCHOR_Z + LATERAL,
                new ReplayTerrainAtlas(rec.heights, rec.waterCoords, rec.seaLevel, rec.start.getZ(), offsetX, offsetZ)));

        // Force the head's actual placement reach to FULL so the surface heightmap is populated (the
        // anchor Y is read from it) and the head never defers mid-run for an ungenerated chunk. Only the
        // driven span needs blocks; the route plans past it from the generator, which needs no chunks.
        for (int cx = (REPLAY_ANCHOR_X - MARGIN) >> 4; cx <= (REPLAY_ANCHOR_X + RIDGE_RUN + 96) >> 4; cx++) {
            for (int cz = (REPLAY_ANCHOR_Z - LATERAL) >> 4; cz <= (REPLAY_ANCHOR_Z + LATERAL) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, orchestrator);

        final TrackExpansionHead[] head = new TrackExpansionHead[1];
        final BlockPos[] anchor = new BlockPos[1];

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    NoiseTerrainSampler sampler = NoiseTerrainSampler.forLevel(level);
                    // The reproduced terrain must match the captured profile: the planner's sampler reads
                    // back the record's start height at the anchor and its 112-high crest at record-x 128.
                    // This is the core of the in-world replay - the captured profile became real terrain.
                    int startH = sampler.getBaseHeight(REPLAY_ANCHOR_X, REPLAY_ANCHOR_Z);
                    int peakH = sampler.getBaseHeight(REPLAY_ANCHOR_X + 128, REPLAY_ANCHOR_Z);
                    helper.assertTrue(startH == 72, "reproduced start height " + startH + " (record says 72)");
                    helper.assertTrue(peakH >= 108, "reproduced ridge crest " + peakH + " (record says 112)");
                    // Anchor at the record's start, at the Y the planner's sampler reports for it (72).
                    BlockPos a = new BlockPos(REPLAY_ANCHOR_X, startH, REPLAY_ANCHOR_Z);
                    StartingTrainPlacer.placeInitialTrack(level, a, dir);
                    anchor[0] = a;
                })
                .thenExecuteAfter(6, () -> {
                    BlockPos a = anchor[0];
                    int targetY = NoiseTerrainSampler.forLevel(level).getBaseHeight(worldTargetX, worldTargetZ);
                    BlockPos target = new BlockPos(worldTargetX, targetY, worldTargetZ);
                    ExpansionHeadManager headManager = orchestrator.getHeadManager();
                    headManager.initializeHeadsWithCustomStart(a, a.relative(dir), dir, level, config, null);
                    TrackExpansionHead forward = null;
                    for (TrackExpansionHead h : headManager.getActiveHeads()) {
                        if (h.getDirection() == dir) {
                            forward = h;
                        } else {
                            h.markComplete();
                        }
                    }
                    helper.assertTrue(forward != null, "no forward (" + dir + ") head was created");
                    // Attach a plain route to the target (no village/station target) - the same minimal
                    // driver seedRouteFollowingHead uses, so the head just follows terrain to the target.
                    CoarseRouteFactory.createAndAttach(level, forward, target);
                    head[0] = forward;
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    TrackExpansionHead h = head[0];
                    if (h == null || h.isComplete()) {
                        return;
                    }
                    if (chebyshev(h.getPosition(), anchor[0]) >= RIDGE_RUN) {
                        return;
                    }
                    if (h.getTerrainPlanState().isRouteRebuilding()) {
                        return;
                    }
                    orchestrator.stepHead(h);
                })
                .thenExecute(() -> {
                    Config.ALL_AVOIDANCE.set(prevAvoidance);
                    Config.BRANCHES_ENABLED.set(prevBranches);
                    Config.STRUCTURE_TARGETING.set(prevStructureTargeting);
                    Config.PARALLEL_MERGE_ENABLED.set(prevParallelMerge);
                    OverlayTerrainAtlas.clear();

                    BlockPos a = anchor[0];
                    BlockPos finalPos = head[0].getPosition();
                    BlockPos mid = new BlockPos((a.getX() + finalPos.getX()) / 2,
                            (a.getY() + finalPos.getY()) / 2,
                            (a.getZ() + finalPos.getZ()) / 2);
                    int half = chebyshev(a, finalPos) / 2 + 24;
                    // The reproduced route can descend well below the ridge baseline on some runs (the
                    // async planner is not deterministic across runs). Scan a Y window tall enough to
                    // include the full descent so the span measures the true horizontal run rather than
                    // just the track that stayed near the start elevation.
                    int halfY = Math.abs(a.getY() - finalPos.getY()) / 2 + 24;
                    Set<BlockPos> track = scanTrack(level, mid, half, halfY);
                    int trackSpan = spanOf(track);
                    int forwardAdvance = (finalPos.getX() - a.getX()) * dir.getStepX()
                            + (finalPos.getZ() - a.getZ()) * dir.getStepZ();
                    RailwaysUntold.LOGGER.info(
                            "[GAMETEST/replay] anchor={} head={} {} track nodes spanning {}; advance {}",
                            a, finalPos, track.size(), trackSpan, forwardAdvance);

                    // The steep captured ridge (slope > the 0.25 limit) routes as Create bezier curves,
                    // which place endpoint track NODES joined by virtual beziers - not block-by-block
                    // straight track - so connectivity here is the node chain spanning the crossing, not a
                    // dense contiguous run (as in the flat expansionPlacesConnectedRun). Assert the head
                    // crossed the reproduced ridge and laid a node chain spanning it, executing cleanly:
                    // this is what the pure-planner replay (ReplayCaseTest) cannot reach - placement over
                    // the actual terrain a production plan saw.
                    helper.assertTrue(head[0].hasPlacedAnySegments(), "replay head placed no segments at all");
                    helper.assertTrue(track.size() >= 4,
                            "replay placed too few track nodes: " + track.size());
                    helper.assertTrue(forwardAdvance >= RIDGE_RUN - 40,
                            "replay head did not cross the ridge: advance " + forwardAdvance + " (need " + (RIDGE_RUN - 40) + ")");
                    helper.assertTrue(trackSpan >= RIDGE_RUN - 40,
                            "replay track did not span the ridge: spans " + trackSpan + " (need " + (RIDGE_RUN - 40) + ")");
                })
                .thenSucceed();
    }

    // Cave bridging fix (ELEVATED_TUNNEL over a void): a sloped tunnel run is the case that suspended
    // the track over the in-game cave - ElevatedTunnelPlacementExecutor placed no supports, so where the
    // run crossed open air there was neither decking nor ballast, just bare track + girders. We carve a
    // void with a ColumnProfileAtlas overlay (the in-world analogue of a captured [SUPPORT] profile) and
    // run the executor directly across it: the planner can't see a sub-surface void, so reproducing the
    // route is unreliable - driving the handler with an ELEVATED_TUNNEL decision is the deterministic
    // way to exercise the fixed path. Then we assert support (decking/ballast) lands near the rail over
    // the void. Girders are excluded from "support" since bare track carries those even unsupported.
    private static final int CAVE_ANCHOR_X = 7_000_000;
    private static final int CAVE_ANCHOR_Z = 7_000_000;
    private static final int CAVE_SURFACE_Y = 72;
    private static final int CAVE_FLOOR_Y = 60;   // ~11 blocks of open air below the rail
    private static final int CAVE_SPAN = 12;      // length of the elevated-tunnel run across the cave
    private static final int CAVE_LATERAL = 6;    // void half-width across the track corridor

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void elevatedTunnelBridgesCaveUnderTrack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction dir = Direction.EAST;
        WaterBridgeDetector.resetCache();

        final int caveMinX = CAVE_ANCHOR_X;
        final int caveMaxX = CAVE_ANCHOR_X + CAVE_SPAN;
        final int caveMinZ = CAVE_ANCHOR_Z - CAVE_LATERAL;
        final int caveMaxZ = CAVE_ANCHOR_Z + CAVE_LATERAL;
        final int MARGIN = 32;

        OverlayTerrainAtlas.clear();
        OverlayTerrainAtlas.register(new OverlayTerrainAtlas.Region(
                caveMinX - MARGIN, caveMinZ - 8, caveMaxX + MARGIN, caveMaxZ + 8,
                new ColumnProfileAtlas(CAVE_SURFACE_Y, caveMinX, caveMaxX, caveMinZ, caveMaxZ,
                        CAVE_FLOOR_Y, false)));

        for (int cx = (caveMinX - MARGIN) >> 4; cx <= (caveMaxX + MARGIN) >> 4; cx++) {
            for (int cz = (caveMinZ - 8) >> 4; cz <= (caveMaxZ + 8) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    // The harness must have carved an open void directly below the rail across the span.
                    BlockState belowRail = level.getBlockState(
                            new BlockPos(caveMinX + 1, CAVE_SURFACE_Y - 1, CAVE_ANCHOR_Z));
                    helper.assertTrue(belowRail.isAir(),
                            "cave column not carved: " + belowRail + " at "
                                    + (caveMinX + 1) + "," + (CAVE_SURFACE_Y - 1));

                    BlockPos start = new BlockPos(caveMinX, CAVE_SURFACE_Y, CAVE_ANCHOR_Z);
                    // End one block lower so start.Y != end.Y, the condition that makes a tunnel segment
                    // ELEVATED_TUNNEL rather than plain TUNNEL.
                    BlockPos end = new BlockPos(caveMaxX, CAVE_SURFACE_Y - 1, CAVE_ANCHOR_Z);
                    int distance = caveMaxX - caveMinX;
                    int elevationChange = end.getY() - start.getY();

                    // The Create bezier extends from an existing track node, so seed a stub at the start
                    // (in production the head's prior track provides this). Placed just west of the void.
                    StartingTrainPlacer.placeInitialTrack(level, start, dir);

                    TrackExpansionHead head = new TrackExpansionHead(1, start, dir);
                    PlacementDecision decision = PlacementDecision.elevatedTunnel(
                            start, end, distance, elevationChange, dir, null);
                    PlacementContext ctx = new PlacementContext(
                            level, head, decision, null, start, dir, config,
                            orchestrator.getHeadManager(), TrackPlacementSavedData.get(level),
                            h -> { }, () -> { }, (l, p, d, c, o, cb) -> null, new Random(0L));

                    HandlerResult result = new ElevatedTunnelPlacementExecutor().handle(ctx);
                    helper.assertTrue(result.success(),
                            "elevated tunnel handler did not succeed: " + result.deferReason());
                })
                .thenExecuteAfter(4, () -> {
                    OverlayTerrainAtlas.clear();
                    WaterBridgeDetector.resetCache();

                    // A Create bezier lays physical rail blocks only at its endpoint nodes; the span
                    // between is a virtual bezier with no per-block rail. So we count track NODES across
                    // the whole span (proving a track was laid, not floating decking) and count SUPPORT
                    // columns over the void interior (the fix). Girders are excluded from support: bare
                    // unsupported track carries them, so they must not count.
                    int trackNodes = 0;
                    int supportOverCave = 0;
                    for (int x = caveMinX; x <= caveMaxX; x++) {
                        boolean node = false;
                        for (int y = CAVE_SURFACE_Y - 4; y <= CAVE_SURFACE_Y + 1; y++) {
                            if (CreateTrackUtil.isTrackBlock(level.getBlockState(new BlockPos(x, y, CAVE_ANCHOR_Z)))) {
                                node = true;
                            }
                        }
                        if (node) {
                            trackNodes++;
                        }
                        if (x > caveMinX && x < caveMaxX && hasSupportUnderRail(level, x, CAVE_ANCHOR_Z)) {
                            supportOverCave++;
                        }
                    }
                    RailwaysUntold.LOGGER.info("[GAMETEST/cave] span={} trackNodes={} supportOverCave={}",
                            CAVE_SPAN, trackNodes, supportOverCave);

                    int need = CAVE_SPAN / 2;
                    helper.assertTrue(trackNodes >= 1,
                            "elevated tunnel laid no track across the cave: " + trackNodes + " nodes");
                    // The fix: the elevated-tunnel run now routes through SupportPlacementService, so the
                    // open void below the rail gets bridge decking. Without it this is 0 (bare track).
                    helper.assertTrue(supportOverCave >= need,
                            "no support placed over the cave (track suspended): " + supportOverCave
                                    + " of " + (CAVE_SPAN - 1) + " columns near the rail");
                })
                .thenSucceed();
    }

    // Drift-drive: drives a head EAST through the z-infinite drift-band hump, where the real ground
    // rises DRIFT_RISE blocks above the flat baseline but the planner's terrain sampler reports flat
    // (the production planner-drift condition - getBaseHeight under-reports the real ground). Asserts
    // the BEZIER->TUNNEL buried-in-rock fallback as a gate (it was previously only observed): placement
    // holds the planned flat Y and tunnels through the hump rather than climbing it, so the connected
    // run crosses the hump at the anchor's Y. Driven at DRIFT_DRIVE_Z, far from
    // driftBandHidesRealRiseFromPlanner's z=0 column, so the two never touch the same blocks. Uses the
    // same deterministic placement driver (seedRouteFollowingHead + avoidance/branching/structure
    // targeting off) as expansionPlacesConnectedRun.
    private static final int DRIFT_DRIVE_Z = 1024;

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void driftDriveTunnelsThroughHiddenHump(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction dir = Direction.EAST;

        boolean prevAvoidance = Config.ALL_AVOIDANCE.get();
        boolean prevBranches = Config.BRANCHES_ENABLED.get();
        boolean prevStructureTargeting = Config.STRUCTURE_TARGETING.get();
        boolean prevParallelMerge = Config.PARALLEL_MERGE_ENABLED.get();
        Config.ALL_AVOIDANCE.set(false);
        Config.BRANCHES_ENABLED.set(false);
        Config.STRUCTURE_TARGETING.set(false);
        Config.PARALLEL_MERGE_ENABLED.set(false);

        final int humpCenterX = BandedTerrainAtlas.DRIFT_CENTER_X;
        final int humpHalf = BandedTerrainAtlas.DRIFT_HALF_WIDTH;
        final int anchorX = humpCenterX - humpHalf - 56;     // flat baseline, just west of the hump
        final int targetX = humpCenterX + humpHalf + 128;    // east of the hump
        // Anchor at the Y the planner's sampler reports (flat baseline) - the same Y the route plans.
        int plannerY = NoiseTerrainSampler.forLevel(level).getBaseHeight(anchorX, DRIFT_DRIVE_Z);
        BlockPos anchor = new BlockPos(anchorX, plannerY, DRIFT_DRIVE_Z);

        // Force-load the corridor (anchor -> past the hump) to FULL so placement runs over generated
        // chunks and the real hump blocks exist to be tunnelled through.
        for (int cx = (anchorX >> 4) - 2; cx <= (targetX >> 4) + 2; cx++) {
            for (int cz = (DRIFT_DRIVE_Z >> 4) - 3; cz <= (DRIFT_DRIVE_Z >> 4) + 3; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, orchestrator);

        final TrackExpansionHead[] head = new TrackExpansionHead[1];
        // Drive until the head has cleared the east edge of the hump (plus margin) from the anchor.
        final int driveDistance = (humpCenterX + humpHalf) - anchorX + 40;

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    // Confirm the precondition this test depends on: the planner sees flat baseline
                    // here while the real ground rises into a solid hump above it.
                    int humpTopY = BandedTerrainAtlas.FLAT_TOP_Y + BandedTerrainAtlas.DRIFT_RISE;
                    helper.assertTrue(level.getBlockState(new BlockPos(humpCenterX, humpTopY, DRIFT_DRIVE_Z)).is(Blocks.STONE),
                            "drift hump top at y=" + humpTopY + " is not stone (precondition)");
                    helper.assertTrue(plannerY < humpTopY,
                            "planner Y " + plannerY + " should be below the hump top " + humpTopY);
                    StartingTrainPlacer.placeInitialTrack(level, anchor, dir);
                })
                .thenExecuteAfter(6, () -> {
                    TrackExpansionHead forward = seedRouteFollowingHead(
                            level, config, orchestrator, anchor, dir);
                    helper.assertTrue(forward != null, "no forward (" + dir + ") head was created");
                    head[0] = forward;
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    TrackExpansionHead h = head[0];
                    if (h == null || h.isComplete()) {
                        return;
                    }
                    if (chebyshev(h.getPosition(), anchor) >= driveDistance) {
                        return;
                    }
                    if (h.getTerrainPlanState().isRouteRebuilding()) {
                        return;
                    }
                    orchestrator.stepHead(h);
                })
                .thenExecute(() -> {
                    Config.ALL_AVOIDANCE.set(prevAvoidance);
                    Config.BRANCHES_ENABLED.set(prevBranches);
                    Config.STRUCTURE_TARGETING.set(prevStructureTargeting);
                    Config.PARALLEL_MERGE_ENABLED.set(prevParallelMerge);

                    BlockPos finalPos = head[0].getPosition();
                    BlockPos mid = new BlockPos((anchor.getX() + finalPos.getX()) / 2, anchor.getY(), DRIFT_DRIVE_Z);
                    int half = chebyshev(anchor, finalPos) / 2 + 24;
                    Set<BlockPos> track = scanTrack(level, mid, half);
                    Set<BlockPos> run = largestComponent(track);

                    // Bounds and peak Y of the connected run within the hump's X-range.
                    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxYoverHump = Integer.MIN_VALUE;
                    for (BlockPos p : run) {
                        minX = Math.min(minX, p.getX());
                        maxX = Math.max(maxX, p.getX());
                        if (Math.abs(p.getX() - humpCenterX) <= humpHalf) {
                            maxYoverHump = Math.max(maxYoverHump, p.getY());
                        }
                    }
                    RailwaysUntold.LOGGER.info(
                            "[GAMETEST/drift-drive] anchor={} head={} run={} blocks x=[{},{}] plannedY={} maxYoverHump={} humpTop={}",
                            anchor, finalPos, run.size(), minX, maxX, anchor.getY(), maxYoverHump,
                            BandedTerrainAtlas.FLAT_TOP_Y + BandedTerrainAtlas.DRIFT_RISE);

                    helper.assertTrue(run.size() >= RUN_LENGTH,
                            "drift-drive run too short: " + run.size() + " connected blocks");

                    // The run must actually CROSS the hump (track on both sides of its centre).
                    helper.assertTrue(minX <= humpCenterX - humpHalf && maxX >= humpCenterX + humpHalf,
                            "run did not cross the hump: x=[" + minX + "," + maxX + "], hump=["
                                    + (humpCenterX - humpHalf) + "," + (humpCenterX + humpHalf) + "]");

                    // BEZIER->TUNNEL: across the hump the track holds the planned flat Y instead of
                    // climbing the +DRIFT_RISE rock - it tunnels through. Allow a couple of blocks of
                    // bezier slack, but well below the hump top.
                    helper.assertTrue(maxYoverHump <= anchor.getY() + 2,
                            "placement climbed the hidden hump instead of tunnelling: maxY over hump "
                                    + maxYoverHump + " vs planned " + anchor.getY());
                })
                .thenSucceed();
    }

    // Drives a head EAST through the VISIBLE peak ridge (BandedTerrainAtlas PEAK band: a 40-high
    // cosine bump over a 60 half-width, slope ~0.67 >> the 0.25 limit, so the planner marks it a
    // tunnel span). Unlike driftDriveTunnelsThroughHiddenHump (BEZIER->TUNNEL rock fallback) and the
    // planner-only plannerTunnelsThroughPeak (no placement), this both PLANS a TUNNEL/ELEVATED_TUNNEL
    // segment AND executes it by driving the head across - exercising TunnelPlacementExecutor end to
    // end. Driven on its own z lane, clear of the planner-band tests' columns.
    private static final int TUNNEL_DRIVE_Z = 2048;

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void drivePlacesTunnelThroughVisiblePeak(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction dir = Direction.EAST;

        boolean prevAvoidance = Config.ALL_AVOIDANCE.get();
        boolean prevBranches = Config.BRANCHES_ENABLED.get();
        boolean prevStructureTargeting = Config.STRUCTURE_TARGETING.get();
        boolean prevParallelMerge = Config.PARALLEL_MERGE_ENABLED.get();
        Config.ALL_AVOIDANCE.set(false);
        Config.BRANCHES_ENABLED.set(false);
        Config.STRUCTURE_TARGETING.set(false);
        Config.PARALLEL_MERGE_ENABLED.set(false);

        final int peakCenterX = BandedTerrainAtlas.PEAK_CENTER_X;
        final int peakHalf = BandedTerrainAtlas.PEAK_HALF_WIDTH;
        final int z = TUNNEL_DRIVE_Z;
        final int anchorX = peakCenterX - peakHalf - 64;   // flat baseline west of the peak
        final int targetX = peakCenterX + peakHalf + 64;   // flat baseline east of the peak
        final int plannerY = NoiseTerrainSampler.forLevel(level).getBaseHeight(anchorX, z);
        final BlockPos anchor = new BlockPos(anchorX, plannerY, z);

        for (int cx = (anchorX >> 4) - 2; cx <= (targetX >> 4) + 2; cx++) {
            for (int cz = (z >> 4) - 3; cz <= (z >> 4) + 3; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, orchestrator);

        final TrackExpansionHead[] head = new TrackExpansionHead[1];
        final boolean[] plannedTunnel = new boolean[1];
        // Drive until the head has cleared the east edge of the peak (plus margin) from the anchor.
        final int driveDistance = (peakCenterX + peakHalf) - anchorX + 40;

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    // Precondition: the planner SEES the peak (the drift band, by contrast, hides it).
                    int peakTopY = NoiseTerrainSampler.forLevel(level).getBaseHeight(peakCenterX, z);
                    helper.assertTrue(peakTopY >= plannerY + BandedTerrainAtlas.PEAK_RISE - 4,
                            "planner should see the peak rise; peakTopY=" + peakTopY + " baseY=" + plannerY);
                    StartingTrainPlacer.placeInitialTrack(level, anchor, dir);
                })
                .thenExecuteAfter(6, () -> {
                    BlockPos target = new BlockPos(targetX, plannerY, z);
                    TrackExpansionHead forward = seedRouteFollowingHeadToTarget(
                            level, config, orchestrator, anchor, dir, target);
                    helper.assertTrue(forward != null, "no forward (" + dir + ") head was created");
                    head[0] = forward;
                })
                // Let the off-thread plan install, then confirm it tunnels the peak BEFORE driving
                // (the route may be consumed/replanned once execution starts).
                .thenIdle(ROUTE_INSTALL_TICKS)
                .thenExecute(() -> {
                    List<PathSegment.Type> segs = installedSegmentTypes(helper, head[0], "tunnel-drive");
                    plannedTunnel[0] = segs.contains(PathSegment.Type.TUNNEL)
                            || segs.contains(PathSegment.Type.ELEVATED_TUNNEL);
                    helper.assertTrue(plannedTunnel[0],
                            "planner did not tunnel the visible peak; segments: " + segs);
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    TrackExpansionHead h = head[0];
                    if (h == null || h.isComplete()) {
                        return;
                    }
                    if (chebyshev(h.getPosition(), anchor) >= driveDistance) {
                        return;
                    }
                    if (h.getTerrainPlanState().isRouteRebuilding()) {
                        return;
                    }
                    orchestrator.stepHead(h);
                })
                .thenExecute(() -> {
                    Config.ALL_AVOIDANCE.set(prevAvoidance);
                    Config.BRANCHES_ENABLED.set(prevBranches);
                    Config.STRUCTURE_TARGETING.set(prevStructureTargeting);
                    Config.PARALLEL_MERGE_ENABLED.set(prevParallelMerge);

                    // The head executed its installed route - including the TUNNEL segment over the peak -
                    // so TunnelPlacementExecutor ran. Assert it placed track and advanced past the peak.
                    BlockPos finalPos = head[0].getPosition();
                    int forwardAdvance = (finalPos.getX() - anchor.getX()) * dir.getStepX();
                    RailwaysUntold.LOGGER.info("[GAMETEST/tunnel-drive] anchor={} head={} advance={}",
                            anchor, finalPos, forwardAdvance);
                    helper.assertTrue(head[0].hasPlacedAnySegments(), "tunnel-drive head placed no segments");
                    helper.assertTrue(forwardAdvance >= peakHalf,
                            "head did not cross the peak: advance " + forwardAdvance + " (need " + peakHalf + ")");
                })
                .thenSucceed();
    }

    // Drives a head EAST across the VISIBLE river band (BandedTerrainAtlas RIVER: a water notch the
    // planner bridges, per plannerBridgesOverRiver). Confirms the installed route emits a BRIDGE
    // segment, then drives the head over it - executing the bridge end to end and exercising
    // BridgePlacementExecutor and the bridge SupportPlacementService that plannerBridgesOverRiver
    // (planner-only) never reaches. Own z lane, clear of the other drive tests.
    private static final int BRIDGE_DRIVE_Z = 3072;

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void drivePlacesBridgeOverRiver(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction dir = Direction.EAST;

        boolean prevAvoidance = Config.ALL_AVOIDANCE.get();
        boolean prevBranches = Config.BRANCHES_ENABLED.get();
        boolean prevStructureTargeting = Config.STRUCTURE_TARGETING.get();
        boolean prevParallelMerge = Config.PARALLEL_MERGE_ENABLED.get();
        Config.ALL_AVOIDANCE.set(false);
        Config.BRANCHES_ENABLED.set(false);
        Config.STRUCTURE_TARGETING.set(false);
        Config.PARALLEL_MERGE_ENABLED.set(false);

        final int riverCenterX = BandedTerrainAtlas.RIVER_CENTER_X;
        final int z = BRIDGE_DRIVE_Z;
        final int anchorX = riverCenterX - 160;   // flat baseline well west of the river feature
        final int targetX = riverCenterX + 160;   // flat baseline well east of it
        final int plannerY = NoiseTerrainSampler.forLevel(level).getBaseHeight(anchorX, z);
        final BlockPos anchor = new BlockPos(anchorX, plannerY, z);

        for (int cx = (anchorX >> 4) - 2; cx <= (targetX >> 4) + 2; cx++) {
            for (int cz = (z >> 4) - 3; cz <= (z >> 4) + 3; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, orchestrator);

        final TrackExpansionHead[] head = new TrackExpansionHead[1];
        final int driveDistance = (riverCenterX + 96) - anchorX + 40;

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    StartingTrainPlacer.placeInitialTrack(level, anchor, dir);
                })
                .thenExecuteAfter(6, () -> {
                    BlockPos target = new BlockPos(targetX, plannerY, z);
                    TrackExpansionHead forward = seedRouteFollowingHeadToTarget(
                            level, config, orchestrator, anchor, dir, target);
                    helper.assertTrue(forward != null, "no forward (" + dir + ") head was created");
                    head[0] = forward;
                })
                .thenIdle(ROUTE_INSTALL_TICKS)
                .thenExecute(() -> {
                    List<PathSegment.Type> segs = installedSegmentTypes(helper, head[0], "bridge-drive");
                    helper.assertTrue(segs.contains(PathSegment.Type.BRIDGE),
                            "planner did not bridge the river; segments: " + segs);
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    TrackExpansionHead h = head[0];
                    if (h == null || h.isComplete()) {
                        return;
                    }
                    if (chebyshev(h.getPosition(), anchor) >= driveDistance) {
                        return;
                    }
                    if (h.getTerrainPlanState().isRouteRebuilding()) {
                        return;
                    }
                    orchestrator.stepHead(h);
                })
                .thenExecute(() -> {
                    Config.ALL_AVOIDANCE.set(prevAvoidance);
                    Config.BRANCHES_ENABLED.set(prevBranches);
                    Config.STRUCTURE_TARGETING.set(prevStructureTargeting);
                    Config.PARALLEL_MERGE_ENABLED.set(prevParallelMerge);

                    BlockPos finalPos = head[0].getPosition();
                    int forwardAdvance = (finalPos.getX() - anchor.getX()) * dir.getStepX();
                    RailwaysUntold.LOGGER.info("[GAMETEST/bridge-drive] anchor={} head={} advance={}",
                            anchor, finalPos, forwardAdvance);
                    helper.assertTrue(head[0].hasPlacedAnySegments(), "bridge-drive head placed no segments");
                    helper.assertTrue(forwardAdvance >= 160,
                            "head did not cross the river: advance " + forwardAdvance + " (need 160)");
                })
                .thenSucceed();
    }

    // Route-vs-route crossing: two heads plan perpendicular routes that cross on the flat baseline.
    // The per-level CoarseRouteRegistry detects the crossing at registration time (not at placement)
    // and resolves it by moving the second-registering route vertically clear of the first - the
    // higher-UUID head crosses OVER, the lower UNDER, separated by vertSep = verticalExpansion + 1
    // (RouteConflictResolver). Asserts the installed coarse routes are vertically separated at the
    // crossing, one waypoint is marked CROSSING_OVER/UNDER, and the higher-UUID head ends up higher.
    // This exercises the real-planner -> registry -> RouteConflictResolver integration the JUnit
    // ConflictRampGoldenTest cannot (it hand-builds conflicts). No driving: resolution is route-level.
    private static final int CROSS_CENTER_X = -50000;   // flat baseline, clear of atlas bands + arenas
    private static final int CROSS_CENTER_Z = -50000;

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void crossingRoutesResolveVertically(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        NoiseTerrainSampler sampler = NoiseTerrainSampler.forLevel(level);

        // Avoidance/structure-targeting off so the two routes run straight and actually cross (a
        // detour or village seek would miss the crossing); restored before asserting.
        boolean prevAvoidance = Config.ALL_AVOIDANCE.get();
        boolean prevStructureTargeting = Config.STRUCTURE_TARGETING.get();
        boolean prevParallelMerge = Config.PARALLEL_MERGE_ENABLED.get();
        Config.ALL_AVOIDANCE.set(false);
        Config.STRUCTURE_TARGETING.set(false);
        Config.PARALLEL_MERGE_ENABLED.set(false);

        final int flatY = sampler.getBaseHeight(CROSS_CENTER_X, CROSS_CENTER_Z);
        final BlockPos crossing = new BlockPos(CROSS_CENTER_X, flatY, CROSS_CENTER_Z);

        // Head A runs EAST through the crossing; head B runs SOUTH through it.
        BlockPos startA = new BlockPos(CROSS_CENTER_X - 120, flatY, CROSS_CENTER_Z);
        BlockPos targetA = new BlockPos(CROSS_CENTER_X + 120, flatY, CROSS_CENTER_Z);
        BlockPos startB = new BlockPos(CROSS_CENTER_X, flatY, CROSS_CENTER_Z - 120);
        BlockPos targetB = new BlockPos(CROSS_CENTER_X, flatY, CROSS_CENTER_Z + 120);

        TrackExpansionHead headA = planDirectedHead(level, config, startA, Direction.EAST, targetA);
        TrackExpansionHead headB = planDirectedHead(level, config, startB, Direction.SOUTH, targetB);
        helper.assertTrue(headA != null && headB != null, "failed to create both crossing heads");

        helper.startSequence()
                // Both async plans complete, register in the shared per-level registry, and the
                // second registration runs crossing detection + resolution.
                .thenIdle(120)
                .thenExecute(() -> {
                    Config.ALL_AVOIDANCE.set(prevAvoidance);
                    Config.STRUCTURE_TARGETING.set(prevStructureTargeting);
                    Config.PARALLEL_MERGE_ENABLED.set(prevParallelMerge);

                    CoarseRoute routeA = headA.getTerrainPlanState().getCoarseRoute();
                    CoarseRoute routeB = headB.getTerrainPlanState().getCoarseRoute();
                    helper.assertTrue(routeA != null && routeB != null,
                            "a crossing route was not installed (A=" + routeA + " B=" + routeB + ")");

                    CoarseRoute.CoarseWaypoint nearA = routeA.findNearestWaypoint(crossing);
                    CoarseRoute.CoarseWaypoint nearB = routeB.findNearestWaypoint(crossing);
                    helper.assertTrue(nearA != null && nearB != null,
                            "a route has no waypoint near the crossing");

                    int yA = nearA.advisedTrackY();
                    int yB = nearB.advisedTrackY();
                    int sep = Math.abs(yA - yB);
                    int vertSep = RailwaysUntoldConfig.getVerticalExpansion() + 1;
                    boolean marked = nearA.type() == CoarseRoute.WaypointType.CROSSING_OVER
                            || nearA.type() == CoarseRoute.WaypointType.CROSSING_UNDER
                            || nearB.type() == CoarseRoute.WaypointType.CROSSING_OVER
                            || nearB.type() == CoarseRoute.WaypointType.CROSSING_UNDER;

                    RailwaysUntold.LOGGER.info(
                            "[GAMETEST/crossing] A(id={} y={} type={}) B(id={} y={} type={}) sep={} vertSep={}",
                            headA.getHeadId(), yA, nearA.type(), headB.getHeadId(), yB, nearB.type(), sep, vertSep);

                    helper.assertTrue(sep >= vertSep,
                            "crossing routes not vertically separated: sep=" + sep + " < vertSep=" + vertSep);
                    helper.assertTrue(marked,
                            "neither route's crossing waypoint is marked CROSSING_OVER/UNDER");

                    // Deterministic split invariant (RC4): the higher-UUID head crosses OVER, so it
                    // ends up at the higher Y regardless of which route registered (and resolved) second.
                    boolean aHigherUuid = headA.getHeadId().compareTo(headB.getHeadId()) > 0;
                    helper.assertTrue(aHigherUuid == (yA > yB),
                            "higher-UUID head should cross OVER: aHigherUuid=" + aHigherUuid
                                    + " yA=" + yA + " yB=" + yB);
                })
                .thenSucceed();
    }

    /**
     * SEQUENCED crossing: head A lays an east-west line L and completes; THEN head B is seeded south of L's
     * midpoint and driven NORTH across it. The placed-track resolver (PlacedTrackConflictDetector, run at
     * registration) must grade-separate B's route OVER/UNDER L - it previously failed to, because the
     * spacing filter's `crossIdx - Integer.MIN_VALUE` overflowed and skipped a route's first crossing.
     * Asserts B's coarse-route crossing waypoint is CROSSING_OVER/UNDER with a separated Y. (The precision
     * compiler not yet honoring that Y at placement is a separate documented follow-up - logged, not asserted.)
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 800, batch = "sequencedCrossing")
    public static void sequencedHeadCrossesAPlacedPerpendicularLine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction lineDir = Direction.EAST;
        Direction crossDir = Direction.NORTH; // head system keys off the positive axis - cross head heads NORTH

        boolean prevAvoidance = Config.ALL_AVOIDANCE.get();
        boolean prevBranches = Config.BRANCHES_ENABLED.get();
        boolean prevStructureTargeting = Config.STRUCTURE_TARGETING.get();
        Config.ALL_AVOIDANCE.set(false);
        Config.BRANCHES_ENABLED.set(false);
        Config.STRUCTURE_TARGETING.set(false);

        final int ANCHOR_X = 13_000_000;
        final int ANCHOR_Z = 13_000_000;
        final int LINE_RUN = CONVERGE_LINE_RUN;
        final int CROSS_APPROACH = 56;
        for (int cx = (ANCHOR_X - 48) >> 4; cx <= (ANCHOR_X + LINE_RUN + 48) >> 4; cx++) {
            for (int cz = (ANCHOR_Z - CROSS_APPROACH - 48) >> 4; cz <= (ANCHOR_Z + CROSS_APPROACH + 48) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, lineDir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, orchestrator);

        final TrackExpansionHead[] lineHead = new TrackExpansionHead[1];
        final TrackExpansionHead[] crossHead = new TrackExpansionHead[1];
        final BlockPos[] anchorL = new BlockPos[1];
        final BlockPos[] startB = new BlockPos[1];
        final CoarseRoute.WaypointType[] crossType = new CoarseRoute.WaypointType[1];
        final int[] crossY = { Integer.MIN_VALUE };
        final int[] precYatCross = { Integer.MIN_VALUE }; // B's precision-route Y where it crosses the line

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    int plannerY = NoiseTerrainSampler.forLevel(level).getBaseHeight(ANCHOR_X, ANCHOR_Z);
                    anchorL[0] = new BlockPos(ANCHOR_X, plannerY, ANCHOR_Z);
                    StartingTrainPlacer.placeInitialTrack(level, anchorL[0], lineDir);
                })
                .thenExecuteAfter(6, () -> {
                    lineHead[0] = seedRouteFollowingHead(level, config, orchestrator, anchorL[0], lineDir);
                    helper.assertTrue(lineHead[0] != null, "line head A was not created");
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    TrackExpansionHead h = lineHead[0];
                    if (h == null || h.isComplete()) return;
                    if (chebyshev(h.getPosition(), anchorL[0]) >= LINE_RUN) return;
                    if (h.getTerrainPlanState().isRouteRebuilding()) return;
                    orchestrator.stepHead(h);
                })
                .thenExecute(() -> {
                    lineHead[0].markComplete();
                    orchestrator.getHeadManager().invalidateActiveHeadsCache();
                    int crossX = anchorL[0].getX() + LINE_RUN / 2;
                    startB[0] = new BlockPos(crossX, anchorL[0].getY(), anchorL[0].getZ() + CROSS_APPROACH);
                    StartingTrainPlacer.placeInitialTrack(level, startB[0], crossDir);
                })
                .thenExecuteAfter(6, () -> {
                    BlockPos targetB = new BlockPos(startB[0].getX(), anchorL[0].getY(),
                            anchorL[0].getZ() - CROSS_APPROACH);
                    TrackExpansionHead b = seedRouteFollowingHeadToTarget(
                            level, config, orchestrator, startB[0], crossDir, targetB);
                    helper.assertTrue(b != null, "cross head B was not created");
                    crossHead[0] = b;
                })
                .thenExecuteAfter(90, () -> {
                    // After the async route install (registerRoute ran PlacedTrackConflictDetector), capture
                    // B's coarse-route waypoint nearest the crossing - its type/Y shows whether the resolver
                    // grade-separated B's route over/under the placed line L.
                    CoarseRoute r = crossHead[0].getTerrainPlanState().getCoarseRoute();
                    if (r != null) {
                        CoarseRoute.CoarseWaypoint near = r.findNearestWaypoint(
                                new BlockPos(startB[0].getX(), anchorL[0].getY(), anchorL[0].getZ()));
                        if (near != null) {
                            crossType[0] = near.type();
                            crossY[0] = near.advisedTrackY();
                        }
                    }
                    RailwaysUntold.LOGGER.info("[GAMETEST/seq-crossing] B route crossing-wp: type={} advisedY={} (lineY={})",
                            crossType[0], crossY[0], anchorL[0].getY());
                    PrecisionRoute pr = crossHead[0].getTerrainPlanState().getPrecisionRoute();
                    if (pr != null && pr.getPrecisionPath() != null) {
                        int lz = anchorL[0].getZ();
                        for (PathSegment s : pr.getPrecisionPath().segments) {
                            int z0 = s.getStart().getZ();
                            int z1 = s.getEndPosition().getZ();
                            if (Math.min(z0, z1) <= lz && Math.max(z0, z1) >= lz) {
                                // The segment crossing the line - its higher endpoint is B clearing over L.
                                precYatCross[0] = Math.max(precYatCross[0],
                                        Math.max(s.getStart().getY(), s.getEndPosition().getY()));
                                RailwaysUntold.LOGGER.info("[GAMETEST/seq-crossing] B PRECISION seg at crossing: {} {} -> {}",
                                        s.type, s.getStart().toShortString(), s.getEndPosition().toShortString());
                            }
                        }
                    }
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    TrackExpansionHead h = crossHead[0];
                    if (h == null || h.isComplete()) return;
                    if (h.getTerrainPlanState().isRouteRebuilding()) return;
                    orchestrator.stepHead(h);
                })
                .thenExecute(() -> {
                    Config.ALL_AVOIDANCE.set(prevAvoidance);
                    Config.BRANCHES_ENABLED.set(prevBranches);
                    Config.STRUCTURE_TARGETING.set(prevStructureTargeting);

                    int lineZ = anchorL[0].getZ();
                    int crossX = startB[0].getX();
                    int lineY = anchorL[0].getY();
                    BlockPos mid = new BlockPos(crossX, lineY, lineZ);
                    Set<BlockPos> track = scanTrackInclFiller(level, mid, CROSS_APPROACH + 24, 24);
                    // B runs along x=crossX; L runs along z=lineZ. Measure B's COLUMN directly (not the
                    // largest component, which is L's longer line): track south of L (z < lineZ) in B's
                    // column is B's far side, and the highest track at the crossing column is B going OVER L.
                    boolean reachedFarSide = track.stream()
                            .anyMatch(p -> Math.abs(p.getX() - crossX) <= 2 && p.getZ() <= lineZ - 6);
                    int farMostZ = track.stream().filter(p -> Math.abs(p.getX() - crossX) <= 2)
                            .mapToInt(BlockPos::getZ).min().orElse(lineZ);
                    int bYatCross = track.stream().filter(p -> Math.abs(p.getZ() - lineZ) <= 1
                                    && Math.abs(p.getX() - crossX) <= 2)
                            .mapToInt(BlockPos::getY).max().orElse(lineY);
                    RailwaysUntold.LOGGER.info(
                            "[GAMETEST/seq-crossing] resolver crossType={} crossY={} | precision precYatCross={} | "
                                    + "placement reachedFarSide={} farMostZ={} bYatCrossing={} (lineY={})",
                            crossType[0], crossY[0], precYatCross[0], reachedFarSide, farMostZ, bYatCross, lineY);
                    crossHead[0].markComplete();
                    orchestrator.getHeadManager().invalidateActiveHeadsCache();

                    // The placed-track resolver (PlacedTrackConflictDetector) must grade-separate a head whose
                    // route crosses an already-PLACED perpendicular line, AND the precision compiler must honor
                    // it (compile a route that climbs OVER the line). Before the int-overflow fix the resolver
                    // skipped a route's first crossing, so this came back at-grade. Assert both:
                    helper.assertTrue(
                            crossType[0] == CoarseRoute.WaypointType.CROSSING_OVER
                                    || crossType[0] == CoarseRoute.WaypointType.CROSSING_UNDER,
                            "sequenced crossing not grade-separated: B's coarse crossing waypoint is " + crossType[0]
                                    + " (expected CROSSING_OVER/UNDER) - the placed-track resolver did not fire");
                    helper.assertTrue(precYatCross[0] > lineY,
                            "precision route did not climb over the line: B's precision Y at the crossing was "
                                    + precYatCross[0] + " (line at Y=" + lineY + ")");
                    // The driving/placement layer must actually lay the climb-over track: B must reach the
                    // far side of L and the track in B's crossing column must sit ABOVE the line, not dead-end
                    // at-grade. This previously failed because the precision compiler dropped the lone
                    // CROSSING_OVER apex waypoint (a single-waypoint run emitted no segment), so the placed
                    // route passed through L at the climb run's Y, only ~5 blocks clear - within the runtime
                    // collision check's vertical clearance - and the over-bezier was rejected to a junction.
                    helper.assertTrue(reachedFarSide,
                            "head did not cross to the far side of the line: farMostZ=" + farMostZ
                                    + " (lineZ=" + lineZ + ") - placed track dead-ends at the line");
                    helper.assertTrue(bYatCross > lineY,
                            "placed track at the crossing is not above the line: bYatCrossing=" + bYatCross
                                    + " (lineY=" + lineY + ") - the head laid at-grade track instead of crossing over");
                })
                .thenSucceed();
    }

    /**
     * Wedge recovery: a head whose route runs into a foreign line it didn't avoid (avoidance off, matching
     * the in-game {@code tracks 0} plan) and cannot extend through must RETIRE/junction promptly, not
     * livelock. Head A lays an east line L and completes; head B is seeded west of L heading EAST straight
     * into it (collinear), with a target east past L, so every placement past L's west end is blocked by
     * existing track. Asserts B completes (retires or junction-terminates) within the drive rather than
     * wedging forever (the head-11 failure: at 8480,1752 it placed nothing and never retired).
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 1600, batch = "wedgeRecovery")
    public static void wedgedHeadAgainstForeignTrackRetires(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction dir = Direction.EAST;

        boolean prevAvoidance = Config.ALL_AVOIDANCE.get();
        boolean prevBranches = Config.BRANCHES_ENABLED.get();
        boolean prevStructureTargeting = Config.STRUCTURE_TARGETING.get();
        boolean prevParallelMerge = Config.PARALLEL_MERGE_ENABLED.get();
        Config.ALL_AVOIDANCE.set(false);
        Config.BRANCHES_ENABLED.set(false);
        Config.STRUCTURE_TARGETING.set(false);
        Config.PARALLEL_MERGE_ENABLED.set(false);

        final int ANCHOR_X = 15_000_000;
        final int ANCHOR_Z = 15_000_000;
        final int LINE_RUN = CONVERGE_LINE_RUN;
        for (int cx = (ANCHOR_X - 80) >> 4; cx <= (ANCHOR_X + LINE_RUN + 112) >> 4; cx++) {
            for (int cz = (ANCHOR_Z - 48) >> 4; cz <= (ANCHOR_Z + 48) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, orchestrator);

        final TrackExpansionHead[] lineHead = new TrackExpansionHead[1];
        final TrackExpansionHead[] wedge = new TrackExpansionHead[1];
        final BlockPos[] anchorL = new BlockPos[1];
        final BlockPos[] startB = new BlockPos[1];
        final boolean[] bComplete = { false };

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    int y = NoiseTerrainSampler.forLevel(level).getBaseHeight(ANCHOR_X, ANCHOR_Z);
                    anchorL[0] = new BlockPos(ANCHOR_X, y, ANCHOR_Z);
                    StartingTrainPlacer.placeInitialTrack(level, anchorL[0], dir);
                })
                .thenExecuteAfter(6, () -> {
                    lineHead[0] = seedRouteFollowingHead(level, config, orchestrator, anchorL[0], dir);
                    helper.assertTrue(lineHead[0] != null, "line head A was not created");
                })
                .thenExecuteFor(DRIVE_TICKS, () -> {
                    TrackExpansionHead h = lineHead[0];
                    if (h == null || h.isComplete()) return;
                    if (chebyshev(h.getPosition(), anchorL[0]) >= LINE_RUN) return;
                    if (h.getTerrainPlanState().isRouteRebuilding()) return;
                    orchestrator.stepHead(h);
                })
                .thenExecute(() -> {
                    lineHead[0].markComplete();
                    orchestrator.getHeadManager().invalidateActiveHeadsCache();
                    startB[0] = new BlockPos(anchorL[0].getX() - 30, anchorL[0].getY(), anchorL[0].getZ());
                    StartingTrainPlacer.placeInitialTrack(level, startB[0], dir);
                })
                .thenExecuteAfter(6, () -> {
                    BlockPos targetB = new BlockPos(anchorL[0].getX() + LINE_RUN + 80, anchorL[0].getY(), anchorL[0].getZ());
                    wedge[0] = seedRouteFollowingHeadToTarget(level, config, orchestrator, startB[0], dir, targetB);
                    helper.assertTrue(wedge[0] != null, "wedge head B was not created");
                })
                .thenExecuteFor(DRIVE_TICKS * 3, () -> {
                    TrackExpansionHead h = wedge[0];
                    if (h == null || h.isComplete()) return;
                    if (h.getTerrainPlanState().isRouteRebuilding()) return;
                    orchestrator.stepHead(h);
                })
                .thenExecute(() -> {
                    Config.ALL_AVOIDANCE.set(prevAvoidance);
                    Config.BRANCHES_ENABLED.set(prevBranches);
                    Config.STRUCTURE_TARGETING.set(prevStructureTargeting);
                    Config.PARALLEL_MERGE_ENABLED.set(prevParallelMerge);

                    bComplete[0] = wedge[0].isComplete();
                    RailwaysUntold.LOGGER.info("[GAMETEST/wedge] B complete={} pos={} (start={} lineWestEnd={})",
                            bComplete[0], wedge[0].getPosition().toShortString(),
                            startB[0].toShortString(), anchorL[0].toShortString());
                    wedge[0].markComplete();
                    orchestrator.getHeadManager().invalidateActiveHeadsCache();

                    helper.assertTrue(bComplete[0],
                            "wedge head did not retire/junction against the foreign collinear track within "
                                    + (DRIVE_TICKS * 3) + " ticks - it livelocks (places nothing, never completes)");
                })
                .thenSucceed();
    }

    // Creates a single head at `start` expanding in the positive cardinal `dir` (EAST or SOUTH),
    // retires the opposite-axis head, and attaches a coarse route to `target`. The head system keys
    // off the track AXIS (NORTH/EAST representative - getPositiveDirection rejects SOUTH/WEST), so the
    // orchestrator and head init take the axis while the head whose expansion direction is `dir` is
    // kept. Each head uses its own orchestrator, but createAndAttach registers the route in the
    // per-level CoarseRouteRegistry shared by all heads, so two such routes can detect and resolve a
    // crossing against each other.
    private static TrackExpansionHead planDirectedHead(
            ServerLevel level, RailwaysUntoldConfig config, BlockPos start, Direction dir, BlockPos target) {
        Direction axis = dir.getAxis() == Direction.Axis.X ? Direction.EAST : Direction.NORTH;
        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, axis, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, orchestrator);
        ExpansionHeadManager headManager = orchestrator.getHeadManager();
        headManager.initializeHeadsWithCustomStart(start, start.relative(dir), axis, level, config, null);
        TrackExpansionHead head = null;
        for (TrackExpansionHead h : headManager.getActiveHeads()) {
            if (h.getDirection() == dir) {
                head = h;
            } else {
                h.markComplete();
            }
        }
        if (head == null) {
            return null;
        }
        head.getVillageState().setTarget(java.util.UUID.randomUUID(), target, head.getPosition());
        CoarseRouteFactory.createAndAttach(level, head, target);
        return head;
    }

    // Station hookup (forced-village variant): rather than wait for a naturally-generated village at
    // a random per-boot position (the long, flaky village-seeking drive), this forces the end state
    // deterministically - a head at a fixed flat site is given a synthetic locked StationPlan and a
    // real datapack station schematic (StationSchematicCache, seeded), then StationCommitter.tryCommit
    // is called directly. That exercises the commit + placement seam: the schematic blocks are placed
    // and the station's track entry is pinned to the head's position (tryCommit FAILS unless
    // placement.trackEntryPoint == headPos - the hookup invariant). Deterministic: fixed coordinate,
    // fixed seed, no drive. It does NOT cover village discovery/approach (deliberately - that path is
    // non-deterministic by design); it covers that a committed station lands and hooks up.
    private static final int STATION_SITE_X = -70000;   // flat baseline, clear of bands + other tests
    private static final int STATION_SITE_Z = -70000;
    private static final long STATION_SEED = 0x5241494C53544EL; // "RAILSTN"

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void stationCommitPlacesSchematicAndHooksTrack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        Direction dir = Direction.EAST;

        final int flatY = NoiseTerrainSampler.forLevel(level).getBaseHeight(STATION_SITE_X, STATION_SITE_Z);
        ChunkPos siteChunk = new ChunkPos(STATION_SITE_X >> 4, STATION_SITE_Z >> 4);

        // Force-load a generous region to FULL so the station footprint chunks are present (else the
        // commit defers for chunks) and the schematic places over clean, generated flat baseline.
        for (int cx = siteChunk.x - 6; cx <= siteChunk.x + 6; cx++) {
            for (int cz = siteChunk.z - 6; cz <= siteChunk.z + 6; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }

        TrackExpansionOrchestrator orchestrator = new TrackExpansionOrchestrator(
                level, config, dir, TrackPlacementSavedData.get(level), level.getSharedSpawnPos());
        TrackPlacerRegistry.register(level, orchestrator);
        ExpansionHeadManager headManager = orchestrator.getHeadManager();

        final TrackExpansionHead[] headRef = new TrackExpansionHead[1];

        helper.startSequence()
                .thenExecuteAfter(4, () -> {
                    BlockPos anchor = new BlockPos(STATION_SITE_X, flatY, STATION_SITE_Z);
                    StartingTrainPlacer.placeInitialTrack(level, anchor, dir);
                    headManager.initializeHeadsWithCustomStart(
                            anchor, anchor.relative(dir), dir, level, config, null);
                    TrackExpansionHead head = null;
                    for (TrackExpansionHead h : headManager.getActiveHeads()) {
                        if (h.getDirection() == dir) {
                            head = h;
                        } else {
                            h.markComplete();
                        }
                    }
                    helper.assertTrue(head != null, "no forward (" + dir + ") head was created");
                    headRef[0] = head;

                    // Synthesize the station target the head would otherwise reach via a village
                    // approach: a locked plan at the head, plus a real datapack station schematic.
                    BlockPos headPos = head.getPosition();
                    head.getVillageState().setLockedStationPlan(StationPlan.of(headPos, dir));
                    SelectedStation station = StationSchematicCache.selectStation(
                            level, headPos, new java.util.Random(STATION_SEED));
                    helper.assertTrue(station != null && station.validation() != null,
                            "could not select a station schematic from the datapack");
                    head.getVillageState().setSelectedStation(station);
                })
                // Give the anchor's deferred track block entity a few ticks before committing.
                .thenExecuteAfter(6, () -> {
                    TrackExpansionHead head = headRef[0];
                    BlockPos headPos = head.getPosition();
                    StationCommitter.CommitResult result = StationCommitter.tryCommit(level, head);
                    int placed = countNonAirAbove(level, headPos, 32, flatY + 1, flatY + 16);
                    RailwaysUntold.LOGGER.info(
                            "[GAMETEST/station] head={} commit={} isStationPlaced={} blocksAbove={}",
                            headPos, result.status(), head.getVillageState().isStationPlaced(), placed);

                    helper.assertTrue(result.status() == StationCommitter.CommitStatus.PLACED,
                            "station commit did not place (status=" + result.status() + ")");
                    // tryCommit only returns PLACED when placement.trackEntryPoint == headPos, so a
                    // PLACED result already proves the track hooked up at the head; the state flag and
                    // the placed structure confirm the schematic actually landed.
                    helper.assertTrue(head.getVillageState().isStationPlaced(),
                            "head state not marked station-placed after a PLACED commit");
                    helper.assertTrue(placed >= 20,
                            "station commit placed too few blocks above the surface: " + placed);
                })
                .thenSucceed();
    }

    // Counts non-air blocks in the band yBase..yTop within +-half in X/Z around `center` - used to
    // confirm a station schematic actually placed structure above the flat baseline.
    private static int countNonAirAbove(ServerLevel level, BlockPos center, int half, int yBase, int yTop) {
        int count = 0;
        BlockPos min = new BlockPos(center.getX() - half, yBase, center.getZ() - half);
        BlockPos max = new BlockPos(center.getX() + half, yTop, center.getZ() + half);
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (!level.getBlockState(p).isAir()) {
                count++;
            }
        }
        return count;
    }

    // Deterministic placement driver. Seeds the forward head from a custom start, retires the other
    // heads, and attaches a coarse route to a fixed target along `dir`. No village or exploration
    // target is set: the head is not in exploration mode, so ExplorationTargetDecider never
    // regenerates a target with its unseeded Random - the head simply follows the installed precision
    // route (CoarseRouteExecutionRule's terrain phase) along `dir`. With avoidance/branching off and a
    // flat baseline that route runs cardinally toward the target (the precision compiler still inserts
    // SCurve45 corrections, so it is not a perfectly straight line). Returns the forward head, or null
    // if none was created for `dir`.
    private static final int DRIVER_TARGET_DISTANCE = RUN_LENGTH + 32;

    private static TrackExpansionHead seedRouteFollowingHead(
            ServerLevel level, RailwaysUntoldConfig config,
            TrackExpansionOrchestrator orchestrator, BlockPos anchor, Direction dir) {
        return seedRouteFollowingHeadToTarget(level, config, orchestrator, anchor, dir,
                anchor.relative(dir, DRIVER_TARGET_DISTANCE));
    }

    // As seedRouteFollowingHead, but routes to an explicit target. An off-axis target makes the
    // planner emit a diagonal route (DiagonalStraight/DiagonalCurve segments) and its closure curves.
    private static TrackExpansionHead seedRouteFollowingHeadToTarget(
            ServerLevel level, RailwaysUntoldConfig config,
            TrackExpansionOrchestrator orchestrator, BlockPos anchor, Direction dir, BlockPos target) {
        ExpansionHeadManager headManager = orchestrator.getHeadManager();
        headManager.initializeHeadsWithCustomStart(
                anchor, anchor.relative(dir), dir, level, config, null);

        TrackExpansionHead forward = null;
        for (TrackExpansionHead h : headManager.getActiveHeads()) {
            if (h.getDirection() == dir) {
                forward = h;
            } else {
                h.markComplete();
            }
        }
        if (forward == null) {
            return null;
        }

        CoarseRouteFactory.createAndAttach(level, forward, target);
        return forward;
    }

    private static int chebyshev(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()),
                Math.max(Math.abs(a.getY() - b.getY()), Math.abs(a.getZ() - b.getZ())));
    }

    // True if a real support block (bridge decking or ballast) sits just below the rail at (x, z).
    // Excludes air, Create track/girder blocks, and create:fake_track (the bezier's own rail filler),
    // so over an open void this is only true once the elevated-tunnel support pass places decking.
    private static boolean hasSupportUnderRail(ServerLevel level, int x, int z) {
        for (int y = CAVE_SURFACE_Y - 4; y <= CAVE_SURFACE_Y - 1; y++) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (s.isAir() || CreateTrackUtil.isTrackBlock(s) || CreateTrackUtil.isGirderBlock(s)) {
                continue;
            }
            if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock())
                    .getPath().equals("fake_track")) {
                continue;
            }
            return true;
        }
        return false;
    }

    // Collects Create track block positions in a cube of the given half-extent (X/Z) around the
    // centre, with a generous vertical margin for any ramps.
    private static Set<BlockPos> scanTrack(ServerLevel level, BlockPos center, int half) {
        return scanTrack(level, center, half, 16);
    }

    private static Set<BlockPos> scanTrack(ServerLevel level, BlockPos center, int half, int halfY) {
        Set<BlockPos> found = new HashSet<>();
        BlockPos min = center.offset(-half, -halfY, -half);
        BlockPos max = center.offset(half, halfY, half);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (CreateTrackUtil.isTrackBlock(level.getBlockState(pos))) {
                found.add(pos.immutable());
            }
        }
        return found;
    }

    // As scanTrack, but also counts create:fake_track - the bezier/connector rail filler. A
    // CreateTrackConnector join (e.g. the parallel-merge curve) is made of fake_track, invisible to
    // isTrackBlock, so a connectivity check that must see the join needs to include it.
    private static Set<BlockPos> scanTrackInclFiller(ServerLevel level, BlockPos center, int half, int halfY) {
        Set<BlockPos> found = new HashSet<>();
        BlockPos min = center.offset(-half, -halfY, -half);
        BlockPos max = center.offset(half, halfY, half);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState s = level.getBlockState(pos);
            boolean filler = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock())
                    .getPath().equals("fake_track");
            if (CreateTrackUtil.isTrackBlock(s) || filler) {
                found.add(pos.immutable());
            }
        }
        return found;
    }

    // The largest connected component of `track`, using 26-neighbour (Chebyshev-1) adjacency so
    // diagonal and sloped track count as connected. This is the single longest gap-free run the
    // expansion placed.
    private static Set<BlockPos> largestComponent(Set<BlockPos> track) {
        Set<BlockPos> remaining = new HashSet<>(track);
        Set<BlockPos> best = new HashSet<>();
        while (!remaining.isEmpty()) {
            BlockPos start = remaining.iterator().next();
            Set<BlockPos> comp = floodComponent(start, track);
            remaining.removeAll(comp);
            if (comp.size() > best.size()) {
                best = comp;
            }
        }
        return best;
    }

    private static Set<BlockPos> floodComponent(BlockPos start, Set<BlockPos> track) {
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos n = pos.offset(dx, dy, dz);
                        if (track.contains(n) && seen.add(n)) {
                            queue.add(n);
                        }
                    }
                }
            }
        }
        return seen;
    }

    // The longer of the component's X / Z bounding-box extents - how far the connected run spans.
    private static int spanOf(Set<BlockPos> component) {
        if (component.isEmpty()) {
            return 0;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : component) {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        return Math.max(maxX - minX, maxZ - minZ);
    }

    // Survey engine lifecycle (P0): submit a survey over a far region, let the engine ticket-load it to
    // FULL, run a test extractor, and persist the result. Asserts the extractor saw every region chunk
    // loaded and the result landed in SurveySavedData. Ticket release is unconditional in the engine's
    // terminal paths; this verifies the load -> extract -> persist lifecycle end to end.
    private static final int SURVEY_ANCHOR = 8_000_000;

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void surveyLoadsRegionAndExtracts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos anchor = new ChunkPos(SURVEY_ANCHOR >> 4, SURVEY_ANCHOR >> 4);
        RegionKey key = RegionKey.around(anchor, 1); // 3x3 = 9 chunks

        SurveyExtractorRegistry.register(new ChunkCountExtractor());
        final SurveyResult[] got = new SurveyResult[1];
        SurveyManager.request(level, new SurveyRequest(
                key, List.of("test-chunkcount"), ChunkStatus.FULL, r -> got[0] = r));

        helper.startSequence()
                .thenExecuteFor(400, () -> { /* the survey engine's own server-tick loop drives the work */ })
                .thenExecute(() -> {
                    helper.assertTrue(got[0] != null, "survey callback never fired (load timed out?)");
                    ChunkCountData data = got[0].get("test-chunkcount", ChunkCountData.class).orElse(null);
                    helper.assertTrue(data != null, "test extractor produced no data");
                    helper.assertTrue(data.allLoaded(), "extractor ran before all region chunks were loaded");
                    helper.assertTrue(data.chunkCount() == 9,
                            "extractor saw " + data.chunkCount() + " chunks, expected 9");
                    helper.assertTrue(SurveySavedData.get(level).contains(key),
                            "survey result was not persisted to SurveySavedData");
                    SurveyExtractorRegistry.clear();
                })
                .thenSucceed();
    }

    private record ChunkCountData(int chunkCount, boolean allLoaded) implements SurveyData {
        @Override
        public CompoundTag toNbt(HolderLookup.Provider provider) {
            CompoundTag t = new CompoundTag();
            t.putInt("n", chunkCount);
            t.putBoolean("all", allLoaded);
            return t;
        }
    }

    private static final class ChunkCountExtractor implements SurveyExtractor<ChunkCountData> {
        @Override public String id() { return "test-chunkcount"; }
        @Override public ChunkStatus requiredStatus() { return ChunkStatus.FULL; }
        @Override public ChunkCountData extract(SurveyContext ctx) {
            boolean all = ctx.chunks().stream().allMatch(c ->
                    com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil.getLoadedChunk(ctx.level(), c) != null);
            return new ChunkCountData(ctx.chunks().size(), all);
        }
        @Override public ChunkCountData fromNbt(CompoundTag tag, HolderLookup.Provider provider) {
            return new ChunkCountData(tag.getInt("n"), tag.getBoolean("all"));
        }
    }
}
