package com.vodmordia.railwaysuntold.worldgen.train;


/**
 * NBT key constants for contraption/train serialization.
 */
public final class ContraptionNbtKeys {

    private ContraptionNbtKeys() {
    }

    // Contraption root keys
    public static final String ASSEMBLY_DIRECTION = "AssemblyDirection";
    public static final String FRONT_CONTROLS = "FrontControls";
    public static final String BACK_CONTROLS = "BackControls";
    public static final String BLOCKS = "Blocks";
    public static final String SEATS = "Seats";
    public static final String ACTORS = "Actors";
    public static final String INTERACTORS = "Interactors";
    public static final String CONDUCTOR_SEATS = "ConductorSeats";
    public static final String BOUNDS_FRONT = "BoundsFront";
    public static final String ANCHOR = "Anchor";
    public static final String CONTRAPTION = "Contraption";

    // Block palette keys
    public static final String PALETTE = "Palette";
    public static final String BLOCK_LIST = "BlockList";
    public static final String POS = "Pos";
    public static final String STATE = "State";
    public static final String DATA = "Data";
    public static final String NAME = "Name";

    // Bogey keys
    public static final String BOGEY_DATA = "BogeyData";
    public static final String BOGEY_STYLE = "BogeyStyle";
    public static final String UPSIDE_DOWN = "UpsideDown";

    // Entity keys
    public static final String ENTITIES = "entities";
    public static final String NBT = "nbt";
    public static final String ID = "id";

    // Glue entity keys
    public static final String FROM = "From";
    public static final String TO = "To";

    // Entity position keys
    public static final String ENTITY_POS = "pos";
    public static final String BLOCK_POS = "blockPos";
}
