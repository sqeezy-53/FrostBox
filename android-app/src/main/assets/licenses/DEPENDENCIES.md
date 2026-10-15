# FrostBox binary package notices

FrostBox original code: MIT (FrostBox-MIT.txt).
Kotlin, AndroidX, Compose, coroutines: Apache-2.0 (Apache-2.0.txt).
NDK runtime notices/exceptions: NDK-NOTICE.txt.
Optional Box64 0.4.4: MIT; complete notice/provenance in runtime/box64.json and bundled Box64 license.
Optional Wine 11.19 console probe: LGPL-2.1-or-later and per-file licenses, with two local patches.
LGPL text: LGPL-2.1.txt; Wine authors/notices are also in the bundled wine-probe.zip.
Corresponding source: FrostBox-Wine-11.19-upstream-source.tar.gz plus third-party/patches
and scripts/build-wine-bionic.sh in the accompanying FrostBox source package.
Rebuild/replace guest Wine using docs/WINE_BIONIC.md; rebuild/reinstall the APK with your own key.
The APK containing Wine is not entirely MIT. No proprietary game/DLL/GPU driver is bundled.
The complete dependency review and exact component lock are in third-party/README.md and runtime-lock.json.

Optional experimental Windows Display: runtime/display/winefrost.drv is LGPL-2.1-or-later,
adapted from Wine's surface/device/event patterns (upstream authors retained in source).
Display explorer patch also remains LGPL. Corresponding source includes the pinned Wine archive,
runtime/display/, and scripts/build-display-driver.sh. The Android transport and own GUI fixture
are FrostBox MIT. Schema-2 Display builds include FreeType 2.14.3 under the FreeType License (FTL),
with LICENSE.TXT and FTL.TXT retained in display/licenses; source/build recipe in
scripts/build-display-freetype.py and the accompanying upstream source archive.
Portions of this software are copyright © 2026 The FreeType Project (www.freetype.org).
All rights reserved. Optional Wine fonts retain their notices in Wine-fonts-NOTICE.txt.
No X11/Wayland server or proprietary font is bundled. Current font candidate is not built.
