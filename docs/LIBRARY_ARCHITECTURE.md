# Library / Launcher contract (0.3.0-dev.2)

Compose → FrostModel → ProfileRepository / LibraryLauncher → WineRuntimeClient/Messenger →
non-exported RuntimeProbeService `:runtime` → AndroidWineManager → existing JNI ProcessManager →
APK-installed Box64 → Bionic Wine → user PE. The UI does not invoke native execution directly.
Pinned Wine ZIP and Box64 binary are unchanged; the old bootstrap laboratory remains available.

DataStore's existing schema-1 archive gains optional `winePrefixId` and `contentGeneration` fields.
Old records retain defaults; no second database or Room migration. `launch.json` is the private locked
execution snapshot, checked again by the runtime. UI edits persist both stores; each write is atomic
independently. Play regenerates the snapshot from the saved profile, reconciling interrupted imports.
These independent writes are not advertised as one distributed transaction.

`games/<UUID>/game.exe`: original EXE imported as data, SHA-256 checked before launch.
`content/`: SAF tree snapshot preserving provider-relative paths. `content.new/`, `content.previous/`,
`import-journal.json` and generation metadata implement stage/publish/rollback. The archive's generation
is the commit decision. No user original is modified. Replacing an imported selected EXE with a folder
that lacks that EXE is refused. A failed/cancelled copy restores the previous snapshot.

Profile/prefix directories use globally ordered nonblocking FileChannel locks. Runtime locks the game,
selected prefix owner and previous prefix owner. Registry/system files stay in the owner's prefix;
other games reference it rather than copying 655 MiB. A durable prefix lease records active execution
so another sharing profile can recover a killed runtime. Recovery retains PID UID/start-time/exe guards.
An owner directory survives Library record removal; removal never deletes shared prefix or content.
AtomicFile readers/writers in the main process are serialized: AtomicFile itself is not thread-safe and
openRead must not discard another writer's pending `.new`. Cross-process config access uses file locks.

LibraryLauncher owns one WineRuntimeClient and ViewModel-scope monitoring. Phases: Ready, Preparing,
Starting, Running, Stopping, Finished, Failed. Each request carries a run identifier; old client states
must not become a new run's completion. `lastLaunch` is updated after the native child actually starts;
it is not an assertion that the PE initialized successfully. Binder death produces Failed and a saved
error. Activity recreation preserves the ViewModel; app restart loads a durable terminal result or
reports an interrupted operation. Neither path automatically executes a Windows program.

Literal argv (64 items / 8192 characters), managed working directory and restricted environment map
are checked at repository, Android manager and native factory boundaries. No shell joins user text.
JNI strings convert standard UTF-8 rather than Modified UTF-8, allowing Unicode filesystem paths.
All programs still share the Android app UID: these validations are path hygiene, not a malware sandbox.

Process stdout/stderr are bounded by the existing native manager; latest JSON and rotated NDJSON provide
logs/export. Default launch Wine debug excludes noisy `+loaddll` so missing DLL errors remain available.
Initialization still has its diagnostic trace. Custom WINEDEBUG can suppress useful errors.
No new rendering/DirectX/driver/controller or thermal/FPS execution path is introduced.

Host/unit tests cover path policy, restricted environment, persistence, low-space refusal and atomic
reader/writer coordination. Device tests exercise actual Compose controls, ContentResolver tree IO,
Wine/Box64 execution and Binder death. Most tests control the *picker result* via Espresso Intents;
that is not equivalent to selecting a file in the system picker UI. Every claim must identify which
was tested. See ANDROID_LAUNCHER_VALIDATION.md for final device evidence and limits.

Primary API references: [SAF DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract),
[Compose tests](https://developer.android.com/develop/ui/compose/testing),
[controlled intent results](https://developer.android.com/training/testing/espresso/intents).
