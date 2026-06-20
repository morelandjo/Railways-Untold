package com.vodmordia.railwaysuntold.worldgen.placement.support;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.core.TrackLogger;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportProfileRecord.Band;
import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportProfileRecord.Decision;
import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportProfileRecord.Reason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures the committed support outcome for a single track block as a machine-parseable
 * {@code [SUPPORT]} record: the block position, what was placed there (bridge decking, terrain
 * fill, or nothing), the reason, and the column scanned below the track. Emitted via the same
 * direct SLF4J channel and verbose gate as the {@code [REPLAY]} route record, so a placement-time
 * decision against real world voxels (caves, ravines, overhangs) becomes reproducible data.
 */
public final class SupportDecisionLogger {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SupportDecisionLogger() {
    }

    public static void capture(ServerLevel level, BlockPos trackPos, Decision decision) {
        capture(level, trackPos, decision, Reason.UNSPECIFIED);
    }

    public static void capture(ServerLevel level, BlockPos trackPos, Decision decision, Reason reason) {
        if (!TrackLogger.isVerboseEnabled()) {
            return;
        }
        List<Band> bands = scanColumn(level, trackPos);
        Reason resolved = (reason == Reason.UNSPECIFIED && decision == Decision.BRIDGE_DECKING)
                ? inferBridgeReason(bands) : reason;
        SupportProfileRecord rec = new SupportProfileRecord(
                trackPos.getX(), trackPos.getY(), trackPos.getZ(), decision, resolved, bands);
        LOGGER.info("[SUPPORT] {} decision={} reason={}\n{}",
                trackPos.toShortString(), decision.name(), resolved.token(), rec.serialize());
    }

    /**
     * Scans the column directly below the track, top-down from trackY-1, classifying each block as
     * solid (S), air or replaceable (A), or fluid (F) and run-length encoding the result. A block
     * counts as non-solid using the same predicates the support detector treats as passthrough.
     * Stops at the first unloaded (null) block or after {@link SupportProfileRecord#MAX_PROFILE_DEPTH}.
     */
    private static List<Band> scanColumn(ServerLevel level, BlockPos trackPos) {
        List<Band> bands = new ArrayList<>();
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos(
                trackPos.getX(), trackPos.getY() - 1, trackPos.getZ());
        char runKind = 0;
        int runLen = 0;
        for (int i = 0; i < SupportProfileRecord.MAX_PROFILE_DEPTH; i++) {
            BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, check);
            if (state == null) {
                break;
            }
            char kind = classify(state);
            if (kind == runKind) {
                runLen++;
            } else {
                if (runLen > 0) {
                    bands.add(new Band(runKind, runLen));
                }
                runKind = kind;
                runLen = 1;
            }
            check.setY(check.getY() - 1);
        }
        if (runLen > 0) {
            bands.add(new Band(runKind, runLen));
        }
        return bands;
    }

    private static char classify(BlockState state) {
        if (BlockTypeUtil.isAnyFluid(state)) {
            return 'F';
        }
        if (state.isAir() || state.canBeReplaced() || BlockTypeUtil.isAirOrLiquid(state)
                || BlockTypeUtil.isTreeOrWood(state)
                || CreateTrackUtil.isTrackBlock(state)
                || CreateTrackUtil.isGirderBlock(state)) {
            return 'A';
        }
        return 'S';
    }

    /** The first non-solid band beneath the track tells whether the bridge spans fluid or open air. */
    private static Reason inferBridgeReason(List<Band> bands) {
        for (Band b : bands) {
            if (b.kind() == 'F') {
                return Reason.FLUID;
            }
            if (b.kind() == 'A') {
                return Reason.ELEVATED;
            }
        }
        return Reason.UNSPECIFIED;
    }
}
