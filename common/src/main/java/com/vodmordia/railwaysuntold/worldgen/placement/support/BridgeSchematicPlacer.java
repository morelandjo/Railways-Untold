package com.vodmordia.railwaysuntold.worldgen.placement.support;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.datapack.BiomeSettingsLoader;
import com.vodmordia.railwaysuntold.datapack.ResolvedBiomeSettings;
import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.spatial.RotationHelper;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor.BezierSegmentData;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader.LoadedSchematic;
import com.vodmordia.railwaysuntold.worldgen.village.StationBlockProtection;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places bridge decking and pillar schematics under track positions.
 *
 * The three NBTs and the half-width are resolved from biome settings per bridge run
 * (see {@link #init(ServerLevel, BlockPos)}). One bridge uses one consistent style
 * end-to-end, even if the track crosses biomes.
 *
 * Uses a two-part decking system:
 * - decking NBT: block at (0,0,0) is the flat decking surface, stamped across the full width
 * - end NBT: edge railing placed on both outside edges
 * - pillar NBT: stackable column placed under the bridge at ground-support intervals
 */
public class BridgeSchematicPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Direction[] HORIZONTAL = { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };

    /** Fallback half-width used if {@link #init(ServerLevel, BlockPos)} isn't called. */
    private static final double FALLBACK_DECK_HALF_WIDTH = 3.0;
    private static final ResourceLocation FALLBACK_DECKING_ID =
            new ResourceLocation("railwaysuntold", "structure/bridge_cross_section.nbt");
    private static final ResourceLocation FALLBACK_END_ID =
            new ResourceLocation("railwaysuntold", "structure/decking_end.nbt");
    /** Fallback pier piece; the live one is per-biome (see {@link #pierNbtId}). Read as a 3D pillar
     *  stamp (X=along-track depth, Y=vertical, Z=across deck) - see {@link #placePillarStamp}. */
    private static final ResourceLocation FALLBACK_PIER_ID =
            new ResourceLocation("railwaysuntold", "structure/bridge_pillar.nbt");
    /** Fallback railing pattern; the live one is per-biome (see {@link #railingNbtId}). Longitudinal,
     *  repeats every its-width; pattern length = pier spacing. */
    private static final ResourceLocation FALLBACK_RAILING_ID =
            new ResourceLocation("railwaysuntold", "structure/bridge_railing.nbt");

    /** Shared across placer instances - resource IDs are stable, so caching block state / schematic is safe. */
    private static final Map<ResourceLocation, BlockState> DECKING_BASE_BLOCK_CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, LoadedSchematic> SCHEMATIC_CACHE = new ConcurrentHashMap<>();
    /** Marker for resource IDs that have been attempted and failed to load - avoids repeated error spam. */
    private static final Set<ResourceLocation> FAILED_IDS = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Resolved bridge settings, populated by {@link #init(ServerLevel, BlockPos)}. */
    private ResourceLocation deckingNbtId = FALLBACK_DECKING_ID;
    private ResourceLocation endNbtId = FALLBACK_END_ID;
    private ResourceLocation pierNbtId = FALLBACK_PIER_ID;
    private ResourceLocation railingNbtId = FALLBACK_RAILING_ID;
    private double deckHalfWidth = FALLBACK_DECK_HALF_WIDTH;
    /** NBT Y row of the pillar piece that aligns with the deck surface (datapack: bridge_pillar_deck_row). */
    private int pillarDeckRow = 0;
    private boolean initialized = false;

    /** Head-persistent longitudinal counter (set per pass); drives railing pattern + pier boundaries. */
    private BridgePillarSpacingCounter railingCounter;
    /** Fallback longitudinal index when no head counter is set (localized passes). */
    private int localRailingIndex = 0;
    /** Pier positions collected at railing-pattern boundaries: [worldPos, tangent]. */
    private final List<Vec3[]> pillarBoundaries = new ArrayList<>();
    /** Deck columns (XZ) already railed - the bezier samples many rows per block, so advance once each. */
    private final Set<Long> railedColumns = new java.util.HashSet<>();

    /** Accumulates positions of connectable blocks across all placements in a bridge. */
    private final List<BlockPos> connectablePositions = new ArrayList<>();
    /** Tracks positions where decking has been placed to prevent overlapping. */
    private final Set<Long> placedDeckingPositions = new java.util.HashSet<>();
    /** Block positions occupied by the bezier track geometry - decking blocks here are skipped. */
    private final Set<Long> trackFootprint = new java.util.HashSet<>();
    /** Positions near branch junctions where the top block (y+1) of end walls should be skipped. */
    private final Set<Long> junctionZone = new java.util.HashSet<>();
    /** Positions on the branch side of a junction where end walls should be replaced with base blocks. */
    private final Set<Long> branchSideZone = new java.util.HashSet<>();
    /** Positions where end walls have been placed - used to avoid downgrading end -> base on overlap. */
    private final Set<Long> endWallPositions = new java.util.HashSet<>();
    /** XZ columns near a junction where pillars / piers are suppressed (kept out of the fork). */
    private final Set<Long> pillarExclusionXZ = new java.util.HashSet<>();

    /**
     * Adds the bezier's track-curve blocks to the footprint so subsequent
     * decking placements skip them.
     *
     * @param segments the bezier segments describing the curve
     * @param origin   the world-space origin to add to relative segment positions
     */
    public void buildTrackFootprint(List<BezierSegmentData> segments, Vec3 origin) {
        for (BezierSegmentData segment : segments) {
            Vec3 worldPos = segment.position().add(origin);
            Vec3 tangent = segment.derivative();
            if (tangent == null) continue;

            // Compute the perpendicular (normal) in the XZ plane
            Vec3 normal = segment.normal();
            if (normal == null) {
                normal = tangent.cross(new Vec3(0, 1, 0)).normalize();
            }

            // Sample center and ±1 block along the normal to cover the track width
            int centerY = Mth.floor(worldPos.y);
            for (double offset = -1.0; offset <= 1.0; offset += 0.5) {
                Vec3 p = worldPos.add(normal.scale(offset));
                int px = Mth.floor(p.x);
                int pz = Mth.floor(p.z);
                // Mark at track level and one above (track blocks can be up to 1 block tall)
                trackFootprint.add(BlockPos.asLong(px, centerY, pz));
                trackFootprint.add(BlockPos.asLong(px, centerY + 1, pz));
            }

            // On diagonal inclines, the center decking base block clips slightly through
            // the track. Mark the exact center at the decking level so placeBaseBlock skips it.
            double absX = Math.abs(tangent.x);
            double absZ = Math.abs(tangent.z);
            double maxXZ = Math.max(absX, absZ);
            boolean isDiagonal = maxXZ > 0.01 && Math.min(absX, absZ) / maxXZ > 0.3;
            boolean isInclined = Math.abs(tangent.y) > 0.01;
            if (isDiagonal && isInclined) {
                int trackY = Mth.floor(worldPos.y - slopeBias(tangent));
                int deckingY = trackY - 1;
                trackFootprint.add(BlockPos.asLong(Mth.floor(worldPos.x), deckingY, Mth.floor(worldPos.z)));
            }
        }
    }

    /**
     * Adds a straight track corridor to the track footprint exclusion zone.
     *
     * @param junctionPos the junction point where the branch splits from the parent
     * @param parentDir   the parent track's travel direction
     * @param halfLength  how many blocks in each direction along the parent track to exclude
     */
    public void addStraightTrackExclusion(BlockPos junctionPos, Direction parentDir, int halfLength) {
        Direction perpDir = parentDir.getClockWise();
        int perpHalf = (int) Math.ceil(deckHalfWidth);
        for (int i = -halfLength; i <= halfLength; i++) {
            BlockPos pos = junctionPos.relative(parentDir, i);
            for (int perpOff = -perpHalf; perpOff <= perpHalf; perpOff++) {
                BlockPos p = pos.relative(perpDir, perpOff);
                // Mark at decking level (Y-1), track level (Y), and above (Y+1)
                for (int dy = -1; dy <= 1; dy++) {
                    trackFootprint.add(BlockPos.asLong(p.getX(), pos.getY() + dy, p.getZ()));
                }
            }
        }
    }

    /**
     * Marks a zone on the branch side of a junction where the top block (y+1, track level)
     * of decking end walls should be skipped to prevent overlap with diverging track.
     * Only marks positions on the branch side (perpendicular offset >= 0 in branchDir),
     * so the non-branch side retains its full end walls.
     *
     * @param junctionPos the junction point where the branch diverges
     * @param radius      how many blocks along the parent direction and into the branch side
     * @param parentDir   the parent track's travel direction
     * @param branchDir   the direction the branch goes (perpendicular to parent)
     */
    public void addJunctionZone(BlockPos junctionPos, int radius, Direction parentDir, Direction branchDir) {
        for (int along = -radius; along <= radius; along++) {
            BlockPos p = junctionPos.relative(parentDir, along);
            for (int perp = 0; perp <= radius; perp++) {
                BlockPos bp = p.relative(branchDir, perp);
                junctionZone.add(BlockPos.asLong(bp.getX(), junctionPos.getY(), bp.getZ()));
            }
        }
    }

    /**
     * Direction-agnostic junction zone: a disc of XZ radius at the center's Y where railing/
     * superstructure tops are suppressed. Used at merges, where two decks converge from an unknown
     * relative direction and a directional {@link #addJunctionZone} can't be computed cleanly.
     */
    public void addJunctionZoneRadial(BlockPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                junctionZone.add(BlockPos.asLong(center.getX() + dx, center.getY(), center.getZ() + dz));
            }
        }
    }

    /**
     * Marks the branch-side edge of decking near a junction so end walls are replaced
     * with base blocks.
     *
     * @param junctionPos the junction point where the branch diverges
     * @param parentDir   the parent track's travel direction
     * @param branchDir   the direction the branch goes (perpendicular to parent)
     * @param halfLength  how many blocks along the parent track to mark
     */
    public void addBranchSideZone(BlockPos junctionPos, Direction parentDir, Direction branchDir, int halfLength) {
        for (int i = -halfLength; i <= halfLength; i++) {
            BlockPos along = junctionPos.relative(parentDir, i);
            // Mark 1-4 blocks on the branch side as "no end wall"
            for (int d = 1; d <= 4; d++) {
                BlockPos p = along.relative(branchDir, d);
                // Mark at decking level (Y-1) so placeEndWall checks hit
                branchSideZone.add(BlockPos.asLong(p.getX(), along.getY() - 1, p.getZ()));
            }
        }
    }

    /**
     * Marks an XZ area (Chebyshev radius around center) where pillars and piers are
     * suppressed, so a pier's legs or over-track beam never land in the fork between diverging
     * tracks at a junction. Y is ignored - the exclusion is by column.
     */
    public void addPillarExclusion(BlockPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                pillarExclusionXZ.add(BlockPos.asLong(center.getX() + dx, 0, center.getZ() + dz));
            }
        }
    }

    /** True when (x,z) is inside a junction pillar-exclusion zone (see {@link #addPillarExclusion}). */
    private boolean isPillarExcluded(int x, int z) {
        return pillarExclusionXZ.contains(BlockPos.asLong(x, 0, z));
    }

    /**
     * Fills the triangular "gore" between a parent track and a diverging branch at a junction, so the
     * fork reads as solid deck instead of an open notch. Decks the wedge between {@code parentDir} and
     * {@code branchDir} fanning out from the junction, but only over genuine holes (air/liquid) - on a
     * ground-level fork the terrain already fills it, so this is a no-op there, and it never webs the
     * open space past {@code radius} where the two decks legitimately separate. Flat deck only; the
     * inner edge of a fork carries no railing.
     */
    public void fillJunctionGore(ServerLevel level, BlockPos junctionPos, Direction parentDir, Direction branchDir) {
        init(level, junctionPos);
        BlockState deck = getDeckSurfaceBlock(level);
        if (deck == null) return;

        double px = parentDir.getStepX(), pz = parentDir.getStepZ();
        double bx = branchDir.getStepX(), bz = branchDir.getStepZ();
        double crossPB = px * bz - pz * bx;
        if (crossPB == 0) return; // parent and branch parallel - no wedge to fill

        int deckingY = junctionPos.getY() - 1;
        int radius = 2 * (int) Math.ceil(deckHalfWidth); // tight to the fork; beyond this the decks separate
        int jx = junctionPos.getX(), jz = junctionPos.getZ();
        StationBlockProtection protection = VillageTargetingSavedData.get(level).getStationBlockProtection();
        List<int[]> filled = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if ((dx == 0 && dz == 0) || dx * dx + dz * dz > radius * radius) continue;
                double crossPv = px * dz - pz * dx; // which side of the parent line
                double crossBv = bx * dz - bz * dx; // which side of the branch line
                if (crossPv * crossPB < 0) continue;                 // must be on the branch side of the parent
                if (crossBv * crossPB > 0) continue;                 // must be on the parent side of the branch
                if (dx * (px + bx) + dz * (pz + bz) <= 0) continue;  // forward of the junction

                BlockPos pos = new BlockPos(jx + dx, deckingY, jz + dz);
                long packed = pos.asLong();
                if (trackFootprint.contains(packed) || !placedDeckingPositions.add(packed)) continue;
                BlockState existing = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
                if (existing == null) continue;
                // Only close genuine holes; don't carpet solid terrain on a ground-level fork.
                if (!BlockTypeUtil.isAirOrLiquid(existing) && !existing.canBeReplaced()) continue;

                ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, deck, true);
                protection.protect(pos);
                filled.add(new int[]{pos.getX(), pos.getZ()});
            }
        }
        if (filled.isEmpty()) return;

        // Outer-railing continuity: rail any filled block that borders open air (the fork's outer
        // perimeter), so the gore deck isn't an open edge. Edges abutting a track or existing deck
        // are not open, so they stay rail-free; the walls connect to existing railings on cleanup.
        railGoreEdges(level, filled, deckingY, protection);
        VillageTargetingSavedData.get(level).setDirty();
    }

    /** Places the railing stack on filled gore blocks whose deck-level neighbour is open air. */
    private void railGoreEdges(ServerLevel level, List<int[]> filled, int deckingY, StationBlockProtection protection) {
        List<BlockState> railing = getRailingColumn(level);
        if (railing.isEmpty()) return;

        for (int[] g : filled) {
            int x = g[0], z = g[1];
            boolean openEdge = isOpenAtDeck(level, x + 1, deckingY, z) || isOpenAtDeck(level, x - 1, deckingY, z)
                    || isOpenAtDeck(level, x, deckingY, z + 1) || isOpenAtDeck(level, x, deckingY, z - 1);
            if (!openEdge) continue;

            for (int i = 0; i < railing.size(); i++) {
                BlockPos rp = new BlockPos(x, deckingY + 1 + i, z);
                if (trackFootprint.contains(rp.asLong())) break;
                BlockState existing = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, rp);
                if (existing == null || CreateTrackUtil.isTrackBlock(existing)) break;
                if (!BlockTypeUtil.isAirOrLiquid(existing) && !existing.canBeReplaced()) break;
                BlockState rail = railing.get(i);
                ChunkSafeBlockAccess.setBlockStateNonBlocking(level, rp, rail, true);
                protection.protect(rp);
                if (isConnectable(rail)) connectablePositions.add(rp.immutable());
            }
        }
    }

    /** True when the deck-level block at (x,y,z) is open (air/liquid/replaceable) - a fall-off edge. */
    private boolean isOpenAtDeck(ServerLevel level, int x, int y, int z) {
        BlockState s = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, new BlockPos(x, y, z));
        return s != null && (BlockTypeUtil.isAirOrLiquid(s) || s.canBeReplaced());
    }

    /** The railing block stack for gore edges: the railing pattern's column 0 (the post). */
    private List<BlockState> getRailingColumn(ServerLevel level) {
        List<BlockState> rail = new ArrayList<>();
        LoadedSchematic railing = getRailing(level);
        if (railing != null) {
            for (int y = 0; y < railing.getHeight(); y++) {
                BlockState s = railing.getBlock(0, y, 0); // post column
                if (s.isAir()) break;
                rail.add(s);
            }
        }
        return rail;
    }

    /** The deck-surface block the sweep places: cross-section centre column surface, or legacy base. */
    @Nullable
    private BlockState getDeckSurfaceBlock(ServerLevel level) {
        LoadedSchematic schematic = getDeckingSchematic(level);
        if (schematic != null && schematic.getWidth() > 1) {
            int deckColumn = (schematic.getWidth() - 1) / 2;
            BlockState surface = schematic.getBlock(deckColumn, deckSurfaceRow(schematic, deckColumn), 0);
            if (!surface.isAir()) return surface;
        }
        return getDeckingBaseBlock(level);
    }

    // ---- Bridge settings ----

    /**
     * Resolves biome settings for this bridge run once, using the biome at {@code referencePos}.
     */
    public void init(ServerLevel level, BlockPos referencePos) {
        if (initialized) return;
        ResolvedBiomeSettings settings = BiomeSettingsLoader.INSTANCE.resolve(level.getBiome(referencePos));
        this.deckingNbtId = settings.getBridgeDeckingNbt();
        this.endNbtId = settings.getBridgeEndNbt();
        this.pierNbtId = settings.getBridgePierNbt();
        this.pillarDeckRow = settings.getBridgePillarDeckRow();
        this.deckHalfWidth = settings.getBridgeHalfWidth();
        this.railingNbtId = settings.getBridgeRailingNbt();
        this.initialized = true;
    }

    // ---- Schematic loading ----

    /**
     * Loads the full decking schematic. A 1-wide schematic is the legacy flat decking
     * (single base block + a separate end-wall NBT); a wider schematic is a transverse
     * cross-section "bay" profile, stamped as vertical columns (see {@link #placeCrossSectionRow}).
     */
    @Nullable
    private LoadedSchematic getDeckingSchematic(ServerLevel level) {
        return loadSchematic(level, deckingNbtId, "bridge decking");
    }

    /**
     * Gets the legacy flat decking base block by reading block (0,0,0) from the decking NBT.
     * Only used for 1-wide decking schematics; cross-section profiles stamp columns directly.
     */
    @Nullable
    private BlockState getDeckingBaseBlock(ServerLevel level) {
        BlockState cached = DECKING_BASE_BLOCK_CACHE.get(deckingNbtId);
        if (cached != null) return cached;

        LoadedSchematic schematic = getDeckingSchematic(level);
        if (schematic == null) return null;
        BlockState block = schematic.getBlock(0, 0, 0);
        if (block.isAir()) {
            LOGGER.error("[BRIDGE-SCHEMATIC] Bridge decking NBT block at (0,0,0) is air: {}", deckingNbtId);
            return null;
        }
        DECKING_BASE_BLOCK_CACHE.put(deckingNbtId, block);
        return block;
    }

    /**
     * Gets the decking end schematic (edge railing).
     */
    @Nullable
    private LoadedSchematic getDeckingEnd(ServerLevel level) {
        return loadSchematic(level, endNbtId, "bridge end");
    }

    @Nullable
    private static LoadedSchematic loadSchematic(ServerLevel level, ResourceLocation id, String label) {
        LoadedSchematic cached = SCHEMATIC_CACHE.get(id);
        if (cached != null) return cached;
        if (FAILED_IDS.contains(id)) return null;

        LoadedSchematic schematic = NbtSchematicLoader.loadFromResources(
                level.getServer().getResourceManager(), id);
        if (schematic == null) {
            LOGGER.error("[BRIDGE-SCHEMATIC] Failed to load {} NBT: {}", label, id);
            FAILED_IDS.add(id);
            return null;
        }
        SCHEMATIC_CACHE.put(id, schematic);
        return schematic;
    }

    // ---- Rotation and slope helpers ----

    /**
     * Returns a Y bias based on slope steepness.
     * Flat tracks (tangent.y near 0) get no bias. Inclined tracks get up to 0.5
     * so the decking doesn't step up before the visual track curve does.
     */
    private static double slopeBias(Vec3 tangent) {
        double absY = Math.abs(tangent.y);
        if (absY < 0.01) return 0.0;
        return Math.min(absY * 4.0, 0.5);
    }

    /**
     * Returns a slope bias that ramps up from zero at the curve endpoints.
     */
    private static double rampedSlopeBias(Vec3 tangent, double currentY, double startY, double endY, double progress) {
        double maxBias = slopeBias(tangent);
        if (maxBias < 0.01) return 0.0;
        double dispRamp = Math.min(Math.abs(currentY - startY), Math.abs(currentY - endY));
        double progressRamp = Math.min(progress, 1.0 - progress);
        // 2x factor widens the plateau where bias sits at maxBias, pushing trackY
        // transitions close to the endpoints. On shallow 1-Y transitions this keeps
        // decking at the lower block for almost the whole curve - matches the visual
        // flat-ends of Create's elevation bezier on both ascents and descents.
        return Math.min(maxBias, 2.0 * Math.max(dispRamp, progressRamp));
    }

    /**
     * Calculates rotation to orient blocks perpendicular to the track tangent.
     * For east-west tracks (tangent along X), rotate 90 degrees.
     * For north-south tracks (tangent along Z), no rotation needed.
     */
    private static Rotation calculatePerpendicularRotation(Vec3 tangent) {
        if (Math.abs(tangent.x) > Math.abs(tangent.z)) {
            return Rotation.CLOCKWISE_90;
        }
        return Rotation.NONE;
    }

    // ---- Decking placement ----

    /**
     * Places decking at trackY-1, centered on track position.
     * For straight track with integer Y positions.
     */
    public void placeDeckingAt(ServerLevel level, BlockPos trackPos, Vec3 tangent) {
        init(level, trackPos);
        placeDeckingAtY(level, trackPos.getX(), trackPos.getY(), trackPos.getZ(), tangent);
    }

    /**
     * Places decking along a bezier curve using center-outward symmetric placement.
     *
     * @param level    the server level
     * @param segments the bezier segments describing the curve
     * @param origin   the world-space origin to add to relative segment positions
     */
    public void placeDeckingAlongCurve(ServerLevel level, List<BezierSegmentData> segments, Vec3 origin) {
        if (!segments.isEmpty()) {
            Vec3 firstWorld = segments.get(0).position().add(origin);
            init(level, BlockPos.containing(firstWorld));
        }
        LoadedSchematic deckingSchematic = getDeckingSchematic(level);
        boolean crossSection = deckingSchematic != null && deckingSchematic.getWidth() > 1;
        BlockState baseBlock = crossSection ? null : getDeckingBaseBlock(level);
        LoadedSchematic endSchematic = crossSection ? null : getDeckingEnd(level);
        if ((baseBlock == null && !crossSection) || segments.isEmpty()) return;

        StationBlockProtection protection = VillageTargetingSavedData.get(level).getStationBlockProtection();

        // Compute endpoint Y values to ramp slopeBias near curve endpoints.
        // At endpoints the Y matches the flat section, so bias should be zero to avoid
        // a 1-block dip at the flat->slope transition.
        double startY = segments.get(0).position().add(origin).y;
        double endY = segments.get(segments.size() - 1).position().add(origin).y;

        int prevCenterX = Integer.MIN_VALUE, prevCenterZ = Integer.MIN_VALUE, prevDeckingY = Integer.MIN_VALUE;
        Vec3 prevNormal = null;

        int lastIdx = Math.max(1, segments.size() - 1);
        // On a shallow 1-Y transition the lower block supports the whole curve
        int minEndpointBlockY = Mth.floor(Math.min(startY, endY));
        int maxEndpointBlockY = Mth.floor(Math.max(startY, endY));
        boolean shallowOneBlockTransition = (maxEndpointBlockY - minEndpointBlockY) == 1;
        for (int segIdx = 0; segIdx < segments.size(); segIdx++) {
            BezierSegmentData segment = segments.get(segIdx);
            Vec3 worldPos = segment.position().add(origin);
            Vec3 derivative = segment.derivative();
            if (derivative == null) continue;

            Vec3 normal = segment.normal();
            if (normal == null) {
                normal = derivative.cross(new Vec3(0, 1, 0)).normalize();
            }

            double progress = (double) segIdx / lastIdx;
            int trackY = Mth.floor(worldPos.y - rampedSlopeBias(derivative, worldPos.y, startY, endY, progress));
            if (shallowOneBlockTransition && progress > 0.02 && progress < 0.98) {
                trackY = minEndpointBlockY;
            }
            int deckingY = trackY - 1;
            Rotation rotation = calculatePerpendicularRotation(derivative);

            int centerX = Mth.floor(worldPos.x);
            int centerZ = Mth.floor(worldPos.z);

            // Fill gaps between this segment and the previous one
            if (prevCenterX != Integer.MIN_VALUE) {
                fillCurveGaps(level, deckingSchematic, baseBlock, endSchematic, protection,
                        prevCenterX, prevCenterZ, prevDeckingY, prevNormal,
                        centerX, centerZ, deckingY, normal, rotation);
            }

            SupportDecisionLogger.capture(level, new BlockPos(centerX, trackY, centerZ),
                    SupportProfileRecord.Decision.BRIDGE_DECKING);
            placeDeckingRowFromCenter(level, deckingSchematic, baseBlock, endSchematic, protection,
                    centerX, centerZ, deckingY, normal, rotation);

            prevCenterX = centerX;
            prevCenterZ = centerZ;
            prevDeckingY = deckingY;
            prevNormal = normal;
        }

        VillageTargetingSavedData.get(level).setDirty();
    }

    /**
     * Fills decking rows for any block-position gaps between consecutive bezier segments.
     * Interpolates the center position and normal between the two segments.
     */
    private void fillCurveGaps(ServerLevel level, @Nullable LoadedSchematic deckingSchematic,
                                BlockState baseBlock, @Nullable LoadedSchematic endSchematic,
                                StationBlockProtection protection,
                                int prevCX, int prevCZ, int prevY, Vec3 prevNormal,
                                int currCX, int currCZ, int currY, Vec3 currNormal,
                                Rotation rotation) {
        int dx = currCX - prevCX;
        int dy = currY - prevY;
        int dz = currCZ - prevCZ;
        int manhattan = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        if (manhattan <= 1) return;

        for (int i = 1; i < manhattan; i++) {
            double t = (double) i / manhattan;
            int gapX = prevCX + Math.round((float) dx * i / manhattan);
            int gapZ = prevCZ + Math.round((float) dz * i / manhattan);
            // Floor for Y to match TerrainFillPlacer: Math.round biases upward at the
            // midpoint, causing decking to step up prematurely on shallow diagonals.
            int gapY = prevY + Mth.floor((float) dy * i / manhattan);

            Vec3 gapNormal = prevNormal.scale(1.0 - t).add(currNormal.scale(t)).normalize();

            placeDeckingRowFromCenter(level, deckingSchematic, baseBlock, endSchematic, protection,
                    gapX, gapZ, gapY, gapNormal, rotation);
        }
    }

    /**
     * Core decking placement for straight tracks and single positions.
     */
    private void placeDeckingAtY(ServerLevel level, int x, int trackY, int z, Vec3 tangent) {
        // Skip if decking was already placed at this center position (prevents overlap on curves)
        long packedPos = BlockPos.asLong(x, trackY, z);
        if (!placedDeckingPositions.add(packedPos)) return;

        LoadedSchematic deckingSchematic = getDeckingSchematic(level);
        boolean crossSection = deckingSchematic != null && deckingSchematic.getWidth() > 1;
        BlockState baseBlock = crossSection ? null : getDeckingBaseBlock(level);
        LoadedSchematic endSchematic = crossSection ? null : getDeckingEnd(level);
        if (baseBlock == null && !crossSection) return;

        Vec3 perp = new Vec3(-tangent.z, 0, tangent.x).normalize();
        int deckingY = trackY - 1;
        StationBlockProtection protection = VillageTargetingSavedData.get(level).getStationBlockProtection();
        Rotation rotation = calculatePerpendicularRotation(tangent);

        SupportDecisionLogger.capture(level, new BlockPos(x, trackY, z),
                SupportProfileRecord.Decision.BRIDGE_DECKING);
        placeDeckingRowFromCenter(level, deckingSchematic, baseBlock, endSchematic, protection,
                x, z, deckingY, perp, rotation);

        VillageTargetingSavedData.get(level).setDirty();
    }

    /**
     * Places a symmetric row of decking blocks centered at (centerX, centerZ) at the given Y,
     * walking outward along the perpendicular in both directions independently.
     */
    private void placeDeckingRowFromCenter(ServerLevel level, @Nullable LoadedSchematic deckingSchematic,
                                            BlockState baseBlock,
                                            @Nullable LoadedSchematic endSchematic,
                                            StationBlockProtection protection,
                                            int centerX, int centerZ, int deckingY,
                                            Vec3 perp, Rotation rotation) {
        List<int[]> positions = computePerpendicularPositions(centerX, centerZ, perp);

        // Cross-section "bay": a decking NBT wider than one block is a transverse profile.
        // Stamp it as vertical columns along the same per-position list the flat path uses,
        // so curves, diagonals and gap-fill all carry over unchanged.
        if (deckingSchematic != null && deckingSchematic.getWidth() > 1) {
            // On ~45° segments use the diagonal deck variant (a full X*Z footprint stamped + rotated),
            // since the cardinal transverse sweep snaps to a cardinal and reads as a mess on diagonals.
            Vec3 tangent = new Vec3(perp.z, 0, -perp.x);
            LoadedSchematic diag = isDiagonalHeading(tangent)
                    ? loadSchematicQuiet(level, diagVariant(deckingNbtId)) : null;
            if (diag != null && diag.getWidth() > 1 && diag.getLength() > 1) {
                placeDiagCrossSection(level, diag, protection, positions, deckingY);
            } else {
                placeCrossSectionRow(level, deckingSchematic, protection, positions, deckingY, rotation);
            }
            // NOTE: railing + pillar still use the cardinal pieces on diagonals for now (staged).
            placeRailingRow(level, positions, deckingY, centerX, centerZ, perp, protection);
            return;
        }

        for (int i = 0; i < positions.size(); i++) {
            int[] pos = positions.get(i);
            long packed = BlockPos.asLong(pos[0], deckingY, pos[1]);
            boolean alreadyPlaced = !placedDeckingPositions.add(packed);
            boolean isEdge = (i == 0 || i == positions.size() - 1);

            if (alreadyPlaced) {
                // Allow upgrading base -> end on curve inner edges where a previous
                // row placed a base block at what is this row's edge position.
                if (isEdge && endSchematic != null
                        && !branchSideZone.contains(packed)
                        && !endWallPositions.contains(packed)) {
                    placeEndWall(level, endSchematic, pos[0], deckingY, pos[1], rotation, protection);
                    endWallPositions.add(packed);
                }
                continue;
            }

            if (isEdge && endSchematic != null
                    && !branchSideZone.contains(packed)) {
                placeEndWall(level, endSchematic, pos[0], deckingY, pos[1], rotation, protection);
                endWallPositions.add(packed);
            } else {
                placeBaseBlock(level, baseBlock, pos[0], deckingY, pos[1], protection);
            }
        }
    }

    // ---- Railing pattern (longitudinal) ----

    /** Sets the head-persistent longitudinal counter so the railing pattern is continuous across passes. */
    public void setRailingCounter(BridgePillarSpacingCounter counter) {
        this.railingCounter = counter;
    }

    /** Pier positions collected at railing-pattern boundaries during decking: [worldPos, tangent]. */
    public List<Vec3[]> getPillarBoundaries() {
        return pillarBoundaries;
    }

    @Nullable
    private LoadedSchematic getRailing(ServerLevel level) {
        return loadSchematic(level, railingNbtId, "bridge railing");
    }

    private int nextRailingIndex() {
        return railingCounter != null ? railingCounter.nextLongitudinal() : localRailingIndex++;
    }

    /**
     * Stamps the repeating railing pattern on both deck edges for one row, and - at each pattern
     * boundary (column 0) - records a pier position. The pattern NBT is read along its X axis as the
     * longitudinal coordinate; one column is consumed per deck row, so the pattern length is the pier
     * spacing. Railings sit just above the deck surface.
     */
    private void placeRailingRow(ServerLevel level, List<int[]> positions, int deckingY,
                                  int centerX, int centerZ, Vec3 perp, StationBlockProtection protection) {
        LoadedSchematic railing = getRailing(level);
        if (railing == null || positions.size() < 2 || railing.getWidth() < 1) return;

        // One step per unique deck column. The bezier samples many rows per block, so without this
        // the longitudinal index races ahead and piers bunch up every 2-3 blocks instead of per pattern.
        if (!railedColumns.add(BlockPos.asLong(centerX, 0, centerZ))) return;

        int patternLength = railing.getWidth();
        int height = railing.getHeight();
        // Railing runs CONTINUOUSLY along the deck (pattern repeats every patternLength). Piers are
        // collected every (patternLength + pillar depth) columns; the pillar's post simply overwrites
        // the railing where it pokes through (piers are placed after decking). We do NOT blank a pier-
        // wide gap - that left huge blank stretches as deep as the pillar (e.g. 5 with the braced pier).
        int period = patternLength + pierDepth(level);
        int idx = nextRailingIndex();

        // perp = (-tz,0,tx) ⇒ tangent = (perp.z,0,-perp.x).
        Vec3 tangent = new Vec3(perp.z, 0, -perp.x);
        if (Math.floorMod(idx, period) == 0) {
            // Collect a pier here; its post (placed after decking) punches through the railing.
            pillarBoundaries.add(new Vec3[]{ new Vec3(centerX + 0.5, deckingY + 1, centerZ + 0.5), tangent });
        }

        // Diagonal railing: spaced fence posts at the deck edges. The cardinal pattern's gate facings
        // don't translate to 45°, so on diagonals we drop them and place bridge_railing_diag's post block
        // at each edge every N diagonal steps (N = the asset's post spacing).
        if (isDiagonalHeading(tangent)) {
            LoadedSchematic dr = loadSchematicQuiet(level, diagVariant(railingNbtId));
            if (dr != null) {
                if (Math.floorMod(idx, diagRailingPeriod(dr)) == 0) {
                    BlockState post = firstNonAir(dr);
                    if (post != null) {
                        int[] le = positions.get(0);
                        int[] ri = positions.get(positions.size() - 1);
                        placeDiagRailingPost(level, post, le[0], deckingY + 1, le[1], protection);
                        placeDiagRailingPost(level, post, ri[0], deckingY + 1, ri[1], protection);
                    }
                }
                return;
            }
        }

        // Rotate directional railing blocks (fence gates) to align lengthwise with the track. The
        // pattern is authored along +X (east), so rotate from east to the actual track direction.
        Rotation rot = rotationFromEast(Direction.getNearest(tangent.x, 0.0, tangent.z));
        int col = Math.floorMod(idx, patternLength); // pattern column, continuous
        int[] left = positions.get(0);
        int[] right = positions.get(positions.size() - 1);
        stampRailingColumn(level, railing, col, height, left[0], deckingY, left[1], rot, protection);
        stampRailingColumn(level, railing, col, height, right[0], deckingY, right[1], rot, protection);
    }

    /** Rotation mapping the railing pattern's authored +X (east) direction to the actual track direction. */
    private static Rotation rotationFromEast(Direction trackDir) {
        switch (trackDir) {
            case SOUTH: return Rotation.CLOCKWISE_90;
            case WEST:  return Rotation.CLOCKWISE_180;
            case NORTH: return Rotation.COUNTERCLOCKWISE_90;
            default:    return Rotation.NONE; // EAST (and any non-horizontal fallback)
        }
    }

    /** Along-track depth (NBT X size) of the pillar piece - how many blocks the pier reserves in the cycle. */
    private int pierDepth(ServerLevel level) {
        LoadedSchematic pier = loadSchematic(level, pierNbtId, "bridge pillar");
        return pier != null ? Math.max(1, pier.getWidth()) : 1;
    }

    /** Stamps one railing column (above the deck) at an edge, rotated to the track, skipping junction/branch/track. */
    private void stampRailingColumn(ServerLevel level, LoadedSchematic railing, int col, int height,
                                     int x, int deckingY, int z, Rotation rotation, StationBlockProtection protection) {
        // Suppress railings inside a junction zone or on the branch side of a fork.
        if (junctionZone.contains(BlockPos.asLong(x, deckingY + 1, z))
                || branchSideZone.contains(BlockPos.asLong(x, deckingY, z))) {
            return;
        }
        for (int y = 0; y < height; y++) {
            BlockState state = railing.getBlock(col, y, 0);
            if (state.isAir()) continue;
            BlockPos pos = new BlockPos(x, deckingY + 1 + y, z);
            if (trackFootprint.contains(pos.asLong())) continue;
            BlockState existing = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
            if (existing == null || CreateTrackUtil.isTrackBlock(existing)) continue;
            BlockState rotated = RotationHelper.rotateBlockState(level, pos, state, rotation);
            ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, rotated, true);
            protection.protect(pos);
            if (isConnectable(rotated)) connectablePositions.add(pos.immutable());
        }
    }

    /**
     * Stamps a transverse cross-section profile across one decking row.
     *
     * <p>The profile NBT is read as columns along its X axis: the outer {@code (W-1)/2} columns
     * on each side are placed verbatim at the row's edges (railings / parapet), and the center
     * column is repeated across the whole interior deck. Width therefore only needs to be
     * {@code 2*edgeColumns + 1}; the physical deck width still comes from {@code deckHalfWidth}.
     * The deck-surface row (the topmost solid block of the center column) lands at the decking
     * level; rows below it are stamped under the deck (girders/fascia) and rows above it are
     * superstructure (railings/parapet). See {@link #deckSurfaceRow}.
     *
     * @param positions left-to-right block positions for this row (from {@link #computePerpendicularPositions})
     */
    private void placeCrossSectionRow(ServerLevel level, LoadedSchematic profile,
                                       StationBlockProtection protection, List<int[]> positions,
                                       int deckingY, Rotation rotation) {
        int width = profile.getWidth();
        int height = profile.getHeight();
        int edgeColumns = (width - 1) / 2; // superstructure columns on each side
        int deckColumn = edgeColumns;      // center column, repeated across the interior
        int deckRowOffset = deckSurfaceRow(profile, deckColumn); // NBT rows below this sit under the deck
        int n = positions.size();

        for (int i = 0; i < n; i++) {
            int fromLeft = i;
            int fromRight = n - 1 - i;
            int column;
            boolean isEdge;
            if (fromLeft < edgeColumns && fromLeft <= fromRight) {
                column = fromLeft;                  // left superstructure column
                isEdge = true;
            } else if (fromRight < edgeColumns) {
                column = width - 1 - fromRight;      // right superstructure column
                isEdge = true;
            } else {
                column = deckColumn;                // interior deck
                isEdge = false;
            }
            placeCrossSectionColumn(level, profile, column, height, deckColumn, deckRowOffset,
                    positions.get(i), deckingY, rotation, protection, isEdge);
        }
    }

    /**
     * Stamps one vertical column of a cross-section profile at a single (x,z), from deckingY upward.
     * Mirrors the footprint / junction / track-overlap guards used by the flat decking path.
     */
    private void placeCrossSectionColumn(ServerLevel level, LoadedSchematic profile, int column, int height,
                                          int deckColumn, int deckRowOffset, int[] pos, int deckingY, Rotation rotation,
                                          StationBlockProtection protection, boolean isEdge) {
        int x = pos[0];
        int z = pos[1];
        long deckPacked = BlockPos.asLong(x, deckingY, z);

        // On the branch side of a junction, drop superstructure back to plain deck so the
        // diverging track is not walled off.
        if (isEdge && branchSideZone.contains(deckPacked)) {
            column = deckColumn;
            isEdge = false;
        }

        // Dedup the deck row, but allow upgrading a previously-placed deck column to an edge
        // column (curve inner edges revisit interior positions as edges).
        boolean firstTime = placedDeckingPositions.add(deckPacked);
        if (!firstTime && (!isEdge || endWallPositions.contains(deckPacked))) {
            return;
        }
        if (isEdge) endWallPositions.add(deckPacked);

        for (int y = 0; y < height; y++) {
            BlockState state = profile.getBlock(column, y, 0);
            if (state.isAir() || state.is(Blocks.STRUCTURE_VOID)) continue;

            BlockPos worldPos = new BlockPos(x, deckingY + (y - deckRowOffset), z);
            long packed = worldPos.asLong();
            if (trackFootprint.contains(packed)) continue;
            // Skip superstructure (anything above the deck row) inside a junction zone.
            if (y > deckRowOffset && junctionZone.contains(packed)) continue;

            BlockState existing = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, worldPos);
            if (existing != null && CreateTrackUtil.isTrackBlock(existing)) continue;

            BlockState rotatedState = RotationHelper.rotateBlockState(level, worldPos, state, rotation);
            ChunkSafeBlockAccess.setBlockStateNonBlocking(level, worldPos, rotatedState, true);
            protection.protect(worldPos);

            if (isConnectable(rotatedState)) {
                connectablePositions.add(worldPos.immutable());
            }
        }
    }

    // ---- Diagonal variants (used when a segment heads ~45°; a curve is a sequence of these) ----

    /** True when the tangent points roughly diagonally (both horizontal axes within ~2:1). */
    private static boolean isDiagonalHeading(Vec3 tangent) {
        double ax = Math.abs(tangent.x), az = Math.abs(tangent.z);
        return ax > 1e-3 && az > 1e-3 && Math.min(ax, az) >= 0.5 * Math.max(ax, az);
    }

    /**
     * Rotation taking a diagonal piece authored for the SE heading (+x,+z) to the actual diagonal.
     * CLOCKWISE_90 maps (x,z)->(-z,x), i.e. SE->SW->NW->NE. (If the assets read 90° off in-world, the
     * authored base heading is wrong - flip this mapping.)
     */
    private static Rotation diagonalRotation(Vec3 tangent) {
        boolean east = tangent.x > 0, south = tangent.z > 0;
        if (east && south) return Rotation.NONE;            // SE
        if (!east && south) return Rotation.CLOCKWISE_90;   // SW
        if (!east) return Rotation.CLOCKWISE_180;           // NW (-,-)
        return Rotation.COUNTERCLOCKWISE_90;                // NE (+,-)
    }

    /** The {@code <id>_diag.nbt} sibling of a piece id. */
    private static ResourceLocation diagVariant(ResourceLocation base) {
        String path = base.getPath();
        int dot = path.lastIndexOf(".nbt");
        String diag = dot >= 0 ? path.substring(0, dot) + "_diag.nbt" : path + "_diag";
        return new ResourceLocation(base.getNamespace(), diag);
    }

    /** Like {@link #loadSchematic} but silent on a miss (a piece may simply have no diagonal variant). */
    private static LoadedSchematic loadSchematicQuiet(ServerLevel level, ResourceLocation id) {
        LoadedSchematic cached = SCHEMATIC_CACHE.get(id);
        if (cached != null) return cached;
        if (FAILED_IDS.contains(id)) return null;
        LoadedSchematic schematic = NbtSchematicLoader.loadFromResources(level.getServer().getResourceManager(), id);
        if (schematic == null) { FAILED_IDS.add(id); return null; }
        SCHEMATIC_CACHE.put(id, schematic);
        return schematic;
    }

    /**
     * Stamps a diagonal deck slice (the {@code _diag} cross-section - a full X*Z footprint, not a 1-deep
     * transverse slice) centred on the track point and rotated to the heading. Swept per diagonal step;
     * the deck-column dedup ({@link #placedDeckingPositions}) merges the overlaps into a continuous deck.
     */
    private void placeDiagCrossSection(ServerLevel level, LoadedSchematic diag, StationBlockProtection protection,
                                        List<int[]> positions, int deckingY) {
        // The diag cross-section is a transverse profile authored along its anti-diagonal: cell
        // (c, y, L-1-c) is transverse column c (edge..centre..edge). We sweep it over the perpendicular
        // positions with the SAME edge-verbatim + centre-repeated rule as the cardinal deck, so the
        // diagonal deck EXPANDS to the full width and the centre (deck) block dupes across the interior.
        // Blocks are authored omni-directional (vertical logs, stone), so no rotation is applied.
        int profLen = Math.min(diag.getWidth(), diag.getLength()); // cells along the anti-diagonal
        int height = diag.getHeight();
        int edgeColumns = (profLen - 1) / 2;
        int deckColumn = edgeColumns;
        int deckRowOffset = 0;
        for (int y = 0; y < height; y++) {
            BlockState s = diag.getBlock(deckColumn, y, diag.getLength() - 1 - deckColumn);
            if (!s.isAir() && !s.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID)) deckRowOffset = y;
        }
        int n = positions.size();
        for (int i = 0; i < n; i++) {
            int fromLeft = i, fromRight = n - 1 - i;
            int column;
            if (fromLeft < edgeColumns && fromLeft <= fromRight) column = fromLeft;
            else if (fromRight < edgeColumns) column = profLen - 1 - fromRight;
            else column = deckColumn;                      // interior -> centre column, duplicated
            int zc = diag.getLength() - 1 - column;        // anti-diagonal cell
            int x = positions.get(i)[0], z = positions.get(i)[1];
            if (!placedDeckingPositions.add(BlockPos.asLong(x, deckingY, z))) continue;
            for (int y = 0; y < height; y++) {
                BlockState state = diag.getBlock(column, y, zc);
                if (state.isAir() || state.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID)) continue;
                BlockPos worldPos = new BlockPos(x, deckingY + (y - deckRowOffset), z);
                if (trackFootprint.contains(worldPos.asLong())) continue;
                BlockState existing = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, worldPos);
                if (existing != null && CreateTrackUtil.isTrackBlock(existing)) continue;
                ChunkSafeBlockAccess.setBlockStateNonBlocking(level, worldPos, state, true);
                protection.protect(worldPos);
                if (isConnectable(state)) connectablePositions.add(worldPos.immutable());
            }
        }
    }

    /**
     * Stamps the diagonal pillar variant: a square footprint centred on the pier and rotated to the
     * heading, with the same vertical mapping as the cardinal pillar (deck row from bridge_pillar_deck_row,
     * y0 = foot filled to ground). No transverse scaling - the diag asset is authored at full size.
     */
    private void placeDiagPillarStamp(ServerLevel level, LoadedSchematic pillar, int x, int trackY, int z, Vec3 tangent) {
        int w = pillar.getWidth(), h = pillar.getHeight(), l = pillar.getLength();
        int ccx = (w - 1) / 2, ccz = (l - 1) / 2;
        StationBlockProtection protection = VillageTargetingSavedData.get(level).getStationBlockProtection();
        int deckSurfaceY = findPlacedDeckTop(level, protection, x, z, trackY);
        if (deckSurfaceY == Integer.MIN_VALUE) deckSurfaceY = trackY - 1;
        // pillar_diag's post-separation axis is 90° from the deck/railing's, so its posts would otherwise
        // run down the track centre (torches in the middle). Rotate it the extra quarter-turn so the posts
        // sit across the track. (If posts/trapdoors come out 180° off, swap to COUNTERCLOCKWISE_90.)
        Rotation rot = diagonalRotation(tangent).getRotated(Rotation.CLOCKWISE_90);

        // Spread the pillar across-track so its posts reach the actual deck edges (the asset is authored
        // narrower than the deck). We scale only the perpendicular component of each offset; the along-track
        // (fore/aft) extent is kept, so the braces stay put.
        double tx = tangent.x, tz = tangent.z;
        double tl = Math.sqrt(tx * tx + tz * tz);
        if (tl < 1e-6) { tx = 1; tz = 0; tl = 1; }
        tx /= tl; tz /= tl;
        double px = -tz, pz = tx; // perpendicular unit
        List<int[]> perpPos = computePerpendicularPositions(x, z, new Vec3(px, 0, pz));
        double targetHalf = perpPos.size() >= 2
                ? Math.abs((perpPos.get(0)[0] - x) * px + (perpPos.get(0)[1] - z) * pz)
                : deckHalfWidth;
        double srcHalf = 0;
        for (int xn = 0; xn < w; xn++) {
            for (int zn = 0; zn < l; zn++) {
                // Anchor the spread to the y0 foot/legs, so the POSTS reach the deck edge (where the
                // railing sits); decorations above (the trapdoor ends) then overhang just outside it.
                if (pillar.getBlock(xn, 0, zn).isAir()) continue;
                double[] r = rotateOffset(xn - ccx, zn - ccz, rot);
                srcHalf = Math.max(srcHalf, Math.abs(r[0] * px + r[1] * pz));
            }
        }
        double f = (srcHalf > 0.5) ? Math.max(1.0, targetHalf / srcHalf) : 1.0;

        for (int xn = 0; xn < w; xn++) {
            for (int zn = 0; zn < l; zn++) {
                double[] r = rotateOffset(xn - ccx, zn - ccz, rot);
                double along = r[0] * tx + r[1] * tz;
                double across = (r[0] * px + r[1] * pz) * f;
                int wx = x + (int) Math.round(along * tx + across * px);
                int wz = z + (int) Math.round(along * tz + across * pz);
                for (int y = 0; y < h; y++) {
                    BlockState state = pillar.getBlock(xn, y, zn);
                    if (state.isAir() || state.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID)) continue;
                    int wy = deckSurfaceY + (y - pillarDeckRow);
                    BlockState rotated = RotationHelper.rotateBlockState(level, new BlockPos(wx, wy, wz), state, rot);
                    if (y == 0) {
                        fillColumnToGround(level, rotated, wx, wy, wz, protection);
                    } else {
                        setAbutmentBlock(level, rotated, wx, wy, wz, protection, true);
                    }
                }
            }
        }
        VillageTargetingSavedData.get(level).setDirty();
    }

    /** Rotates a horizontal offset by a 90°-step rotation: CW90 maps (x,z)->(-z,x). */
    private static double[] rotateOffset(int ox, int oz, Rotation rot) {
        switch (rot) {
            case CLOCKWISE_90:        return new double[]{-oz, ox};
            case CLOCKWISE_180:       return new double[]{-ox, -oz};
            case COUNTERCLOCKWISE_90: return new double[]{oz, -ox};
            default:                  return new double[]{ox, oz};
        }
    }

    /** Post spacing (in diagonal steps) for the diagonal railing: the min X-gap between posts in the asset. */
    private static int diagRailingPeriod(LoadedSchematic dr) {
        java.util.TreeSet<Integer> xs = new java.util.TreeSet<>();
        for (int x = 0; x < dr.getWidth(); x++)
            for (int y = 0; y < dr.getHeight(); y++)
                for (int zz = 0; zz < dr.getLength(); zz++)
                    if (!dr.getBlock(x, y, zz).isAir()) xs.add(x);
        int min = Integer.MAX_VALUE, prev = Integer.MIN_VALUE;
        for (int xv : xs) { if (prev != Integer.MIN_VALUE) min = Math.min(min, xv - prev); prev = xv; }
        return min == Integer.MAX_VALUE ? 1 : Math.max(1, min);
    }

    /** First non-air block in a schematic (the diagonal railing's post block). */
    private static BlockState firstNonAir(LoadedSchematic s) {
        for (int x = 0; x < s.getWidth(); x++)
            for (int y = 0; y < s.getHeight(); y++)
                for (int zz = 0; zz < s.getLength(); zz++) {
                    BlockState b = s.getBlock(x, y, zz);
                    if (!b.isAir()) return b;
                }
        return null;
    }

    /** Places one diagonal railing post at a deck edge (skips junction/branch/track). */
    private void placeDiagRailingPost(ServerLevel level, BlockState post, int x, int y, int z, StationBlockProtection protection) {
        if (junctionZone.contains(BlockPos.asLong(x, y, z)) || branchSideZone.contains(BlockPos.asLong(x, y - 1, z))) return;
        BlockPos pos = new BlockPos(x, y, z);
        if (trackFootprint.contains(pos.asLong())) return;
        BlockState existing = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
        if (existing == null || CreateTrackUtil.isTrackBlock(existing)) return;
        ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, post, true);
        protection.protect(pos);
        if (isConnectable(post)) connectablePositions.add(pos.immutable());
    }

    /**
     * Finds the deck-surface row of a cross-section profile: the topmost solid block in the
     * deck (center) column. Rows below it are stamped under the deck (girders/fascia); rows
     * above are superstructure. Returns 0 (legacy: y=0 is the deck) if the column is empty.
     */
    private static int deckSurfaceRow(LoadedSchematic profile, int deckColumn) {
        for (int y = profile.getHeight() - 1; y >= 0; y--) {
            BlockState s = profile.getBlock(deckColumn, y, 0);
            if (!s.isAir() && !s.is(Blocks.STRUCTURE_VOID)) {
                return y;
            }
        }
        return 0;
    }

    /**
     * Computes unique block positions along the perpendicular direction from -deckHalfWidth to +deckHalfWidth.
     *
     * @return ordered list of [x, z] pairs from left edge to right edge
     */
    private List<int[]> computePerpendicularPositions(int centerX, int centerZ, Vec3 perp) {
        List<int[]> positions = new ArrayList<>();
        int prevX = Integer.MIN_VALUE, prevZ = Integer.MIN_VALUE;

        // Scale range so perpendicular coverage is always ~(2*deckHalfWidth+1) blocks wide.
        double maxComponent = Math.max(Math.abs(perp.x), Math.abs(perp.z));
        double range = maxComponent > 0.01 ? deckHalfWidth / maxComponent : deckHalfWidth;

        // Sample in 0.5 increments for smooth coverage
        for (double offset = -range; offset <= range + 0.01; offset += 0.5) {
            int bx = centerX + Mth.floor(perp.x * offset + 0.5);
            int bz = centerZ + Mth.floor(perp.z * offset + 0.5);
            if (bx != prevX || bz != prevZ) {
                positions.add(new int[]{bx, bz});
                prevX = bx;
                prevZ = bz;
            }
        }

        // Fill diagonal staircase gaps between consecutive positions
        List<int[]> filled = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            filled.add(positions.get(i));
            if (i + 1 < positions.size()) {
                int[] cur = positions.get(i);
                int[] next = positions.get(i + 1);
                int dx = next[0] - cur[0];
                int dz = next[1] - cur[1];
                // If both X and Z change, there's a diagonal gap to fill
                if (Math.abs(dx) + Math.abs(dz) > 1) {
                    double distStepX = Math.abs((cur[0] + dx - centerX) * perp.z - (cur[1] - centerZ) * perp.x);
                    double distStepZ = Math.abs((cur[0] - centerX) * perp.z - (cur[1] + dz - centerZ) * perp.x);
                    if (distStepX <= distStepZ) {
                        filled.add(new int[]{cur[0] + dx, cur[1]});
                    } else {
                        filled.add(new int[]{cur[0], cur[1] + dz});
                    }
                }
            }
        }

        return filled;
    }

    /**
     * Places a single decking base block at the given position.
     */
    private void placeBaseBlock(ServerLevel level, BlockState baseBlock, int x, int y, int z,
                                StationBlockProtection protection) {
        BlockPos worldPos = new BlockPos(x, y, z);
        long packed = worldPos.asLong();
        if (trackFootprint.contains(packed)) return;

        // Allow overwriting terrain blocks - bridge decking runs before deferred terrain
        // clearing, so terrain (stone, dirt, etc.) may still exist at the decking level.
        BlockState existing = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, worldPos);
        if (existing != null && CreateTrackUtil.isTrackBlock(existing)) {
            return;
        }

        ChunkSafeBlockAccess.setBlockStateNonBlocking(level, worldPos, baseBlock, true);
        protection.protect(worldPos);

        if (isConnectable(baseBlock)) {
            connectablePositions.add(worldPos.immutable());
        }
    }

    /**
     * Places the decking_end wall schematic (1x2 tall) at the given edge position.
     * Bottom block aligns at decking level (bottomY), top block at bottomY+1 (track level).
     */
    private void placeEndWall(ServerLevel level, LoadedSchematic endSchematic, int x, int bottomY, int z,
                               Rotation rotation, StationBlockProtection protection) {
        for (int y = 0; y < endSchematic.getHeight(); y++) {
            BlockState state = endSchematic.getBlock(0, y, 0);
            if (state.isAir()) continue;
            if (state.is(Blocks.STRUCTURE_VOID)) continue;

            BlockPos worldPos = new BlockPos(x, bottomY + y, z);
            long packed = worldPos.asLong();
            if (trackFootprint.contains(packed)) continue;
            // Skip top block (track level) near branch junctions to prevent overlap with diverging track
            if (y > 0 && junctionZone.contains(packed)) continue;

            // Allow overwriting terrain blocks - same rationale as placeBaseBlock.
            BlockState existing = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, worldPos);
            if (existing != null && CreateTrackUtil.isTrackBlock(existing)) {
                continue;
            }

            BlockState rotatedState = RotationHelper.rotateBlockState(level, worldPos, state, rotation);
            ChunkSafeBlockAccess.setBlockStateNonBlocking(level, worldPos, rotatedState, true);
            protection.protect(worldPos);

            if (isConnectable(rotatedState)) {
                connectablePositions.add(worldPos.immutable());
            }
        }
    }

    /**
     * Places pillar schematics stacking down to ground level.
     * For straight track with integer Y positions.
     */
    public void placePillarAt(ServerLevel level, BlockPos trackPos, Vec3 tangent) {
        init(level, trackPos);
        placePillarAtY(level, trackPos.getX(), trackPos.getY(), trackPos.getZ(), tangent);
    }

    /**
     * Places pillar schematics using a precise Vec3 position from a bezier segment.
     */
    public void placePillarAt(ServerLevel level, Vec3 worldPos, Vec3 tangent) {
        int trackY = Mth.floor(worldPos.y - slopeBias(tangent));
        BlockPos refPos = new BlockPos(Mth.floor(worldPos.x), trackY, Mth.floor(worldPos.z));
        init(level, refPos);
        placePillarAtY(level, Mth.floor(worldPos.x), trackY, Mth.floor(worldPos.z), tangent);
    }

    private void placePillarAtY(ServerLevel level, int x, int trackY, int z, Vec3 tangent) {
        // Keep piers out of the junction fork.
        if (isPillarExcluded(x, z)) return;
        placePillarStamp(level, x, trackY, z, tangent);
    }

    /**
     * Updates shape connections for all connectable blocks (walls, fences, panes, etc.)
     * placed during this bridge.
     */
    public void updateConnections(ServerLevel level) {
        for (BlockPos pos : connectablePositions) {
            BlockState current = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
            if (current == null) continue;

            // Let each neighbor direction update this block's shape
            BlockState updated = current;
            for (Direction dir : HORIZONTAL) {
                BlockPos neighborPos = pos.relative(dir);
                BlockState neighborState = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, neighborPos);
                if (neighborState == null) continue;
                updated = updated.updateShape(dir, neighborState, level, pos, neighborPos);
            }
            // Also update vertical connections (up/down)
            BlockPos above = pos.above();
            BlockState aboveState = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, above);
            if (aboveState != null) {
                updated = updated.updateShape(Direction.UP, aboveState, level, pos, above);
            }
            BlockPos below = pos.below();
            BlockState belowState = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, below);
            if (belowState != null) {
                updated = updated.updateShape(Direction.DOWN, belowState, level, pos, below);
            }

            if (updated != current) {
                ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, updated, true);
            }
        }
        connectablePositions.clear();
    }


    /**
     * Sets a single block, protecting it. Skips existing track. When
     * {@code overwriteSolid} is false, leaves existing solid terrain in place (used for the
     * proud lip/ledge so they don't carve into the bank).
     */
    private void setAbutmentBlock(ServerLevel level, BlockState state, int x, int y, int z,
                                   StationBlockProtection protection, boolean overwriteSolid) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState existing = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
        if (existing == null || CreateTrackUtil.isTrackBlock(existing)) return;
        if (!overwriteSolid) {
            boolean solid = !BlockTypeUtil.isAirOrLiquid(existing) && !existing.canBeReplaced()
                    && !BlockTypeUtil.isVegetation(existing);
            if (solid) return;
        }
        ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, state, true);
        protection.protect(pos);
    }

    // ---- Piers (NBT-driven stamp) ----

    /**
     * Stamps a pier from the pillar NBT ({@link #pierNbtId}). The NBT is read as a 3D piece rotated to
     * the track: <b>X = along-track depth</b>, <b>Y = vertical</b>, <b>Z = across the deck</b>. The
     * transverse (Z) profile is fitted to the deck width with the same edge-verbatim + centre-repeated
     * rule the deck uses, so the leg groups snap to the deck edges (and a solid centre column fills into
     * one wide "mono" pier). Per Y layer: <b>y0</b> is the leg - placed at the deck surface and extended
     * down to the ground (it passes through our own protected deck/fascia); <b>y1</b> sits at the railing
     * level and replaces the railing here; <b>y2+</b> are decorative, placed as-is above. No arches.
     */
    private boolean placePillarStamp(ServerLevel level, int x, int trackY, int z, Vec3 tangent) {
        // On ~45° segments use the diagonal pillar variant (a square footprint stamped + rotated).
        if (isDiagonalHeading(tangent)) {
            LoadedSchematic diag = loadSchematicQuiet(level, diagVariant(pierNbtId));
            if (diag != null) {
                placeDiagPillarStamp(level, diag, x, trackY, z, tangent);
                return true;
            }
            // else fall through to the cardinal pillar
        }
        LoadedSchematic pillar = loadSchematic(level, pierNbtId, "bridge pillar");
        if (pillar == null) return false;
        int depth  = pillar.getWidth();   // NBT X = along-track depth
        int height = pillar.getHeight();  // NBT Y = vertical
        int transW = pillar.getLength();  // NBT Z = across the deck
        if (depth < 1 || transW < 1) return false;

        StationBlockProtection protection = VillageTargetingSavedData.get(level).getStationBlockProtection();
        // Anchor to the deck that was actually placed (robust to slope bias in trackY).
        int deckSurfaceY = findPlacedDeckTop(level, protection, x, z, trackY);
        if (deckSurfaceY == Integer.MIN_VALUE) deckSurfaceY = trackY - 1;

        Direction trackDir = Direction.getNearest(tangent.x, 0.0, tangent.z);
        Rotation rot = rotationFromEast(trackDir);
        Vec3 perp = new Vec3(-tangent.z, 0, tangent.x).normalize();
        List<int[]> positions = computePerpendicularPositions(x, z, perp);
        int n = positions.size();
        if (n < 1) return false;

        // The y0 leg footprint defines the across-deck alignment: its outermost columns sit at the deck
        // edges (legs flush to the sides), and any column outside that span hangs just past the edge as
        // decoration (e.g. trapdoors on the outside). Without this the pads would pin the legs 1 block in.
        int zLo = transW, zHi = -1;
        for (int zc = 0; zc < transW; zc++) {
            for (int d = 0; d < depth; d++) {
                if (!pillar.getBlock(d, 0, zc).isAir()) { zLo = Math.min(zLo, zc); zHi = Math.max(zHi, zc); break; }
            }
        }
        if (zHi < 0) { zLo = 0; zHi = transW - 1; }   // no legs at y0: fall back to full width
        int legW = zHi - zLo + 1;
        int legEdgeCols = (legW - 1) / 2;

        // In-deck columns: the leg span fitted to the deck width (edge-verbatim + centre-repeated).
        for (int i = 0; i < n; i++) {
            int zcol = zLo + transverseColumn(i, n, legW, legEdgeCols);
            stampPillarColumn(level, pillar, zcol, depth, height, positions.get(i)[0], positions.get(i)[1],
                    deckSurfaceY, trackDir, rot, protection);
        }
        // Overhang columns (decoration outside the leg span): one step past each deck edge per column.
        int[] leftStep  = n > 1 ? new int[]{positions.get(0)[0] - positions.get(1)[0], positions.get(0)[1] - positions.get(1)[1]} : new int[]{0, 0};
        int[] rightStep = n > 1 ? new int[]{positions.get(n - 1)[0] - positions.get(n - 2)[0], positions.get(n - 1)[1] - positions.get(n - 2)[1]} : new int[]{0, 0};
        for (int zc = 0; zc < zLo; zc++) {
            int over = zLo - zc;
            stampPillarColumn(level, pillar, zc, depth, height,
                    positions.get(0)[0] + leftStep[0] * over, positions.get(0)[1] + leftStep[1] * over,
                    deckSurfaceY, trackDir, rot, protection);
        }
        for (int zc = zHi + 1; zc < transW; zc++) {
            int over = zc - zHi;
            stampPillarColumn(level, pillar, zc, depth, height,
                    positions.get(n - 1)[0] + rightStep[0] * over, positions.get(n - 1)[1] + rightStep[1] * over,
                    deckSurfaceY, trackDir, rot, protection);
        }
        VillageTargetingSavedData.get(level).setDirty();
        return true;
    }

    /** Stamps one transverse column of the pillar (its `depth` blocks along the track, all Y layers). */
    private void stampPillarColumn(ServerLevel level, LoadedSchematic pillar, int zcol, int depth, int height,
                                    int baseX, int baseZ, int deckSurfaceY, Direction trackDir, Rotation rot,
                                    StationBlockProtection protection) {
        for (int d = 0; d < depth; d++) {              // extend the pier `depth` blocks along the track
            int wx = baseX + trackDir.getStepX() * d;
            int wz = baseZ + trackDir.getStepZ() * d;
            for (int y = 0; y < height; y++) {
                BlockState state = pillar.getBlock(d, y, zcol);
                if (state.isAir()) continue;
                // The configured deck row maps to the deck surface; rows below it land under the deck,
                // rows above it sit above the deck.
                int wy = deckSurfaceY + (y - pillarDeckRow);
                BlockState rotated = RotationHelper.rotateBlockState(level, new BlockPos(wx, wy, wz), state, rot);
                if (y == 0) {
                    // Bottom row = the foot: fill from here down to the ground (passes through protected deck/fascia).
                    fillColumnToGround(level, rotated, wx, wy, wz, protection);
                } else {
                    // Everything above the foot is placed at its fixed height (under-deck braces, deck-level, decor).
                    setAbutmentBlock(level, rotated, wx, wy, wz, protection, true);
                }
            }
        }
    }

    /** Maps deck transverse position i (of n) to a column in a span of width `spanW`: edges verbatim, centre repeated. */
    private static int transverseColumn(int i, int n, int spanW, int edgeCols) {
        int fromRight = n - 1 - i;
        if (i < edgeCols && i <= fromRight) return i;
        if (fromRight < edgeCols) return spanW - 1 - fromRight;
        return edgeCols; // centre column, repeated across the interior
    }

    /**
     * Finds our own deck-surface Y at (x,z): the topmost protected, solid, non-track block in a small
     * window around {@code trackY}. Anchors piers to the deck as actually placed (the pillar's
     * slope-biased trackY drifts from the deck on inclines). {@link Integer#MIN_VALUE} if not found.
     */
    private int findPlacedDeckTop(ServerLevel level, StationBlockProtection protection, int x, int z, int trackY) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = trackY + 3; y >= trackY - 4; y--) {
            m.set(x, y, z);
            if (!protection.isProtected(m)) continue;
            BlockState s = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, m);
            if (s == null || s.isAir() || CreateTrackUtil.isTrackBlock(s)) continue;
            return y;
        }
        return Integer.MIN_VALUE;
    }


    /**
     * Fills a single column downward from startY with {@code fill}, through air/liquid/vegetation,
     * until it reaches solid terrain (left intact) or the build floor. Existing track/girder blocks
     * are skipped so an abutment never plugs a crossing line.
     *
     * @return the Y of the lowest block placed, or {@link Integer#MIN_VALUE} if none (immediate ground)
     */
    private int fillColumnToGround(ServerLevel level, BlockState fill, int x, int startY, int z,
                                    StationBlockProtection protection) {
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int scanned = 0;
        int lowest = Integer.MIN_VALUE;
        for (int y = startY; y >= minY && scanned < 256; y--, scanned++) {
            m.set(x, y, z);
            BlockState existing = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, m);
            if (existing == null) break;
            if (CreateTrackUtil.isTrackBlock(existing) || CreateTrackUtil.isGirderBlock(existing)) {
                continue; // don't plug a crossing track/girder; keep descending
            }
            // Our own deck/fascia/decking sits just under the track edge - pass through it rather
            // than mistaking it for ground (otherwise an edge leg stops dead at the under-deck girder).
            if (protection.isProtected(m)) continue;
            boolean isGround = !BlockTypeUtil.isAirOrLiquid(existing) && !existing.canBeReplaced()
                    && !BlockTypeUtil.isVegetation(existing);
            if (isGround) break;
            BlockPos p = m.immutable();
            ChunkSafeBlockAccess.setBlockStateNonBlocking(level, p, fill, true);
            protection.protect(p);
            lowest = y;
        }
        return lowest;
    }

    /**
     * Checks if a block state is a connectable type (wall, fence, pane, etc.)
     * that needs neighbor-aware shape updates.
     */
    private static boolean isConnectable(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.WallBlock
            || state.getBlock() instanceof net.minecraft.world.level.block.FenceBlock
            || state.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock;
    }

}
