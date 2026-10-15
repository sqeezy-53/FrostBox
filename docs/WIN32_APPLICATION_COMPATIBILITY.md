# FrostBox 0.4.0-dev.2 — Win32 application compatibility

Committed Unicode input in the standard single/multiline Edit is now qualified on POCO.
This remains an experimental software GDI milestone, not general application compatibility.
Existing console/software GDI architecture, base Wine ZIP and prefixes are retained.

## Independent application

`tests/windows/compat-lab.c` builds a real PE x64 with standard user32 controls and message loop:
Button, single/multiline Edit, automatic Checkbox, Static, ListBox, Clear and click counter.
Its own Win32 API definitions are in win32-minimal.h. Build: scripts/build-compat-lab.sh.
No Android-specific execution/drawing/input logic inside the EXE; it can run under ordinary Windows.
Static strings include Hello Windows, Привет из FrostBox, Windows на Android, ё Ё № €.
Controls expose genuine WM_COMMAND state changes to stdout for assertions, not fake input results.

Library import uses the existing ACTION_OPEN_DOCUMENT/content URI/copy/profile flow, followed by
its actual Play button, Wine prefix, isolated runtime and WindowsScreen. Device harness substitutes
the SAF picker result with the read-only debug provider; it does not replace import or launch.
Manual system picker/physical interaction remain unqualified. The EXE is distributed separately
so it can be imported normally without instrumentation.

Initial POCO screenshots confirmed standard control rendering and Button/Checkbox state changes.
Unicode special-symbol static rendering was visually inspected, not a general Unicode proof.
The original seven text attempts failed; the recovered input path now verifies GetWindowTextW,
EN_CHANGE, WM_CHAR and actual Surface pixels. See WIN32_TEXT_INPUT_RECOVERY.md for the new
final regression results/artifact hashes; GUI_RUNTIME_STABILITY.md preserves the earlier results.

Win32 APIs exercised: RegisterClassExW/CreateWindowExW, ShowWindow/UpdateWindow,
GetMessage/TranslateMessage/DispatchMessage/DefWindowProc, SendMessage, SetWindowText,
WM_COMMAND/WM_CLOSE/WM_DESTROY, standard child controls and GDI fonts. ListBox items render;
selection/navigation/combobox, resizing/minimize/restore and arbitrary multiwindow are unqualified.
External application testing was deferred in favor of the blocking text path.
