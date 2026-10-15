> Update2026-10-12: both previously listed geometry/caption limitations are fixed in the
> fixture; one focused POCO GUI test passed. See [limited follow-up report](DISPLAY_WINDOW_FIXES.md).
> Earlier results below refer to their original APKs, not this follow-up build.

# FrostBox 0.4.0-dev.1 — первый реальный Windows GUI на POCO

Отчёт обновлён 2026-10-12. Доступ к ADB/Gradle восстановлен. Ниже — результаты
окончательной сборки, а не прежнего 0.3.0-dev.2 APK. Старые попытки сохранены ниже как история.

## Проверено на устройстве

POCO F8 Ultra (25102PCBEG), serial YOUR_DEVICE_SERIAL, Android API36, ARM64, app application UID.
Путь: Library → изолированный :runtime → JNI ProcessManager → Box64 → Wine11.19 →
winefrost GDI driver → Android SurfaceView. Без root, без удалённого Windows и без подмены окна.

| Проверка окончательной сборки | Результат |
|---|---|
| guiPixelsMouseKeyboardAndClose | PASS: реальные RGB/Latin/Cyrillic пиксели, WM_MOUSE/keyboard, WM_CLOSE, exit42 |
| tenGuiLaunches | PASS: 10/10 GUI запусков, exit42, проверка prefix |
| recreateSurfaceAndStop | PASS: пересоздание Surface и отмена процесса |
| runtimeDeathClosesDisplay | PASS: ошибка IPC при гибели :runtime, закрытие display, повторный запуск |
| addPlayRepeatLogsAndRecreate | PASS: console5050/42, повтор, Library/Activity recreation |
| adjacentDllDirectoryAndMissingDependency | PASS: соседняя DLL; отсутствующая DLL даёт реальный Failed |
| ARM64 / Box64 interpreter / dynarec / cancellation | PASS: 4 базовых Android tests под UID приложения |
| Gradle app APK + AndroidTest APK | Собраны и установлены; SHA256 сверены с устройством |
| JVM/Robolectric / CTest | 16/16 / 2/2 на Linux; часть Gradle checks up-to-date |
| Полная прежняя 29-test suite / lint | В этой завершающей проверке не запускались |

GUI fixture stdout:
```
FROSTBOX_GUI_UNICODE_OK
FROSTBOX_GUI_PAINT
FROSTBOX_GUI_READY
FROSTBOX_GUI_MOUSE
FROSTBOX_GUI_KEY
FROSTBOX_GUI_UNICODE_OK
FROSTBOX_GUI_PAINT
FROSTBOX_GUI_CLOSE
```

Ввод проверен instrumentation через обычные in-app MotionEvent/KeyEvent и изменения
реальных пикселей Windows. Физическое касание и аппаратная клавиатура вручную не проверены.

## Причины ошибок и изменения

1. Сборка остановилась в winefrost C-коде: после ручной pthread TLS замены в WARN оказался
   буквальный перенос строки вместо `\n`. FreeType/configure были исправны. Исправлена строка.
2. Adapter использует pthread_once/pthread_key вместо `__thread`; маркер выставляется только
   после успешного Wine queue registration, без per-thread heap allocation. Финальный ELF
   не импортирует __tls_get_addr. Это локальная проверка adapter, не доказательство всей TLS Wine.
3. Unix init был скрыт visibility flags: добавлен wine/unixlib.h. __wine_unix_lib_init теперь
   экспортируется; на POCO trace подтверждает connected to FrostBox software surface и winefrost.drv.
   Build script проверяет export и запрещает __tls_get_addr.
4. DisplaySession владеет отдельной native Surface reference; освобождение происходит на render
   executor после работы с ней. Ошибки старой Surface не повреждают новую сессию.
5. Surface явно RGBA8888: устранена RGB565 квантизация, точные PixelCopy проверки проходят.
6. Stop отключает и очищает ввод до отмены runtime; stale focus/release не пишут в закрытый socket.
7. Library публикует полный terminal report до idle; устранена гонка prefixValidation при повторах.
   Optional Wine helper import_dll сообщения остаются в логах; настоящий loader_init failure
   по-прежнему обозначает Failed. GUI test ждёт fixture READY и его пикселей, а не первого
   промежуточного окна Wine configuration.

Изменённые компоненты: runtime/display/winefrost.drv/frost.c, scripts/build-display-driver.sh,
android-app/display/DisplaySession.kt, WindowsSurface.kt, runtime/LibraryLauncher.kt,
androidTest/DisplayDeviceTest.kt, FreeType/font overlay recipes и документация. Точные пути
находятся в дереве src/main/java/org/frostbox/app и src/androidTest/java/org/frostbox/app.
Базовый Wine ZIP не переупакован; prefix и пользовательские файлы не удалялись.

## Ограничения и оставшиеся сообщения

Это первый software GDI GUI, не универсальный Windows Display Engine. Проверено одно opaque
окно fixture; arbitrary GUI, несколько окон, IME, графические игры и GPU API не квалифицированы.
Surface snapshot 640×512 при requested640×400 оставляет нижнее поле; caption control glyphs
ещё требуют корректного symbol font. Runtime view копируется из-за HyperOS hardlink EACCES;
дополнительные примерно726MiB кешируются на overlay, не на каждый запуск/игру.
В stderr остаются Wine DNS/libresolv, network errno13, winebth/service, WGL/Vulkan-disabled
и optional helper сообщения. Они сохранены, не скрыты; не блокируют этот GDI fixture.
Отсутствие утечек при длительной работе не доказано десятью повторами.

## Артефакты и воспроизведение

В соседнем outputs: FrostBox-0.4.0-dev.1-arm64-debug.apk, AndroidTest APK, display-source.zip,
display-evidence.zip и SHA256SUMS-display.txt. Raw evidence: android-display-validation/critical-debug/
release-display, release-library, release-basic, FINAL_SUMMARY.json; stdout/stderr/exit/PID/UID,
logcat, ApplicationExitInfo, meminfo и process snapshots сохранены. Surface PNG извлечены
из device evidence без редактирования. Старые failed attempts не удалены; console-library
был прерван установкой APK во время instrumentation и не считается runtime regression.

Команды окончательной проверки (из корня репозитория, JDK17/SDK/NDK настроены):
```bash
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
  :android-app:assembleDebug :android-app:assembleDebugAndroidTest \
  :core:test :android-app:testDebugUnitTest
python3 scripts/validate-display-runtime.py YOUR_DEVICE_SERIAL --output ../android-display-validation/new-display
python3 scripts/validate-library-runtime.py YOUR_DEVICE_SERIAL --no-install \
  --methods addPlayRepeatLogsAndRecreate adjacentDllDirectoryAndMissingDependency \
  --output ../android-display-validation/new-library
python3 scripts/validate-android-runtime.py YOUR_DEVICE_SERIAL --no-install --phase basic \
  --output ../android-display-validation/new-basic
ctest --test-dir build/native --output-on-failure
```
Не устанавливать APK параллельно с instrumentation. Следующий технический шаг — исправить
allocation/viewport geometry и symbol fonts, затем квалифицировать ещё одно реальное GDI
приложение и физический ввод, сохраняя console/prefix regressions. DirectX не начинался.

## История прежних попыток (не текущий статус)

# FrostBox 0.4.0-dev.1 — незавершённый display-прототип

## Продолжение: FreeType recipe и Unicode gate — 2026-10-11

**Новый APK не собран, новых запусков на POCO нет.** Пользователь согласился восстановить
доступ, но текущий tool-процесс всё ещё не видит `/dev/bus/usb`; AF_INET и AF_UNIX bind
возвращают EPERM. Повторная Gradle попытка с workspace cache и `--offline --no-daemon`
останавливается в FileLockContentionHandler / wildcard IP. FreeType source download
останавливается на DNS, до компиляции. Это ограничения среды, не обнаруженные ошибки POCO.

| Результат этой итерации | Фактический статус |
|---|---|
| Win32 GUI EXE с TextOutW и GetGlyphIndicesW для «Привет FrostBox» | Собран x64 PE |
| Kotlin main/debug и все instrumentation исходники | Standalone K2 compiler: успешно |
| Изменённый ARM64 JNI | NDK CMake: успешно |
| Core JUnit / CTest | 10/10 / 2/2 на Linux |
| Wine Tahoma Latin/Cyrillic cmap | Все символы fixture присутствуют; не тест rasterizer |
| FreeType 2.14.3 x64/Bionic + Wine win32u | Рецепт написан; ещё не собраны |
| Новый APK / полный Gradle/Robolectric/lint | Не выполнены из-за socket restrictions |
| GUI / PixelCopy / touch / 10 повторов / recovery на POCO | Не запускались |
| Исторические 0.3.0-dev.2 результаты | Сохранены; не считаются новой регрессией |

Изменения:
- `scripts/build-display-freetype.py`: pinned upstream 2.14.3 archive и SHA256, безопасная
  распаковка, x64 NDK29 shared library, отключены внешние HarfBuzz/PNG/Brotli/BZip2/zlib.
  FreeType internal zlib остаётся частью upstream; ELF dependency audit разрешает только Bionic.
- `scripts/build-display-driver.sh`: отдельная reconfigure с `--with-freetype`, проверка
  SONAME backend, rebuild win32u; schema-2 overlay включает library/fonts/лицензии.
  Base ZIP не переупаковывается. Сам рецепт ещё нельзя считать проверенной сборкой.
- `DisplayPackage.kt`: проверка schema/обязательных файлов/hash/path/ограничения размеров;
  шрифты устанавливаются в share/wine/fonts runtime view. Существующий prefix не удаляется.
- JNI для Display задаёт BOX64_EMULATED_LIBS только для FreeType, чтобы использовать
  x64/Bionic библиотеку. Console путь не изменён.
- `DisplaySession.kt`: bitmap conversion и lockCanvas больше не удерживают общий lifecycle
  lock. Фактическое поведение Surface recreation всё ещё требует устройства.
- GUI fixture: CreateFontW(Tahoma), проверка Cyrillic glyph indices, TextOutW уникальным
  цветом. Instrumentation проверяет реальные пиксели этого цвета и маркер Unicode.
- `verify-display-package.py` и Gradle gate отвергают старый fontless overlay; этот отказ
  воспроизведён локально. Device runner также запрещает schema-1 APK.
- `finish-display-validation.sh`: сборка, smoke gate, четыре Display теста и старые
  регрессии в обычном пользовательском Linux shell; сохраняет APK/evidence, без pm clear.

FreeType pinned checksum взят из [официального release README](https://sourceforge.net/projects/freetype/files/freetype2/2.14.3/).
Build options сверены с [upstream CMakeLists](https://github.com/freetype/freetype/blob/VER-2-14-3/CMakeLists.txt).
Выбран FTL вариант, полные LICENSE.TXT/FTL.TXT копируются из проверенного source archive при
сборке; бинарника FreeType сейчас нет. [Условия upstream](https://freetype.org/license.html).
Wine fonts оставлены без изменений, их name-table notices сохранены в Wine-fonts-NOTICE.txt;
соответствующие .sfd находятся в уже сохранённом pinned Wine source archive. Нет Microsoft fonts.
FTL attribution добавлен в notices. Лицензия собственного FrostBox кода не менялась.

Логи этой итерации: `outputs/android-display-validation/font-integration/` рядом с репозиторием.
Следующий шаг: применить новый доступ к tool-процессу либо выполнить готовый скрипт по
WINDOWS_DISPLAY_GUIDE.md; сначала собрать FreeType/overlay и APK, затем получить первый
реальный Surface screenshot и stdout. Ни текст, ни ввод, ни GUI пока не квалифицированы.

Дата: 2026-10-11. **Windows Display пока не квалифицирован. Нового APK этой версии нет.**
Нельзя выдавать существующий 0.3.0-dev.2 APK за сборку Display.

## Что проверено

| Проверка | Реальный результат этой итерации |
|---|---|
| Историческая основа 0.3.0-dev.2 | Изучены исходники, ANDROID_LAUNCHER_VALIDATION.md и FINAL_SUMMARY.json: ранее 29 Android / 16 JVM / 2 CTest |
| ADB до изменения разрешений чата | YOUR_DEVICE_SERIAL был подключён, runner org.frostbox.app.test/androidx.test.runner.AndroidJUnitRunner найден |
| Собственный Win32 GUI fixture | Собран clang + llvm-dlltool + lld-link; x64 PE, subsystem Windows; импорты user32/gdi32/kernel32 проверены llvm-readobj |
| Wine software driver | winefrost.drv + winefrost.so + patched explorer собраны из локального pinned Wine 11.19 для x86_64/Bionic NDK29 |
| Android C++/JNI | NDK 27.2.12479018, ARM64, API29: libfrostbox.so собрана CMake/Ninja после изменения JNI |
| Kotlin main/debug + core | Прямой Kotlin 2.2.20 compiler, Compose и serialization plugins: компиляция прошла |
| AndroidTest Kotlin | Все существующие и четыре новых DisplayDeviceTest скомпилированы тем же compiler; **не исполнены** |
| JVM CoreTest | Прямой JUnit 4.13.2: **10/10**, включая дополненные проверки GUI/CUI subsystem и короткого PE optional header |
| CTest | **2/2** runtime_contract / process_lifecycle на Linux |
| Gradle assemble/JVM/Robolectric/lint | Не выполнены до конца: sandbox запрещает служебные сетевые сокеты Gradle |
| GUI на POCO, 10 повторов, ввод, recovery, idle CPU/latency/memory | **Не проверено**; новых Android результатов нет |
| 29 Android regression tests на новых исходниках | **Не выполнены**; прежние результаты относятся только к прежнему APK |

Standalone Kotlin использовал уже разрешённые/скачанные dependencies из Gradle cache, извлечённые
AAR classes и прежние R.jar (ресурсы не менялись). Это проверка исходников, а не Gradle build,
линковка APK, Robolectric suite или Android execution. Логи сохранены в
`outputs/android-display-validation/host/` рядом с репозиторием.

## Наблюдаемый блокер среды

Во время работы профиль доступа изменился с unrestricted на workspace-write/restricted network.
Последующий `adb -s YOUR_DEVICE_SERIAL get-state` выдал:

```text
Failed to open bus directory '/dev/bus/usb': No such file or directory
Failed to open netlink socket: Operation not permitted
could not install *smartsocket* listener: Operation not permitted
error: cannot connect to daemon
```

Прежний ~/.gradle оказался read-only. После переноса cache в workspace попытка
`./gradlew --offline --no-daemon ...` завершилась:

```text
Could not create service of type FileLockContentionHandler
Could not determine a usable wildcard IP for this machine
```

Это не результаты Wine/Box64 на телефоне. Ограничения не обходились; запрос на восстановление
доступа отправлен пользователю. Ни root, ни изменение HyperOS/SELinux не применялись.

## Что реализовано в исходниках

- Отдельный LGPL Wine user driver: GDI window_surface flush → bounded Unix stream, same-UID
  peer check, Wine queue FD, mouse/key/close messages. Не stock wineandroid, не X11, не GPU driver.
- SurfaceView, worker rendering, ограниченные frame dimensions, coalescing, letterbox mapping,
  UI Stop/Close/Escape, lifecycle attach/detach, focus input release, landscape.
- Library Play определяет GUI subsystem PE и передаёт endpoint через существующий IPC/JNI.
  Console путь сохраняется. Display включается явно build property `withWindowsDisplay=true`.
- Отдельное runtime-представление на hardlinks: базовый Wine ZIP и prefix marker не изменяются.
  Поверх hardlinks атомарно заменяются только overlay files. Hardlinks требуют поддержки файловой
  системы; fallback с неконтролируемым дублированием Wine не выполняется.
- Bounded event queue; GUI first-frame watchdog 15 секунд после старта Windows процесса;
  runtime death/terminal result закрывают display, report сохраняется в существующих logs.
- GUI fixture RegisterClassExW/CreateWindowExW/ShowWindow/message loop, WM_PAINT/WM_CLOSE,
  цветные прямоугольники, TextOutW, mouse/key response, exit42 при закрытии.
- Четыре подготовленных теста: pixels/input/close; десять запусков; recreate/Stop; runtime death/recovery.
  PixelCopy читает реальный Surface buffer; в приложение не встроены готовые изображения GUI.

## Технические находки и оставшиеся риски

1. Wine 11.19 `dlls/ntdll/unix/loader.c:set_dll_path()` ставит собственный dll_dir раньше
   WINEDLLPATH. Одной переменной окружения для patched explorer недостаточно. Это исправлено
   отдельным runtime view, но его фактическую загрузку ещё нужно подтвердить на устройстве.
2. Базовый Wine собран `--without-freetype`. В `win32u/font.c:font_init()` отсутствие backend
   прерывает загрузку системных шрифтов. **Нужна подтверждённая сборка FreeType x64/Bionic + соответствующий win32u
   в display view**, либо иное обоснованное решение. Текущая компиляция не доказывает работоспособность
   TextOutW. Fixture сообщает FROSTBOX_GUI_TEXT_FAILED при ошибке, тест не засчитает это как PASS.
3. Реализация ограничена одним opaque foreground GDI window. Нет гарантии корректности popups,
   layered windows, сложных регионов, multiwindow, Unicode/IME, cursor shapes, OpenGL/Vulkan/DirectX.
4. Передаются полные damaged window buffers, с CPU-копиями; zero-copy и FPS не заявляются.
5. Экран открывается для PE subsystem GUI. Console EXE, который впоследствии создаёт окно,
   пока не имеет отдельного пользовательского режима Display.
6. Input через in-app MotionEvent/KeyEvent в подготовленных тестах не равен физическому касанию,
   внешней клавиатуре или прохождению системного SAF picker. Эти ручные сценарии остаются отдельно.
7. 15 секунд — предел prototype watchdog, не гарантия времени открытия любого приложения.
   Пересоздание Surface, освобождение buffers/FD, цвета/координаты, fonts и Wine queue semantics
   требуют настоящего device-прогона. No-leak claim отсутствует.

## Следующий минимальный технический шаг

Вернуть Gradle/ADB доступ, собрать candidate с display package, запустить **только**
`guiPixelsMouseKeyboardAndClose` и сохранить logcat/PixelCopy/stdout. Проверить загрузку
winefrost через runtime view. Добавить FreeType с pinned source/license в отдельный view,
не меняя квалифицированный headless base. Только после видимого текста/геометрии и реакции на ввод
переходить к 10 повторам и полным 29 Android + 16 JVM + 2 CTest регрессиям.
Новые APK публиковать как проверенные лишь после этих проверок.

Команды продолжения: [WINDOWS_DISPLAY_GUIDE.md](WINDOWS_DISPLAY_GUIDE.md).
Выбор архитектуры: [WINDOWS_DISPLAY_ARCHITECTURE.md](WINDOWS_DISPLAY_ARCHITECTURE.md).
