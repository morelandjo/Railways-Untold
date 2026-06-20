package com.vodmordia.railwaysuntold.worldgen.integration.deferred;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterizes {@link DeferredProcessingManager}'s world-skip predicates - the filters that keep a pending
 * item from being applied to the wrong dimension or a stale (reloaded) world. Pinned with mocked levels.
 *
 * 1.21.1-only: mocking the heavyweight {@code ServerLevel} fails on the 1.20.1 Architectury common test
 * toolchain (Mockito InternalError), and the predicates are byte-identical across branches, so parity is by
 * inspection. The pure throttle is covered on both branches by DeferredProcessingThrottleTest.
 */
class DeferredProcessingWorldFilterTest {

    private static final class Probe extends DeferredProcessingManager {
        @Override
        protected int getProcessIntervalTicks() {
            return 1;
        }

        boolean stale(ServerLevel pending, ServerLevel current) {
            return isStaleLevel(pending, current);
        }

        boolean sameDim(ServerLevel a, ServerLevel b) {
            return isSameDimension(a, b);
        }

        boolean skip(ServerLevel pending, ServerLevel current) {
            return shouldSkipForWorld(pending, current);
        }
    }

    private static ServerLevel level(MinecraftServer server, String dimPath) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        ResourceKey<Level> dim = ResourceKey.create(
                Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("minecraft", dimPath));
        when(level.dimension()).thenReturn(dim);
        return level;
    }

    @Test
    void isStaleLevelComparesServerIdentity() {
        Probe probe = new Probe();
        MinecraftServer serverA = mock(MinecraftServer.class);
        MinecraftServer serverB = mock(MinecraftServer.class);

        assertFalse(probe.stale(level(serverA, "overworld"), level(serverA, "overworld")),
                "same server -> not stale");
        assertTrue(probe.stale(level(serverA, "overworld"), level(serverB, "overworld")),
                "a pending item from a different server is stale");
    }

    @Test
    void isSameDimensionComparesTheDimensionLocation() {
        Probe probe = new Probe();
        MinecraftServer server = mock(MinecraftServer.class);

        assertTrue(probe.sameDim(level(server, "overworld"), level(server, "overworld")));
        assertFalse(probe.sameDim(level(server, "overworld"), level(server, "the_nether")));
    }

    @Test
    void shouldSkipForWorldSkipsWrongDimensionOrStaleWorld() {
        Probe probe = new Probe();
        MinecraftServer server = mock(MinecraftServer.class);
        MinecraftServer otherServer = mock(MinecraftServer.class);

        assertFalse(probe.skip(level(server, "overworld"), level(server, "overworld")),
                "same dimension and same server -> process");
        assertTrue(probe.skip(level(server, "the_nether"), level(server, "overworld")),
                "wrong dimension -> skip");
        assertTrue(probe.skip(level(otherServer, "overworld"), level(server, "overworld")),
                "stale world (different server) -> skip");
    }
}
