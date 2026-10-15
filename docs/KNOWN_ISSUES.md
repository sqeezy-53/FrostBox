# Known issues

- Runtime storage: full overlay copies plus retained diagnostic environments caused
  74.61GiB of app data. Safe sharing/lifecycle work must not delete games or prefixes.
- Mafia II: one physical menu/controller/music success; repeated intro startup not
  reliable. No five-run qualification, level, character control or gameplay FPS.
- KARLSON: Tutorial works on the tested device; Sandbox/scene transitions can fail
  with `VK_ERROR_DEVICE_LOST`. Multithreaded Unity graphics has prior crash evidence;
  retain the known `-force-gfx-direct` fallback, do not call the issue solved.
- Display scaling / in-game panel: existing gameplay screen is present, complete
  Fit/Fill/Stretch persistence and Surface-safe panel behavior remain work in progress.
- Full GPU clean-clone build/release automation and binary license/source bundle
  correspondence are incomplete. Shell CI is not a full runtime qualification.
- Other devices, drivers, many Vulkan extensions and Windows applications are untested.

Use real Issues for acceptance criteria. Do not infer gameplay from fixture success.
