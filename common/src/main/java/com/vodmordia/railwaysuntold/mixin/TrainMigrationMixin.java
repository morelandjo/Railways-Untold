package com.vodmordia.railwaysuntold.mixin;

import com.simibubi.create.content.trains.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;

/**
 * Prevents NPE crash in Train.detachFromTracks when a TravellingPoint
 * has null node1, node2, or edge. This can happen when TrackPropagator.onRailAdded
 * triggers TrackGraph.removeNode on a train with already-detached travel points.
 *
 * For null points, a migration entry is cloned from a valid sibling point on the
 * same bogey (or the nearest valid point on the train). This keeps the migration
 * list in sync with forEachTravellingPoint order, allowing the train to reattach.
 *
 * <p>This fully replaces Create's {@link Train#detachFromTracks()} via {@link Overwrite}
 * (rather than an @Inject that always cancels) so the replacement is explicit and Mixin
 * tooling flags a conflict if the target signature changes in a future Create version.
 * Revisit this on Create updates.
 */
@Mixin(value = Train.class, remap = false)
public class TrainMigrationMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("RailwaysUntold/TrainMigrationMixin");

    @Shadow
    List<TrainMigration> migratingPoints;

    /**
     * @author RailwaysUntold
     * @reason Replace detach with a null-tolerant migration build so a TravellingPoint with
     *         null node1/node2/edge cannot NPE the detach path.
     */
    @Overwrite
    public void detachFromTracks() {
        Train self = (Train) (Object) this;
        migratingPoints.clear();
        self.navigation.cancelNavigation();

        // Collect all points in order, tracking which are valid
        List<TravellingPoint> allPoints = new ArrayList<>();
        List<TrainMigration> migrations = new ArrayList<>();

        // First pass: collect points and create migrations for valid ones
        for (Carriage c : self.carriages) {
            collectBogeyPoints(c.leadingBogey(), allPoints);
            if (c.isOnTwoBogeys()) {
                collectBogeyPoints(c.trailingBogey(), allPoints);
            }
        }

        // Second pass: create migrations, using neighbor recovery for null points
        for (TravellingPoint tp : allPoints) {
            if (isValid(tp)) {
                migrations.add(new TrainMigration(tp));
            } else {
                // Placeholder - will be filled in recovery pass
                migrations.add(null);
            }
        }

        // Recovery pass: fill nulls with nearest valid migration
        // Forward pass first
        TrainMigration fill = null;
        for (int i = 0; i < migrations.size(); i++) {
            if (migrations.get(i) != null) {
                fill = migrations.get(i);
            } else if (fill != null) {
                migrations.set(i, fill);
            }
        }
        // Backward pass for any remaining nulls at the start
        fill = null;
        for (int i = migrations.size() - 1; i >= 0; i--) {
            if (migrations.get(i) != null) {
                fill = migrations.get(i);
            } else if (fill != null) {
                migrations.set(i, fill);
            }
        }

        int recoveredCount = 0;
        for (int i = 0; i < allPoints.size(); i++) {
            if (!isValid(allPoints.get(i)) && migrations.get(i) != null) {
                recoveredCount++;
            }
        }

        if (recoveredCount > 0) {
            LOGGER.warn("[TRAIN-MIGRATION] Train '{}' had {} travel point(s) with null nodes during detach - "
                    + "recovered using neighbor migration data", self.name.getString(), recoveredCount);
        }

        // Check if any are still null (all points on the train were invalid)
        boolean anyNull = false;
        for (TrainMigration m : migrations) {
            if (m == null) {
                anyNull = true;
                break;
            }
        }

        if (anyNull) {
            LOGGER.error("[TRAIN-MIGRATION] Train '{}' has ALL travel points with null nodes - "
                    + "train cannot be migrated and will be derailed", self.name.getString());
            // Add empty migrations so the list stays in sync - train will fail to reattach
            // and enter derailed state naturally
            for (int i = 0; i < migrations.size(); i++) {
                if (migrations.get(i) == null) {
                    migrations.set(i, new TrainMigration());
                }
            }
        }

        migratingPoints.addAll(migrations);
        self.graph = null;
    }

    private static void collectBogeyPoints(CarriageBogey bogey, List<TravellingPoint> out) {
        out.add(bogey.leading());
        out.add(bogey.trailing());
    }

    private static boolean isValid(TravellingPoint tp) {
        return tp.node1 != null && tp.node2 != null && tp.edge != null;
    }
}
