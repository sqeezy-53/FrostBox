# FrostBox 0.6.0-alpha-physical-recovery — partial device checkpoint

2026-10-14. Реальный POCO F8 Ultra, serial YOUR_DEVICE_SERIAL, Android API36 / ARM64,
app application UID. Установлено поверх evolution без uninstall/clear/prefix удаления.
VersionCode15. Это **не стабильный gaming release**: остаются остановка Present
в level-selection/Sandbox сценарии и ошибки Unity GfxDeviceWorker.

## Input: подтверждённая причина и исправление

Первый отложенный Raw Input test прошёл: real Wine keyboard/mouse devices2,
W/A/S/D/Space scan17/30/31/32/57;10 raw key events; RAWINPUT sizes40/48,
device-info ошибок0; HWND/focus/foreground совпадают; relative37/-19.
Linux-only проблема device enumeration не воспроизвелась на POCO. Самодельные
handles/RAWINPUT структуры не добавлялись: PnP/winehid/winebus и Wine server
предоставляют штатные устройства1/2. Winebus не пересобирался.

Однако прежний test отправлял mouse buttons после движения. В реальной KARLSON
пользователь подтвердил движение персонажа, но отсутствие камеры. Новый test
ждёт WM_INPUT37/-19 **до кнопок**: старый driver зависает10s, FAIL. Найдена причина:
Wine11.19 `accum_mouse_motion` удерживает движение до host frame completion,
кнопки или достижения batch limit. FrostBox не сообщал завершение mouse frame.
После batch3/8 добавлен штатный `MOUSEEVENTF_MOVE_NOCOALESCE` flush через
NtUserSendHardwareInput(NULL,SEND_HWMSG_RAWINPUT,...), как pointer_handle_frame
в Wine Wayland driver. Ни busy-wait, ни новый input manager, ни system injection.
Усиленный test на POCO: PASS/exit42, movement alone доставлен. Пользователь
после патча подтвердил поворот камеры в самой игре. Предыдущие scan-code/raw
sample исправления сохранены. Focus forcing на каждом кадре не добавлялся.

Unity на Android действительно перечисляет raw devices и регистрирует mouse
usagePage1/usage2, target сначала splash HWND0x1005e, затем game HWND0x10056.
Keyboard route выбирает focus child только с совпадающим root ancestor. Наличие
import/GetAsyncKeyState и fixture нельзя выдавать за доказанный единственный
keyboard API Unity; отдельный hook потребления GetRawInputData/keyboard polling
не выполнялся. Регистрация Raw Input + физическая реакция камеры + независимый
movement-only regression подтверждены отдельно. KARLSON не квалифицируется
как XInput application: работает проверенный keyboard/mouse mapping.

Физический 8BitDo: Android ABXY/D-pad/Start/Back, оба стика/триггера, neutral
после disconnect и stable slot reconnect PASS. Windows fixture: реальные
XInput1.3/1.4/9.1.0, capabilities0, четыре stick axes>32000, triggers255/255,
disconnect1167/reconnect, exit42. Полные PAD snapshots сохранены. Эти физические
тесты выполнены на промежуточном v14 этой сессии; qualified winebus/Android HID
bridge неизменны в финальном v15. No USB/rumble qualification.

## Чувствительность и обычный Library

Настройка существовала как «Скорость мыши». Переименована в «Чувствительность
камеры · правый стик», диапазон50–6000 ед./с вместо50–2000; сохраняется отдельно
для игры, применяется при следующем Play. Unit test проверяет границы и JSON.
Настоящий Compose test: slider2700 → Save → recreate Activity → reload2700 PASS.
KARLSON profile сохранён в keyboard mode, sensitivity1800, audioEnabled=true;
остальные mapping/settings, game files, prefix и -force-gfx-direct сохранены.
Перед миграцией записан private karlson-input-before-physical-recovery.json.
Изменения только этой записи; глобальная controller calibration не сбрасывалась.

## Audio: Android и слышимость

Первый device audio test: AudioTrack initialized, PCM0, exit3 — mmdevapi assert
`!status`, mmdevapi_private.h:118. Причина: optional midi_get_driver возвращал
STATUS_NOT_IMPLEMENTED. Wine ожидает успешный Unix dispatch; отсутствие/unsupported
feature сообщается через output params. Добавлены корректные empty-MIDI handlers:
нет MIDI endpoints, notify thread сразу завершает цикл, optional commands дают
MMSYSERR_NOTSUPPORTED; никакого ложного MIDI/audio device. Regression test добавлен.
Пересобран **только** маленький experimental Unix audio module, не Wine целиком.

После патча real Windows WASAPI fixture на POCO: endpoint present,48k stereo PCM16,
4800-frame capacity,9600 frames received **и consumed** Android AudioTrack,
2 Start/Stop/Reset rounds, cleanup=true/error empty, exit42. Повтор и финальная
регрессия прошли. Пользователь: «Да, слышал» два коротких Windows сигнала.
Звук физически слышен; microphone/loopback не использовались.

KARLSON с audio opt-in: пользователь «звук есть». Actual mmdevapi create:
flow0/shared0/flags0/rate48000/channels2/bits16. Android получил4050240 PCM frames,
потребил4045440 до Stop (4800 queued frames discarded при cleanup); starts1,
cleanup=true. Это настоящая Windows-game→Wine→Android audio output.
Не заявлена универсальная WASAPI/event/exclusive/capture/XAudio2/multi-stream
совместимость. Driver ограничен shared polling stereo48k PCM16/float32.

## Оставшиеся игровые блокировки

Пользователь выбрал Sandbox0 и сообщил зависание. Audio-enabled 90s attempt:
923 presented frames, audio продолжало идти, затем controlled Stop. Трасса
mmdevapi ограничена native log budget; dropped stderr2009489 bytes, поэтому
поздний crash нельзя достоверно исключить по этому логу. Recorded final socket
reset при Stop не доказывает первопричину зависания.

Сравнение без audio, -force-gfx-direct: level-selection достигнута; frames571
на22s,573 на27s и **573 до87s**; процесс жив, FD234 стабилен, CPU ticks продолжаются.
В этом сравнении нет зафиксированного физического Sandbox0 click: это повтор
того же класса presentation stall, не строгий A/B того же клика. Audio не является
единственным необходимым условием. Native debuggerd -b под app UID ответил
«root is required». Ограничение не обходилось; ни root, ни security изменений.
Стек зависшего Wine потока не получен, точная причина ещё не установлена.
Screen timeout600000ms и Awake опровергают простой 22-second screen timeout.

Обычный threaded mode,45s: user «туториал работает фулл»,3324 GPU presents,
но Player.log повторяет Gfx command not handled:0/-2125814000 (last10034),
GfxDeviceWorker.cpp:2504. В этой проверке SIGSEGV не зарегистрирован; signal15 /
cancelled=true соответствуют контролируемому Stop. Закрытие FrostBox после
45s — finish instrumentation, подтверждено ApplicationExitInfo, не новый crash.
Полный backtrace нового GfxDeviceWorker crash отсутствует, поскольку новый crash
не получен. Историческая проблема остаётся unqualified; -force-gfx-direct сохранён.
Переписывать WSI или пересобирать Box64/DXVK без доказательств не стали.

Физические движения/камера и слышимый звук подтверждены, **длительная совместная
игра с аудио и стабильность всех уровней не подтверждены**. 10–15min stress/game
session не проводилась из-за нового stall. Frame-time/1%low не измерены.
Короткий direct input run2:7532 presents/118.004s ≈63.8 average presented frames/s
за полный exec interval с menu/loading, не gameplay benchmark. Run1:8930/117.796s
≈75.8; threaded:3324/43.808s≈75.9. Эти разные окна нельзя трактовать как сравнение
производительности режимов. Thermal status0 на снятом snapshot, battery31.3°C
cached value; никакой гарантии температуры или длительной тепловой устойчивости.

## Проверки и артефакты

Final v15: **7 Android regressions PASS**:
1. movement-only Raw Input / keyboard / focus / device info;
2. WASAPI→actual AudioTrack9600/42, cleanup;
3. Library console5050/42 three launches + Activity recreation;
4. Windows Vulkan compute10800/42;
5. extended Unicode;
6. touch→Win32;
7. DXVK D3D11 cube120 Draw/Present + PixelCopy.
Additional sensitivity UI save/recreate PASS. Profile migration/reload PASS.
24 core JVM +11 Android JVM/Robolectric PASS;3 audio Unix contract tests PASS;
2 CTest PASS. APK/test APK builds PASS. Old game directories/profile marker
preserved; no base archive, previous APK, user prefix/game removal.

Evidence: ../../outputs/physical-recovery-validation/ (successes **and failures**,
manual reports, timing/PID/UID, stdout/stderr/codes, logcat, exit-info, screenshots).
Checkpoint: ../../outputs/physical-release-0.6.0-alpha/.
Source snapshot uses the previously delivered evolution upstream source bundle;
corresponding path/SHA is included. No game files/Qualcomm driver are redistributed.

## Exact commands

From existing outputs/FrostBox:

```bash
export JAVA_HOME="$PWD/../../work/toolchain/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$PWD/../../work/gradle-home"
python3 scripts/build-wine-audio-overlay.py --wine-work ../../work/wine-wsi --sdk ../../work/toolchain/sdk
python3 scripts/build-wine-keyboard-driver.py --source-work ../../work/wine-wsi --work ../../work/wine-keyboard-driver
python3 scripts/build-wine-wsi-overlay.py --source-work ../../work/wine-vulkan --work ../../work/wine-wsi --sdk ../../work/toolchain/sdk
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
 -PwithBox64Vulkan=true -PwithWineVulkan=true -PwithSurfaceIpcProbe=true \
 -PwithWineWsi=true -PwithD3d11Probe=true -PwithWineInput=true -PwithRemoteAudio=true \
 -PwithPhysicalRecovery=true :core:test :android-app:testDebugUnitTest \
 :android-app:assembleDebug :android-app:assembleDebugAndroidTest
python3 scripts/validate-remote-input-audio.py --serial YOUR_DEVICE_SERIAL --no-install --regressions
adb -s YOUR_DEVICE_SERIAL shell am instrument -w -r -e class org.frostbox.app.library.RemoteInputAudioDeviceTest#cameraSensitivityUiSaveAndRecreate org.frostbox.app.test/androidx.test.runner.AndroidJUnitRunner
```

Internal stimuli are test-only. Physical controller tests require explicit
`frostbox.physicalPad=true`; game probes require `frostbox.gameQa=true` and the
existing user-owned game. Tests terminate at their documented duration; launch
normally from Library for sessions outside instrumentation.

Changed: winefrost.drv mouse-frame flush; frost_audio optional MIDI contract / create
logging; keyboard sensitivity validation/label; v15 build flag; Raw/audio/sensitivity
instrumentation and game probe timeline/profile; host regression; packaging/docs.

Next bounded technical step: app-UID Wine/native debugger or supported in-process
stack diagnostic at an actually stalled Present; capture thread/queue/guest IPC ACK
states. Distinguish shader/loading stalls, GPU-owner lifecycle, guest wait and Unity
command corruption before changing WSI/Box64. Keep validated input/audio checkpoints.
