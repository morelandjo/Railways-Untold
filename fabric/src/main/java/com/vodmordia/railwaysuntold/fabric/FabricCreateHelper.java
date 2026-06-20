package com.vodmordia.railwaysuntold.fabric;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.platform.AbstractCreateHelper;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Fabric implementation of CreateHelper.
 * Uses shared reflection logic from AbstractCreateHelper with Fabric-specific overrides.
 */
public class FabricCreateHelper extends AbstractCreateHelper {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Cached networking references for Fabric
    private static volatile Object allPacketsChannel = null;
    private static volatile Method fabricSendMethod = null;
    private static volatile boolean networkingInitialized = false;
    private static final Object NETWORK_LOCK = new Object();

    public FabricCreateHelper() {
        initializeCreateIntegration();
    }

    @Override
    protected String getPlatformName() {
        return "Fabric";
    }

    @Override
    protected String getCoupleClassName() {
        // Create 6.0+ moved Couple to the catnip library (same as Forge)
        return "net.createmod.catnip.data.Couple";
    }

    @Override
    protected String getBogeyStyleIdFieldName() {
        // Fabric uses "name" field for BogeyStyle ID
        return "name";
    }

    @Override
    public boolean sendTrainPacketToAllClients(Object packet) {
        initializeNetworking();

        if (fabricSendMethod == null || allPacketsChannel == null) {
            LOGGER.warn("[RailwaysUntold] Fabric networking not initialized");
            return false;
        }

        try {
            fabricSendMethod.invoke(allPacketsChannel, packet);
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
                    // Fabric uses sendToClientsInCurrentServer(packet)
                    for (Method method : allPacketsChannel.getClass().getMethods()) {
                        if (method.getName().equals("sendToClientsInCurrentServer") && method.getParameterCount() == 1) {
                            fabricSendMethod = method;
                            LOGGER.debug("[RailwaysUntold] Found Fabric networking method: sendToClientsInCurrentServer");
                            break;
                        }
                    }
                }

                if (fabricSendMethod == null) {
                    LOGGER.warn("[RailwaysUntold] Could not find Fabric sendToClientsInCurrentServer method");
                }
            } catch (Exception e) {
                LOGGER.error("[RailwaysUntold] Failed to initialize Fabric networking: {}", e.getMessage());
            }

            networkingInitialized = true;
        }
    }
}
