# Game configuration

Profiles are stored using the existing DataStore and per-game private directories;
there is no parallel database. SAF copies selected files, preserving safe relative
paths. Originals remain unchanged.

Configure name, EXE, working directory, argument array, environment, Wine prefix,
runtime variant and graphics API in Details/Settings. Runtime selection validates
the PE machine; x86 WoW64 requires the separate experimental package. Prefixes are
reused, not re-created each Play. Never silently repurpose an x64 user's prefix as
an experimental WoW64 prefix.

Audio and controller mappings are per profile. Keyboard/mouse mapping is distinct
from XInput and depends on the application's actual input API. Camera sensitivity
is persisted. Environment values are validated; arguments are not concatenated
into shell commands. Restart-required runtime changes apply on the next Play.
Future graphics/display switches must not imply unsupported backends exist.

[Launcher user guide](LAUNCHER_USER_GUIDE.md) · [controller support](CONTROLLER_SUPPORT.md).
