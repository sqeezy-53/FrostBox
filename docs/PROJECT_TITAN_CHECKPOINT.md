# PROJECT TITAN — 0.5.0-dev.4-titan-checkpoint

Дата: 2026-10-13. Это проверенный инженерный checkpoint Windows WSI/DXVK и короткого игрового сценария, а не завершённый стабильный 0.6.0. Продолжение началось с уже работающего interface48/Win32 surface и неправильной геометрии HWND; завершённый dev.2-аудит не повторялся.

## Подтверждённые слои

| Слой | Реальный статус |
|---|---|
| Persistent GPU IPC | Приватный exec guest → GPU image FD / SYNC_FD → host Present → retirement ACK; использован в новых Windows тестах |
| Wine interface48 | Экспериментальный Wine WSI overlay, реальные callbacks |
| VK_KHR_win32_surface | Перечисляется Windows PE; VkInstance/Win32 surface создаются |
| Guest swapchain / Acquire / Present | На POCO; реальные GPU submissions, семафоры, ownership transfer и host Present |
| Windows Vulkan triangle | 120 Draw/Acquire/Present, resize, close, exit42; финальный APK22 разных PixelCopy |
| DXVK | 2.7.1, реальные x64 d3d11.dll/dxgi.dll через winevulkan/Box64/Adreno840 |
| D3D11 | Hardware cube,120 Draw/Present,exit42,22 PixelCopy; обычный Library Play также проверен |
| Сторонняя Windows3D-игра | KARLSON: два коротких45s Tutorial-сценария, автоматизированный ввод/движение/падение/возврат; экспериментально |
| Headless Windows Vulkan | Регрессия10800/42 прошла на финальном APK |
| Native Android Vulkan | Предыдущая квалификация сохранена; этот renderer не подменяет новые Windows тесты и в этой итерации отдельно не переиспытан |
| DX12 / VKD3D / FSR / игровая оптимизация | Не начинались |

Устройство: POCO25102PCBEG/myron, ADB YOUR_DEVICE_SERIAL, Android16/API36, arm64-v8a, обычный application UID. GPU Adreno840, Qualcomm Vulkan1.4.295, driver0.842.27. Другие UID/приложения не использовались для доказательства выполнения и не завершались.

## Первопричина HWND6357189×0

GetClientRect/GetWindowRect возвращали успех, но фактическая client area была неправильной. RECT и остальные Win64 структуры инициализированы, размеры/offsets проверены статическими assertions для RECT/WNDCLASS/MSG/MINMAXINFO/WINDOWPOS. Старый GetLastError после успешного вызова не интерпретирован как ошибка. Отслежены стили, WM_GETMINMAXINFO, WM_NCCALCSIZE, WM_SIZE и переводы client/desktop coordinates.

В diagnostic prefix `17d97c71-08d4-4bca-aaa5-0de6eb7d5600` в user.reg находились CaptionHeight/MenuHeight/SmCaptionHeight=6357103. SM_CYCAPTION=6357104; Win32 minimal size6357197×6357112 расширял decorated окно, а nonclient calculation оставлял client6357189×0. Это повреждённые nonclient metrics до Vulkan, а не сбой RECT ABI или surface capabilities. Borderless HWND отдельно прошёл120frames/22PixelCopy и использован только для разделения оформления и WSI.

Исходники Wine11.19: `win32u/sysparams.c:get_text_metr_size` при неуспехе NtGdiGetTextMetricsW мог оставлять TEXTMETRIC неинициализированным, а нормализация читала его высоту/leading. В изолированной экспериментальной сборке добавлена инициализация `.tmHeight=-1`, остальные поля0. Историческое происхождение записанного мусора именно при старой fontless инициализации — обоснованная гипотеза, а не восстановленная трасса того старого сбоя.

Уже записанный реестр патч сам не исправляет. Для принадлежащего тестовой fixture prefix разрешён отдельный guarded repair: явный `FROSTBOX_REPAIR_DIAGNOSTIC_METRICS=1`, backup user.reg, SPI_GET/SETNONCLIENTMETRICS, замена только невозможных высот18/15/18 с сохранением остальных настроек. Пользовательские prefixes автоматически не правятся и не удаляются. После исправления WS_OVERLAPPEDWINDOW outer320×240 → client312×213; resize480×360 → client472×333; caption19/frame4/min112×27. Первый decorated gate:120GPUframes/exit42/21PixelCopy, host generation2,6imageimports/120SYNC_FD, ошибка пустая. Значения18/18/15 сохраняются в registry.

## Реальный WSI путь

Box64 остаётся отдельным exec ребёнком. Wine interface48 обращается к x64/Bionic `libfrostvk.so`; обычные Vulkan GPU функции обрабатываются существующим Box64 wrapper и системным ARM64 loader. Surface/swapchain guest handles локальны гостю. Binder Parcelable Surface передаётся в `:runtime`, где host владеет ANativeWindow. Не передаются Java/native указатели, HWND handles или произвольные Vulkan API вызовы через RPC.

Guest создаёт2–4dedicated RGBA8 images, экспортирует OPAQUE_FD. Host проверяет UID/session capability, generation, device/driver UUID, metadata и memory requirements и импортирует изображения один раз. Acquire использует настоящие queue barriers и semaphore/fence. Present ждёт app semaphore, переводит image в GENERAL/EXTERNAL, экспортирует настоящий SYNC_FD. Host импортирует sync, копирует/масштабирует GPU image в свой Android swapchain, вызывает Vulkan Present и ACK только после bounded GPU/present retirement. Mutex сериализует queue submissions/idle для DXVK threads; condvar ожидание без busy-wait.

Дополнительно исправлен host preTransform: переданные кадры не pre-rotated, поэтому при поддержке выбирается IDENTITY. Использование currentTransform ошибочно объявляло, что вращение уже применено. Устранён подтверждённый90° разворот меню на POCO. Поведение fallback currentTransform на других устройствах не квалифицировано.

## DXVK и обычный Launcher

Pinned DXVK2.7.1 release SHA256 `d85ce7c79f57ecd765aaa1b9e7007cb875e6fde9f6d331df799bce73d513ce87`, Zlib license, только x64d3d11/dxgi. Wine11.19 `455e3509b98a6919fd4ad1def4803e08c41c03b2` LGPL2.1+, Box640.4.4 `2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a` MIT, FreeType2.14.3 FTL. Нативный Qualcomm driver не распространяется. License/source/patches включены в bundle. [Официальный DXVK release](https://github.com/doitsujin/dxvk/releases/tag/v2.7.1).

Cube fixture использует настоящие D3D11CreateDevice/D3DCompile/vertexbuffer/constantbuffer/depth/Draw/DXGI Present; shaders vs_5_0/ps_5_0,36vertices,feature11_0. DXVK logs подтверждают winevulkan и Adreno840. Финальный guest8836/runtime8769:5006ms,120frames/120SYNC_FD/3imports/22PixelCopy,exit42,serverkill/wait0. Предупреждения EDID и pipeline-library/memory-budget не скрыты.

Library импортирует PE через существующий SAF и сохраняет GraphicsBackend.DXVK. Play ждёт реальный Binder GPU endpoint и запускает через прежний Wine Process Manager, prefix lock/journal и argv vector, без shell-конкатенации. Хеши DLL проверяются; WINEPATH оказался недостаточен для Wine DLL lookup, поэтому управляемые DLL добавляются рядом с EXE только в приватную импортированную копию. Иная существующая DLL не перезаписывается. Выбор GDI заставляет использовать builtin d3d11/dxgi. Original SAF files/base system32/qualified Wine ZIP не меняются.

Activity ждёт landscape перед созданием GPU Surface, чтобы не передать уничтожаемую portrait Surface с ANativeWindow extent−19. GPU/input-only client geometry отделены от software framebuffer. Реальные counters/errors и bounded DXVK logs доступны в Runtime Logs. Нет fake Play и автозапуска после импорта. Library gate `library-d3d11-6`:120frames/exit42/20PixelCopy. [Game report](FIRST_WINDOWS_GAME_VALIDATION.md) подробно отделяет меню, level и ввод.

## Итоговые проверки и доказательства

Evidence лежит рядом с репозиторием: `../titan-wsi-validation/`, каждый этап в отдельной директории; прежние неуспешные попытки не удалены. JSON содержит PID/UID/stdout/stderr/exit/server cleanup; logcat/exit-info и processes-after сохранены. Top-level legacy `wineSurfaceConnected=false/presentedFrames=0` не относится к новому image bridge: реальные значения вложены в `hostWsi.guestWsi`.

| Evidence | Метод и проверенный результат |
|---|---|
| `geometry-before-repair` → `decorated-metrics-repair` | Точная геометрия/metrics до и после; decorated120/42/21PixelCopy |
| `final-wsi` | Windows triangle120/42/22PixelCopy, resize312×213→472×333; D3D11 cube120/42/22PixelCopy |
| `library-d3d11-6` | Реальный Library SAF/settings/Play120/42/20PixelCopy |
| `final-library` | saveValidatedKarlsonDirectProfile (только конфигурация); addPlayRepeatLogsAndRecreate (3×5050/42,пересоздание); stopRecreateAndRuntimeDeath (Stop, Binderdeath, recovery) |
| `final-gdi` | compatibilityLabControlsAndUnicode: Win32 controls/Unicode,exit42 и Stop |
| `final-headless` | windowsCompute: реальное10800/42 |
| `final-gameplay` | Второй45s Tutorial на финальном appAPK:1651observedframes/47PixelCopy,6deliveredactions,StopSIG15,errorempty,serverwait0,prefixvalid |
| `game-qa-optin` / `game-qa-default-skip.txt` | Последний testAPK: конфигурация сохранена; game test без явного opt-in корректно SKIPPED(-4), не counted as passed execution |

Семь выбранных итоговых Android методов включают один configuration-only и шесть runtime/regression методов. Плюс повторный короткий game run; после opt-in test-only изменения повторена только короткая проверка конфигурации. Полная длительная suite не запускалась после каждого изменения. На host18JVM/Robolectric (core12+Android6),2CTest (runtime_contract/process_lifecycle) прошли; XML/LastTest.log включены в evidence. Нативный WSI/JNI действительно собран NDK и проверен телефоном; CTest сам по себе не доказывает GPU путь.

Финальный appSHA256 `1ed97e9c6c098cc47332715672a939f4932feba49a3b9be10a7fe1a5a54263ae`. Финальный testSHA256 `547d068f407a8118dee96982e895e198fb1ae9fd6fd960c51fecd33f506a1ee4` после opt-in guard. У разных стадий testAPK hashes отличаются и явно сохранены в их JSON; нельзя утверждать, что все тесты запускались последним testAPK. AppAPK итоговых регрессий/игры одинаков; Library120gate выполнен прежним checkpointAPK, затем launcher/regression/game повторены финальным.

`preserved-qualified-assets.json`:30qualified assets byte-identical относительно preceding persistent checkpoint. Qualified Wine ZIP SHA256 `583f3072e134528598e3374c95260c2cc69f10afbc225993e10eaf0023139c52`; default/Vulkan Box64, GDI, fonts, launcher fixtures сохранены. Пользовательские данные/prefixes и предыдущие сборки не очищались. Итоговые процессы Wine завершены в тестах через собственный manager/server cleanup. Это не гарантия cleanup при любом будущем OEM kill.

## Сборка и воспроизведение

Из корня репозитория, Linux toolchain в `../../work/toolchain`:

```bash
python3 scripts/build-wine-wsi-overlay.py --source-work ../../work/wine-vulkan --work ../../work/wine-wsi --sdk ../../work/toolchain/sdk
python3 scripts/build-d3d11-probe.py --wine-work ../../work/wine-wsi --dxvk-work ../../work/dxvk-experiment
JAVA_HOME="$PWD/../../work/toolchain/jdk" PATH="$PWD/../../work/toolchain/jdk/bin:$PATH" GRADLE_USER_HOME="$PWD/../../work/gradle-home" ./gradlew \
 -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true -PwithBox64Vulkan=true \
 -PwithWineVulkan=true -PwithSurfaceIpcProbe=true -PwithWineWsi=true -PwithD3d11Probe=true \
 :android-app:assembleDebug :android-app:assembleDebugAndroidTest :core:test :android-app:testDebugUnitTest
ctest --test-dir build/native --output-on-failure
python3 scripts/validate-surface-ipc.py YOUR_DEVICE_SERIAL --output build/titan-wsi-new --methods SurfaceIpcDeviceTest#windowsTriangleThroughGuestWsi SurfaceIpcDeviceTest#d3d11CubeThroughDxvk
```

В текущей итерации эти сборки/host checks и выбранные device validators реально запускались; использовать новый output, не перезаписывать старые результаты. Base prerequisites/build scripts: [BUILD](BUILD.md), [DXVK integration](DXVK_INTEGRATION.md). После последнего production build менялся только instrumentation opt-in; incremental assembleDebugAndroidTest succeeded in2s, appSHA сохранился. GitHub не публиковался.

Артефакты создаёт `scripts/package-titan-checkpoint.py`: отдельные appAPK/testAPK/sourceZIP/evidenceZIP/trianglePE/cubePE/SHA256SUMS. Bundle name `FrostBox-0.5.0-dev.4-titan-checkpoint`, каталог `../titan-release-0.5.0-dev.4/`. Debug APK, не production-signed release. Source содержит соответствующие pinned Wine/Box64/FreeType archives и локальные изменения. Evidence исключает game binaries/resources/Unity memory dump; game screenshots предназначены только для инженерного результата, не game distribution.

## Остаточные ограничения / следующий шаг

KARLSON default threaded Unity GfxDeviceWorker падал; `-force-gfx-direct` прошёл два45s запуска, но причина command-state corruption не найдена. Нет квалификации длительной стабильности, аудио, физических controllers/rawmouse, полноценного level completion или нескольких игр. Enter workaround для невидимого software selector не заменяет нормальную интеграцию GDI dialog в GPU display.

WSI сейчас один instance/device/surface/swapchain, RGBA8/FIFO. Одновременный oldSwapchain handover не поддержан; проверенный triangle уничтожает старый перед созданием. Custom allocation callbacks и все optional WSI extensions не квалифицированы; часть неизвестных GIPA/GDPA function pointers ещё может попадать в native forwarding и должна быть fail-closed перед широкой совместимостью. Resize guest images проверен; бесшовная Activity rotation/Surface loss/runtime kill для продолжающей GPU игры не обещается — Stop/новая Surface/restart. Fresh GPU prefix с новым font-enabled overlay не проверен на телефоне: использовался существующий полноценный prefix. Импорт/copy и вывод логов bounded, но native DXVK/Player.log на диске ещё требуют production quotas.

Следующий инженерный шаг: сохранить этот baseline, сделать bounded reproducer Unity threaded command failure и проверить память/порядок выполнения без новых переписываний; закрыть unsupported WSI dispatch/oldSwapchain и software-selector/input usability. Затем длительный игровой запуск и реальные пользовательские клавиатура/мышь/геймпад. До этого checkpoint нельзя объявлять complete0.6.0. DX12/VKD3D/FSR/thermal/FPS оптимизации не добавлялись.

## Основные изменённые файлы

- WSI: `graphics/wsi/{guest_protocol.h,guest_wsi.c,image_bridge.cpp,image_bridge.hpp}`, `runtime/display/{surface_owner.cpp,wine-wsi/vulkan.c.inc}`, isolated Wine metric patch.
- Сборка/fixtures: `scripts/build-wine-wsi-overlay.py`, `scripts/build-d3d11-probe.py`, Windows triangle diagnostics, `tests/d3d11/cube.c`, Gradle flags/version, pinned dependency/license metadata.
- Launcher/IPC: `GuestWsiSession.kt`, `WindowsSurface.kt`, `WindowsScreen.kt`, `DisplaySession.kt`, `FrostApp.kt`, `LibraryLauncher.kt`, `AndroidWineManager.kt`, `ProfileRepository.kt`, runtime JNI, `WineVulkanDiagnostics.kt`, RuntimeSurface/Probe Service/Client.
- Проверки: `SurfaceIpcDeviceTest.kt`, `LibraryDeviceTest.kt`, validate-surface-ipc/library-runtime, explicit test-input helper, package script.
- README RU/EN, CHANGELOG, ARCHITECTURE и текущие evidence/WSI/DXVK/game reports. Древние negative checkpoints оставлены историей со ссылкой на этот результат.
