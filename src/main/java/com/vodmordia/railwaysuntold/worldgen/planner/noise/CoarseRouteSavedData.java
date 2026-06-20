package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.*;

/**
 * Persists coarse route data across world saves/loads.
 */
public class CoarseRouteSavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "railwaysuntold_coarse_routes";

    private static final String KEY_ROUTES = "Routes";
    private static final String KEY_HEAD_ID = "HeadId";
    private static final String KEY_WAYPOINTS = "Waypoints";
    private static final String KEY_X = "X";
    private static final String KEY_Y = "Y";
    private static final String KEY_Z = "Z";
    private static final String KEY_ADVISED_Y = "AdvisedY";
    private static final String KEY_TYPE = "Type";
    private static final String KEY_Y_BASIS = "YBasis";

    private final Map<UUID, CoarseRoute> routes = new HashMap<>();

    public CoarseRouteSavedData() {
    }

    public static CoarseRouteSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(
                        CoarseRouteSavedData::new,
                        CoarseRouteSavedData::load
                ),
                DATA_NAME
        );
    }

    public void addRoute(UUID headId, CoarseRoute route) {
        routes.put(headId, route);
        setDirty();
    }

    public void removeRoute(UUID headId) {
        if (routes.remove(headId) != null) {
            setDirty();
        }
    }

    public void loadIntoRegistry(CoarseRouteRegistry registry, ServerLevel level) {
        for (Map.Entry<UUID, CoarseRoute> entry : routes.entrySet()) {
            registry.registerRoute(entry.getKey(), entry.getValue(), level);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag routesList = new ListTag();

        for (Map.Entry<UUID, CoarseRoute> entry : routes.entrySet()) {
            CompoundTag routeTag = new CompoundTag();
            routeTag.putUUID(KEY_HEAD_ID, entry.getKey());

            CoarseRoute route = entry.getValue();

            ListTag waypointsList = new ListTag();
            for (CoarseWaypoint wp : route.getWaypoints()) {
                CompoundTag wpTag = new CompoundTag();
                wpTag.putInt(KEY_X, wp.position().getX());
                wpTag.putInt(KEY_Y, wp.position().getY());
                wpTag.putInt(KEY_Z, wp.position().getZ());
                wpTag.putInt(KEY_ADVISED_Y, wp.advisedTrackY());
                wpTag.putByte(KEY_TYPE, (byte) wp.type().ordinal());
                wpTag.putByte(KEY_Y_BASIS, (byte) wp.yBasis().ordinal());
                waypointsList.add(wpTag);
            }
            routeTag.put(KEY_WAYPOINTS, waypointsList);

            routesList.add(routeTag);
        }

        tag.put(KEY_ROUTES, routesList);
        LOGGER.debug("[COARSE-SAVED] Saved {} coarse routes", routes.size());
        return tag;
    }

    public static CoarseRouteSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        CoarseRouteSavedData savedData = new CoarseRouteSavedData();

        if (tag.contains(KEY_ROUTES, Tag.TAG_LIST)) {
            ListTag routesList = tag.getList(KEY_ROUTES, Tag.TAG_COMPOUND);
            for (int i = 0; i < routesList.size(); i++) {
                CompoundTag routeTag = routesList.getCompound(i);
                if (!routeTag.hasUUID(KEY_HEAD_ID)) continue;

                UUID headId = routeTag.getUUID(KEY_HEAD_ID);

                List<CoarseWaypoint> waypoints = new ArrayList<>();
                if (routeTag.contains(KEY_WAYPOINTS, Tag.TAG_LIST)) {
                    ListTag wpList = routeTag.getList(KEY_WAYPOINTS, Tag.TAG_COMPOUND);
                    for (int j = 0; j < wpList.size(); j++) {
                        CompoundTag wpTag = wpList.getCompound(j);
                        BlockPos pos = new BlockPos(
                                wpTag.getInt(KEY_X),
                                wpTag.getInt(KEY_Y),
                                wpTag.getInt(KEY_Z));
                        int advisedY = wpTag.getInt(KEY_ADVISED_Y);
                        byte typeOrd = wpTag.getByte(KEY_TYPE);
                        WaypointType type = typeOrd >= 0 && typeOrd < WaypointType.values().length
                                ? WaypointType.values()[typeOrd]
                                : WaypointType.TERRAIN_FOLLOW;

                        byte basisOrd = wpTag.getByte(KEY_Y_BASIS);
                        CoarseRoute.YBasis yBasis = basisOrd >= 0 && basisOrd < CoarseRoute.YBasis.values().length
                                ? CoarseRoute.YBasis.values()[basisOrd]
                                : CoarseRoute.YBasis.PREFERENCE;

                        waypoints.add(new CoarseWaypoint(pos, advisedY, type, yBasis));
                    }
                }

                if (waypoints.isEmpty()) continue;

                CoarseRoute route = new CoarseRoute(headId, waypoints);

                savedData.routes.put(headId, route);
            }
        }

        return savedData;
    }
}
