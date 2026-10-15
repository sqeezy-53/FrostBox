# Measurements and future game benchmark protocol

No present source or Windows runtime is connected. Do not benchmark launcher recompositions as game FPS.
Later: same game/build/scene/resolution, fixed display mode, fixed brightness, recorded ambient conditions,
charging state and initial thermal status. Record cold/warm shader-cache cases separately. Run ≥20 minutes.
Store monotonic present deltas, applied cap, status/headroom, battery temperature and charge trend.
Report sample count, average FPS, p95/p99 frametime, slowest-1%-mean FPS and long frames (>1.5x target).
Battery drain is not an accurate instantaneous SoC power meter. Do not report watts without a calibrated source.

`run-host-wine.py` is an executable **host smoke measurement**, not a gaming benchmark. It records
platform, whole-run elapsed time and JSON stage results for wineboot/x64 PE/x86 PE/shutdown. Peak RSS
and CPU time belong to direct children, not the entire Wine process tree. Cold prefix setup dominates
whole-run time. See `docs/NATIVE_RUNTIME.md` for commands; Android/Box64 performance is unknown.
