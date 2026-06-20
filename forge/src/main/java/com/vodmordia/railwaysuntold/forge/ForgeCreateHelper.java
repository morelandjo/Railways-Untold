package com.vodmordia.railwaysuntold.forge;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.platform.AbstractCreateHelper;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Forge implementation of CreateHelper.
 * Uses shared reflection logic from AbstractCreateHelper with Forge-specific overrides.
 */
public class ForgeCreateHelper extends AbstractCreateHelper {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Cached networking references for Forge
    private static volatile Object allPacketsChannel = null;
    private static volatile Method forgeSendMethod = null;
    private static volatile Object packetDistributorAll = null;
    private static volatile boolean networkingInitialized = false;
    private static final Object NETWORK_LOCK = new Object();

    public ForgeCreateHelper() {
        initializeCreateIntegration();
    }

    @Override
    protected String getPlatformName() {
        return "Forge";
    }

    @Override
    protected String getCoupleClassName() {
        // Forge uses Catnip library's Couple class
        return "net.createmod.catnip.data.Couple";
    }

    @Override
    protected String getBogeyStyleIdFieldName() {
        // Forge uses "id" field for BogeyStyle ID
        return "id";
    }

    @Override
    public boolean sendTrainPacketToAllClients(Object packet) {
        initializeNetworking();

        if (forgeSendMethod == null || allPacketsChannel == null || packetDistributorAll == null) {
            LOGGER.warn("[RailwaysUntold] Forge networking not initialized");
            return false;
        }

        try {
            forgeSendMethod.invoke(allPacketsChannel, packetDistributorAll, packet);
            return true;
        } catch (Exception e) {
            LOGGER.error("[RailwaysUntold] Failed to send train packet: {}", e.getMessage());
            return false;
        }
    }

    private void initializeNetworking() {
        if (networkingInitialized) return;

        synchronized (NETWORK_LOCK) {
            if (networkingInitialized) return;

            try {
                // Get AllPackets.getChannel()
                Class<?> allPacketsClass = Class.forName("com.simibubi.create.AllPackets");
                Method getChannelMethod = allPacketsClass.getMethod("getChannel");
                allPacketsChannel = getChannelMethod.invoke(null);

                if (allPacketsChannel != null) {
                    // Forge uses send(distributor, packet)
                    for (Method method : allPacketsChannel.getClass().getMethods()) {
                        if (method.getName().equals("send") && method.getParameterCount() == 2) {
                            forgeSendMethod = method;
                            LOGGER.debug("[RailwaysUntold] Found Forge networking method: send");
                            break;
                        }
                    }
                }

                // Get PacketDistributor.ALL.noArg()
                if (forgeSendMethod != null) {
                    try {
                        Class<?> packetDistributorClass = Class.forName("net.minecraftforge.network.PacketDistributor");
                        Field allField = packetDistributorClass.getField("ALL");
                        Object allDistributor = allField.get(null);
                        Method noArgMethod = allDistributor.getClass().getMethod("noArg");
                        packetDistributorAll = noArgMethod.invoke(allDistributor);
                        LOGGER.debug("[RailwaysUntold] Found Forge PacketDistributor.ALL");
                    } catch (Exception e) {
                        LOGGER.error("[RailwaysUntold] Failed to get PacketDistributor.ALL: {}", e.getMessage());
                    }
                }

                if (forgeSendMethod == null || packetDistributorAll == null) {
                    LOGGER.warn("[RailwaysUntold] Could not fully initialize Forge networking");
                }
            } catch (Exception e) {
                LOGGER.error("[RailwaysUntold] Failed to initialize Forge networking: {}", e.getMessage());
            }

            networkingInitialized = true;
        }
    }
}
