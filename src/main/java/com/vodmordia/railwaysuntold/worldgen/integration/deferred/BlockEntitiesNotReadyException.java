package com.vodmordia.railwaysuntold.worldgen.integration.deferred;

/**
 * Exception thrown when block entities are not yet ready for operations.
 */
public class BlockEntitiesNotReadyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new BlockEntitiesNotReadyException with a custom message.
     *
     * @param message Detailed message about why block entities aren't ready
     */
    public BlockEntitiesNotReadyException(String message) {
        super(message);
    }

    /**
     * Creates a new BlockEntitiesNotReadyException with position information.
     *
     * @param position Description of which position(s) have unready block entities
     */
    public static BlockEntitiesNotReadyException atPosition(String position) {
        return new BlockEntitiesNotReadyException("Block entities not ready at: " + position);
    }
}
