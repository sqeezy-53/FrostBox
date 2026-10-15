# FrostBox 0.5.0-dev.1 · Vulkan render validation

## Статус

Нативный ARM64 Android Vulkan renderer **проверен на POCO F8 Ultra**, API36, application UID, без root.
Это законченный экспериментальный GPU foundation; Windows Vulkan и DXVK не реализованы.
Wine11.19, Box640.4.4, GDI display/fonts и пользовательские prefixes сохранены.
Версия APK0.5.0-dev.1 / versionCode9. Финальные SHA и проверки приведены ниже.

## Что фактически проверено

На точной финальной сборке: `qualified-gpu` → 2/2 instrumented methods PASS.
`realGpuProbe` вызван настоящей кнопкой Probe; GPU properties извлечены в JNI под UID приложения.
`realTriangleAndLifecycle` вызвал Start и проверил реальные VkDevice/swapchain, ≥30 кадров,
PixelCopy SUCCESS и RGB geometry. Два разных кадра подтверждают движение; bounding-box проверка
исключает растянутую полосу вместо треугольника. PNG portrait/landscape просмотрены визуально.
Ни Canvas, ни готовые картинки, ни Wine/GDI в этой сцене не используются.

| Сценарий | Реальное наблюдение renderer PID30070 |
|---|---|
| Portrait native scene | Running, 73 accepted presents, image/Surface1128×1300, contexts2 |
| Поворот Activity в landscape | Running, 84 presents, contexts3, Surface2392×300/image300×2392, RGB и пропорции PASS |
| Activity.recreate в landscape | Running, 95 presents, contexts4; новые кадры PixelCopy PASS |
| Background (Activity CREATED/onStop) | Paused, 124 presents; счётчик неизменен в контрольном интервале300ms |
| Foreground RESUMED | Running, 134 presents, contexts5; создание нового swapchain |
| Stop | Stopped, 136 presents; deviceCreated=false и swapchainCreated=false |
| Повторный Start | Новый session counter: Running43, contexts1; PixelCopy и пропорции PASS |
| Save JSON | Файл текущего PID/state/framecount проверен; не принят stale report предыдущего теста |
| Финальный Stop | Stopped53, deviceCreated=false; штатное завершение теста |

Первый дополнительный context вызван изменением высоты SurfaceView при появлении capability report.
Это реальная resize/recreation, не дополнительная очередь кадров. Native request queue single-slot,
максимум один submitted command buffer in-flight. В фоне/Stop GPU контекст уничтожается на worker.
На финальном Vulkan logcat нет native crash/Failed/VUID сообщений. Validation layers не подключались,
поэтому отсутствие VUID не равно validation-layer certification. ApplicationExitInfo и process dumps
сохранены; старые системные записи exit-info не выдаются за падения этой сборки.

OUT_OF_DATE/SUBOPTIMAL/device-loss обработка реализована, но на финальном устройстве соответствующие
counters0; искусственная потеря устройства не создавалась. Полной fault/hung-driver recovery и
долговременного leak/thermal soak proof нет. Требуются Vulkan1.1 и EXT maintenance1 feature;
на других Android драйверах может быть честная ошибка unavailable. 180/270-degree rotation не проверены.

## Метрики, действительно полученные

Срез `native-render`, portrait1128×1300, 73 accepted present calls, application UID/PID30070:

| Метрика | Значение | Что означает |
|---|---:|---|
| Latest context initialization | 8.93490ms | instance/device/WSI/resources на worker, последняя resize-инициализация |
| Time to first accepted frame | 10.8143ms | после Start, включает ожидание Surface/инициализацию |
| CPU record+submit wall mean | 0.0559153ms | запись команды и vkQueueSubmit; не GPU время |
| Scoped renderer-thread CPU mean | 0.121174ms | record/submit/present CPU; не вся CPU нагрузка процесса |
| GPU interval mean | 0.238223ms | 71 завершённый timestamp sample, TOP_OF_PIPE→BOTTOM_OF_PIPE |
| Present-call interval mean | 16.6600ms | cadence вызовов, не scanout latency |
| Present-call interval P95 | 17.0192ms | bounded window, не latency GPU |
| Present-call cadence | 60.0241Hz | CPU deadline/FIFO, не benchmark GPU throughput/Windows FPS |
| Android thermal status | 0 / NONE | состояние в короткой проверке, не обещание отсутствия перегрева |
| Process PSS | 162149KiB | Android app+instrumentation, sampled every2s; не GPU allocation |
| Dropped display frames | null | scanout drop источник отсутствует |
| GPU memory usage | null | capacities heaps не подменяют usage; memory_budget отсутствует |

GPU timestamps содержат интервал command buffer, включая возможную синхронизацию; это не чистое
время ALU и не end-to-end display latency. Некоторые последние timestamp samples не считываются при
retirement, поэтому sample count меньше present count. Паузы/resize исключены из pacing intervals.
После Start статистика сбрасывается; после recreation сохраняется, init time относится к последнему
контексту. Все lifecycle snapshots: JSON (local checkpoint artifact, not part of the source repository).
Нет измерений DXVK/DirectX/игрового FPS, 1% low игры или устойчивости долгой игровой сессии.

## Ошибки, обнаруженные и исправленные

1. Debug.getPss возвращает Long: первичная Kotlin compile ошибка исправлена.
2. Worker сначала создавался до инициализации всех членов C++ объекта; запуск перенесён в тело
   конструктора до первого device-test. Stats reset/paused cadence также исправлены до квалификации.
3. Первый lifecycle-run прошёл recreation/background/foreground/Stop, но тест после нового Start
   принял старый framecount при Stopped. Ожидание теперь требует Running и новый counter threshold.
4. После пересборки test APK `--no-install` честно отказал по SHA mismatch; APK установлен, это
   не GPU failure и не повод отключать проверку идентичности.
5. Landscape fixed-height report оставлял Surface без высоты: report ограничен48dp, Surface восстановлена.
6. Следующий RGB-only тест формально прошёл, но визуальная проверка обнаружила растяжение при90°.
   currentTransform устанавливался без swapchain dimension swap и shader pre-rotation. Исправлены
   обе части, добавлена bounds-aspect assertion; обновлённые PNG подтверждают геометрию.

Все первоначальные провалы/intermediate evidence сохранены. `final-gpu`/`final-display` — исторические
промежуточные имена до исправления pre-rotation; финальная квалификация имеет префикс **qualified-**.
Не смешивать их SHA или считать старый слабый тест доказательством исправленной геометрии.

## Воспроизведение

```bash
# SDK/NDK/JDK настроены по BUILD.md; Wine/Box64/GDI packages уже подготовлены.
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
  :android-app:assembleDebug :android-app:assembleDebugAndroidTest \
  :core:test :android-app:testDebugUnitTest :android-app:lintDebug
cmake -S runtime/native -B build/native -G Ninja
cmake --build build/native -j2
ctest --test-dir build/native --output-on-failure
python3 scripts/validate-vulkan-runtime.py YOUR_DEVICE_SERIAL --output ../android-vulkan-validation/new-gpu
python3 scripts/validate-display-runtime.py YOUR_DEVICE_SERIAL --no-install --methods \
  editUnicodeBridgeExtended committedImeAndBack guiPixelsMouseKeyboardAndClose \
  tenGuiLaunches recreateSurfaceAndStop runtimeDeathClosesDisplay \
  --output ../android-vulkan-validation/new-display
python3 scripts/validate-library-runtime.py YOUR_DEVICE_SERIAL --no-install --methods \
  addPlayRepeatLogsAndRecreate stopRecreateAndRuntimeDeath adjacentDllDirectoryAndMissingDependency \
  --output ../android-vulkan-validation/new-library
python3 scripts/validate-android-runtime.py YOUR_DEVICE_SERIAL --no-install --phase basic \
  --output ../android-vulkan-validation/new-basic
```

Выбирайте новый output directory; не перезаписывайте прежние результаты. Обновление APK через
`adb install -r -t` сохраняет данные; uninstall/pm clear/удаление prefix не выполнялись.
В интерфейсе: Library → Vulkan Diagnostics → Probe → Start → Stop/Start → Save JSON.
Back/Library возвращает launcher и останавливает сцену. JSON сохраняется в приватном
`files/vulkan-diagnostics`; `adb exec-out run-as org.frostbox.app cat files/vulkan-diagnostics/render.json`
читает его в debug-сборке. Это локальная диагностика; сетевой телеметрии нет.
Реальное нажатие человеком не входит в автоматизированное доказательство — кнопки нажимались Compose
instrumentation, но GPU и содержимое Surface настоящие. Родные GDI/IME регрессии выполняются отдельно.

## Сборка, регрессии и артефакты

Финальный набор на одинаковых установленных SHA обоих APK:

| Gate | Результат |
|---|---|
| Vulkan | 2/2 methods PASS, real GPU/Surface pixels + rotation/lifecycle/Stop/Start |
| Display | 6/6 methods PASS: extended Unicode, IME/Back, GDI input/close, 10/10 GUI launches, recreation/Stop, runtime-death recovery |
| Library | 3/3 methods PASS: console5050/42 + repeat/recreate, Stop/runtime-death, missing DLL exit53 + imported DLL5050/42 |
| Basic runtime | 4/4 methods PASS: ARM64, Box64 interpreter, Box64 dynarec, cancellation |
| Linux JVM/Robolectric | 18/18 PASS (12core+6app), no failures/errors |
| Host CTest | 2/2 PASS: existing runtime_contract/process_lifecycle, not GPU emulation |
| Android APK/AndroidTest/native compilation | PASS; C++20, -Wall/-Wextra/-Werror |
| lintDebug | PASS,0 errors; pinned-dependency update warnings remain |

Всего **15 целевых Android test methods**, не полный исторический prefix/stress suite.
20 Wine повторов и полная prefix suite не повторялись: их прежние результаты не переносятся на
эту версию как новые тесты. До pre-rotation fix также прошли9 Display методов на промежуточном
APK; они отдельно сохранены и не включены в этот финальный count.

- App SHA256: `12129b6b36c3d3189a65366e4896744b6a6c04b322e08eace4592f8ff92f94a2`
- AndroidTest SHA256: `bf541cb05ffaebb13bbfc6852ed392ddcd4b12d27cf999ec2d537230c4a3cbbc`
- Wine base ZIP: `583f3072e134528598e3374c95260c2cc69f10afbc225993e10eaf0023139c52`
- Box64: `167dbda2f6020c73f13c874fa1b2826725e638bc9bbf7005a14b872404669c56`

Сравнение с qualified0.4.0-dev.2 APK: Box64 binary/manifest, Wine ZIP/manifest и все18
GDI/font assets полностью совпадают. Wine и Box64 не пересобирались; пользовательские данные,
прошлые APK и prefixes не удалялись. SELinux Enforcing. Новые тестовые профили создаются обычными
существующими test fixtures; неизвестные пользовательские EXE автоматически не исполняются.
Существующие Wine DNS/NSI/service/WGL и `Wine was built without Vulkan support.` diagnostics
остались: нативный Android renderer их не исправляет и не выдаёт за Windows Vulkan.

Device evidence: `outputs/android-vulkan-validation/qualified-gpu`, `qualified-display`,
`qualified-library`, `qualified-basic`. PNG и JSON также в evidence ZIP; build logs рядом с repo.
Финальный process snapshot после basic gate сохранён; длительное отсутствие утечек не квалифицировано.

Созданы рядом с репозиторием:

- FrostBox-0.5.0-dev.1-arm64-debug.apk
- FrostBox-0.5.0-dev.1-androidTest.apk
- FrostBox-0.5.0-dev.1-source.zip (own source plus corresponding pinned Wine/Box64/FreeType sources)
- FrostBox-0.5.0-dev.1-evidence.zip (final gates, earlier failures, JSON/PixelCopy/logcat, host reports)
- FrostBox-0.5.0-dev.1-SHA256SUMS.txt

Артефакты не переименованы из старой сборки. Debug signing, без публикации на GitHub.
Следующий технический этап ограничен headless Box64 Vulkan loader/ABI proof; в этой итерации
он не запускается. [Аудит и порядок изменений](VULKAN_GPU_ARCHITECTURE.md).
