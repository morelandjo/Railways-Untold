package com.vodmordia.railwaysuntold.worldgen.head.state;

/**
 * Tracks curve-related state for a track expansion head.
 */
public class CurveTrackingState {

    public enum CurveDirection {
        NONE(0), LEFT(1), RIGHT(2);

        private final int value;

        CurveDirection(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static CurveDirection fromValue(int value) {
            return switch (value) {
                case 1 -> LEFT;
                case 2 -> RIGHT;
                default -> NONE;
            };
        }

        public static CurveDirection fromBoolean(boolean turnedLeft) {
            return turnedLeft ? LEFT : RIGHT;
        }
    }

    private int blocksSinceLastCurve;
    private int blocksSinceLastLateral;
    private CurveDirection previousCurveDirection;
    private CurveDirection priorCurveDirection;
    private boolean lastPlacementWasEmergencyCurve;

    public CurveTrackingState() {
        this.blocksSinceLastCurve = 0;
        this.blocksSinceLastLateral = 0;
        this.previousCurveDirection = CurveDirection.NONE;
        this.priorCurveDirection = CurveDirection.NONE;
        this.lastPlacementWasEmergencyCurve = false;
    }

    public int getBlocksSinceLastCurve() {
        return blocksSinceLastCurve;
    }

    public void incrementBlocksSinceLastCurve(int blocks) {
        this.blocksSinceLastCurve += blocks;
    }

    public void resetBlocksSinceLastCurve() {
        this.blocksSinceLastCurve = 0;
    }

    public void incrementBlocksSinceLastLateral(int blocks) {
        this.blocksSinceLastLateral += blocks;
    }

    public void resetBlocksSinceLastLateral() {
        this.blocksSinceLastLateral = 0;
    }

    public CurveDirection getPreviousCurveDirection() {
        return previousCurveDirection;
    }

    public CurveDirection getPriorCurveDirection() {
        return priorCurveDirection;
    }

    public void recordCurveTurn(boolean turnedLeft) {
        priorCurveDirection = previousCurveDirection;
        previousCurveDirection = CurveDirection.fromBoolean(turnedLeft);
    }

    public boolean wasLastPlacementEmergencyCurve() {
        return lastPlacementWasEmergencyCurve;
    }

    public void setLastPlacementWasEmergencyCurve(boolean wasEmergency) {
        this.lastPlacementWasEmergencyCurve = wasEmergency;
    }

    public void restore(int blocksSinceCurve, int curveDir1, int curveDir2, boolean wasEmergency) {
        this.blocksSinceLastCurve = blocksSinceCurve;
        this.previousCurveDirection = CurveDirection.fromValue(curveDir1);
        this.priorCurveDirection = CurveDirection.fromValue(curveDir2);
        this.lastPlacementWasEmergencyCurve = wasEmergency;
    }
}
