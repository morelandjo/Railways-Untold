# Compile replay cases

Drop a `<name>.compile` file here to turn an in-game track-geometry bug into a
deterministic regression test. `CompileCaseTest` parses each `.compile`, re-runs
`PrecisionRouteCompiler.compile` on the exact captured waypoints (no world, no
terrain), pins the compiled segments against `<name>.txt`, and asserts the route
is well-formed: no disconnected segment seam, no `>=90°` kink, no sub-minimum-radius
curve.

To capture one:

1. Run with `verboseLogging = true`. Each compile emits a `[COMPILE]` summary line
   followed by an unprefixed `COMPILE v1` record (start, target, headDir,
   isFromTrackTip, and the full waypoint list).
2. Paste the log block around the reported route into `<name>.compile` here.
   Surrounding log noise is fine — only the `COMPILE v1` → `waypoints` lines are kept.
3. Run the tests; the `.txt` golden is generated on first run. If the captured
   route reproduces a disconnect, the well-formedness assertions fail — that is the
   bug surfacing, not a golden to freeze.

Pair with the `[PLACE-SEG]` log lines (planned start vs live tip per segment) to tell
a compile-time disconnect from a placement-time drift: if the captured waypoints
compile clean here, the disconnect happened during placement, and `[PLACE-SEG]` shows
where the tip drifted.
