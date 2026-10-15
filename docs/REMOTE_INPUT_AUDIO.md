# 0.6.0-alpha-remote-input-audio — host-validated candidate

Дата: 2026-10-14. Продолжение `0.6.0-alpha-evolution`, **не готовый игровой релиз**.
`adb devices -l` не обнаруживает ни одного устройства. Скрипт проверки сохранил
`deviceTested=false`, пустой список тестов и причину блокировки. В этой итерации
нет новых результатов POCO, физического ввода, реакции KARLSON или прослушивания звука.
Ни данные приложения, ни игры/prefixes, ни предыдущие APK не удалялись.

## Unity input: установлено и исправлено

Проанализирована именно локальная KARLSON Windows64 / Unity 2019.2.6f1.
На Linux Wine запущен её настоящий UnityPlayer с `WINEDEBUG=+rawinput,+xinput,+dinput`.
Трасса содержит регистрацию Raw Input **мыши**: usagePage1/usage2, flags0,
размер RAWINPUTDEVICE16, target HWND0x3006a. Это реальный вызов Unity на Linux,
не доказательство того же вызова на Android. Импорты UnityPlayer включают
GetAsyncKeyState/GetKeyState/GetRawInputData/device enumeration/focus/cursor APIs;
наличие импортов не доказывает вызов каждого API. Managed InputManager игры
содержит WASD/arrows, Space, Ctrl, E/Q, Mouse X/Y; joystick axes там не обнаружены.
Не установлен фактический вызов XInputGetState или DirectInput игрой.

В `winefrost.drv/frost.c` найдены два конкретных дефекта подачи событий:

- Packet4 передавал scan0, если Android не предоставил scan code. Wine server
  формирует RAWKEYBOARD.MakeCode из этого поля; исправный GetAsyncKeyState
  поэтому сосуществовал с некорректным Raw Input. Теперь используется
  Wine MapVirtualKeyEx/MAPVK_VK_TO_VSC_EX текущей раскладки и E0 extended flag.
- Packet8 передавал только положение Wine cursor. При ClipCursor/recentering
  это не независимый относительный input sample. Теперь используется контракт
  Wine11.19 `raw_mouse` / `SEND_HWMSG_RAWINPUT`, с настоящими dx/dy пакета.
  Для absolute touch delta считается от предыдущего transport point того же HWND,
  а не от перемещённого игрой cursor; первое касание даёт нулевой raw delta.
  История сбрасывается при close/destroy HWND.

Изменены только экспериментальные keyboard и WSI overlays. Квалифицированный
base display/font/Wine ZIP не заменён. Wine сам строит RAWINPUTHEADER/RAWKEYBOARD/
RAWMOUSE и device handles; отдельные выдуманные структуры или handles не вводятся.
Дополнительный SetForegroundWindow на каждом кадре не добавлялся. Предыдущие
попытки focus recovery не восстановили физическое управление; окончательная
причина Unity incompatibility пока не доказана.

### Проверка fixture на Linux

`raw-input-x64.exe self`: exit42, raw keys4, scan0 контроль2, scan17 контроль2,
raw mouse3, GetAsyncKeyState down2, ошибок sizing0. Это labelled Win32 SendInput
в собственной host Wine fixture, **не исполнение нового Android driver**.
Stock Wine ускорил относительные37/-19 до74/-38; эти числа не квалифицируют
новый bridge. Все x64 размеры структур проверены compile-time assertions.

Выявлено ограничение: device list пуст и device info lookup дал7 ошибок.
Таким образом exit42 означает успех проверки доставки/контрольных scan codes,
**не полную квалификацию Raw Input**. Wine11.19 `win32u/rawinput.c` ищет реальные
PnP device interfaces и IOCTL_HID_GET_WINE_RAWINPUT_HANDLE; штатный winebus
создаёт keyboard/mouse при IRP_MN_START_DEVICE. Следующий адресный шаг — проверка
Winebus/PnP/winehid в Android prefix, а не подмена handles. На Linux эта причина
не доказана до конкретного отказавшего PnP-вызова. Host prefix использует X11:
первая попытка инициализации с default Wayland/EGL закончилась таймаутом.

Новый Android test запускает passive PE через Library, подаёт только внутренние
FrostBox key/relative-pointer packets, проверяет HWND/focus/foreground, Wscan17,
WM_INPUT37/-19, sizing и успешный device-info lookup. При отсутствии lookup тест
должен упасть. Физический геймпад не нужен; system INJECT_EVENTS не применяется.
Этот test **собран, но не запущен**. KARLSON reaction: **MANUAL VALIDATION REQUIRED**;
нет нового доказательства gameplay или того, что Unity потребила эти события.

## Windows audio: минимальная реализация, пока без Android qualification

Квалифицированная сборка содержит mmdevapi, но собрана без ALSA/Pulse/OSS;
работающего Unix audio backend нет. Исторический POCO WinMM fixture: endpoints0,
exit77. Дефект не в перечислении MMDevice, а в отсутствии output transport.

Добавлен узкий `audio/wine/frost_audio.c` по существующему 37-entry Unix contract
Wine11.19 mmdevapi. Его shared ELF `wineoss.so` занимает штатный OSS slot **только
в отдельном opt-in overlay**; OSS устройства Android не используются. Wine
mmdevapi продолжает реализовывать Windows COM/WASAPI. Registry Audio choice
не переписывается: явное отключение/другой драйвер пользователя сохраняется.

Путь: Windows WASAPI → mmdevapi → x64/Bionic Unix module под Box64 → same-UID
abstract stream socket → launcher-owned Android AudioHub (main app process,
IO dispatcher) → настоящий AudioTrack. Wine exec/:runtime process manager
остаётся прежним. Выбор профиля `audioEnabled` по умолчанию false. UI предлагает
только явно экспериментальный opt-in, когда overlay действительно упакован.

Ограничения реализации: render, shared polling flags0, stereo48kHz PCM16 и
float32 (конвертация в PCM16), максимум4800 frames. Нет capture/microphone,
exclusive/event-driven/rate-adjustment/midi; общая совместимость WinMM,
DirectSound/XAudio2 и звук KARLSON **не квалифицированы**. Одно соединение
обслуживается за раз; concurrent playback/mixing не поддерживаются.

Endpoint объявляется только после успешного HELLO: реального создания
AudioTrack и получения audio focus. Отсутствующий receiver/output даёт ошибку,
а не фальшивое устройство. Fixed-size protocol проверяет длины, peer UID,
capacity/padding; дополнительной неограниченной PCM очереди нет. Guest socket
операции ограничены2s; AudioTrack writes неблокирующие с deadline1.5s.
Playback head, bounded padding, Start/Stop/Reset, cleanup после guest EOF/death
и закрытия launcher реализованы. При focus loss stream инвалидируется;
автоматического возобновления нет. Счётчики различают submitted и consumed frames,
сохраняют totals между Reset; shared AudioClock возвращает byte units.

### Фактические проверки аудио

- x64/Bionic Android Unix module собран NDK27.2, ELF max-page-size16KiB.
- Native host contract tests: **2 PASS**. Используют настоящий массив Unixlib
  entry points и test-only collector/NT timing stub: отсутствие receiver не
  объявляет endpoint; lifetime/buffer bound/float conversion/stale handle checks.
  Collector — **mock, не AudioTrack**; это не Windows→Android audio test.
- `wasapi-audio-x64.exe` на **штатном Linux Wine**: реальный COM endpoint/client,
 9600 PCM frames,2 Start/Stop/Reset rounds, exit42. Он не использует новый backend.
  Первая ошибка fixture (неверный IAudioRenderClient IID) исправлена по Wine IDL.
- Android AudioTrack bridge и WASAPI→Android device test **только скомпилированы**.
  Последний проверит actual outputKind, received9600, consumed≥9600 и cleanup.
- Звук физически не прослушан; микрофон не использовался.

## Сборка и воспроизведение

Команды из корня существующего репозитория; local.properties содержит существующий SDK.

```bash
bash scripts/build-raw-input-fixture.sh
bash scripts/build-wasapi-fixture.sh
python3 scripts/build-wine-keyboard-driver.py --source-work ../../work/wine-wsi --work ../../work/wine-keyboard-driver
python3 scripts/build-wine-wsi-overlay.py --source-work ../../work/wine-vulkan --work ../../work/wine-wsi --sdk ../../work/toolchain/sdk
python3 scripts/build-wine-audio-overlay.py --wine-work ../../work/wine-wsi --sdk ../../work/toolchain/sdk
bash scripts/test-remote-audio-host.sh ../../work/wine-wsi
export JAVA_HOME="$PWD/../../work/toolchain/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$PWD/../../work/gradle-home"
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
 -PwithBox64Vulkan=true -PwithWineVulkan=true -PwithSurfaceIpcProbe=true \
 -PwithWineWsi=true -PwithD3d11Probe=true -PwithWineInput=true -PwithRemoteAudio=true \
 :core:test :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:assembleDebugAndroidTest
ctest --test-dir build/native --output-on-failure
```

Existing prepared Wine source/build trees нужны для incremental overlays;
`test-remote-audio-host.sh` использует GCC только для unit mock, не для Android.
Для fixture-only Linux checks можно использовать отдельный тестовый Wine prefix
и `wine build/remote-input/raw-input-x64.exe self` / `wine build/remote-audio/wasapi-audio-x64.exe`.
Exit42 у этих PE означает диагностический успех; стандартный shell трактует42 как nonzero.

Когда устройство доступно, без участия человека:

```bash
python3 scripts/validate-remote-input-audio.py --serial YOUR_DEVICE_SERIAL --regressions
```

Скрипт устанавливает matching APK/test APK через `adb install -r -t` (без wipe),
проверяет installed SHA/runner, запускает2 новых теста и при `--regressions` ещё5
bounded console/library, headless compute, Unicode, touch и D3D11 tests. Создаёт
новые QA records/EXE, использует существующий выбранный prefix; ничего не удаляет.
Сохраняет stdout/stderr/exit/PID из instrumentation, logcat, process list,
ApplicationExitInfo и JSON. Отсутствие ADB → exit2, deviceTested=false;
INSTALL_FAILED_USER_RESTRICTED → сохранённый отказ, без security обхода.
Эти команды подготовлены; **они не были выполнены на POCO этой итерации**.

## Регрессии и checkpoint

Host:23 core JVM +11 Android JVM/Robolectric, failures0/errors0/skipped0;
2 CTest PASS;2 native audio contract tests PASS. Kotlin/app/test APK builds PASS.
Box64/DXVK не пересобирались; графический IPC не изменялся. Android регрессии,
IME/touch/console5050/compute10800/D3D11/gamepad на новой сборке **не повторены**.
Сравнение APK фиксирует побайтную сохранность qualified runtime/display/DXVK
и native libraries отдельно от намеренно изменённых experimental overlays.

Артефакты: `../../outputs/remote-release-0.6.0-alpha/`; APK, AndroidTest APK,
Raw/WASAPI PE, компактный corresponding-source snapshot (без дублирования
upstream archives), evidence.zip, validation.json, SHA256SUMS. Upstream Wine/Box64
sources остаются в прежнем evolution source archive; LGPL notices сохранены.
Это **host-validated candidate checkpoint**, не установленная/qualified на POCO версия.

Следующий шаг:2 новых device tests. При raw-device failure — диагностика winebus/
PnP до повторного KARLSON; затем короткое Unity API trace с внутренним test-only
вводом. Audio — real WASAPI→AudioTrack test, далее physical listening отдельно.
Unity multithreaded graphics остаётся прежней блокировкой; `-force-gfx-direct` сохранён.
