# FrostBox 0.4.0-dev.2 — Win32 Text Input Recovery

Дата: 2026-10-12. POCO F8 Ultra, модель 25102PCBEG, serial YOUR_DEVICE_SERIAL, Android API36,
ARM64, UID приложения 10263. Исполнение локальное, без root. Это квалификация ограниченного
software GDI/input пути; совместимость произвольных Windows-программ не заявляется.

## 1. Первопричина и семь прежних попыток

`WindowsSurface` посылал `MOUSEEVENTF_LEFTUP | MOUSEEVENTF_RIGHTUP` на обычный touch-up,
cancel, потерю фокуса и уничтожение Surface, даже если правая кнопка никогда не нажималась.
Штатный Wine Edit открывает контекстное меню по полученному правому отпусканию. Его отдельная
поверхность становилась последним кадром `DisplaySession`, следовательно HWND меню становился
адресатом следующего ввода. Существующий callback `window_changed` также активировал показанное
окно. Edit сначала действительно получал WM_SETFOCUS, но затем терял фокус в пользу popup.

Анализ сохранённых попыток до изменения поведения:

| Evidence directory | Проверенная гипотеза / результат |
|---|---|
| dev2-lab-smoke | Исходный Unicode/controls сценарий: таймаут текста; полного event report нет |
| dev2-lab-diagnostic | LAB_READY, Count и Checkbox работают; нет EN_CHANGE Edit |
| dev2-lab-focus | Hardware keyboard с HWND0 не восстановил child input |
| dev2-key-trace | 40 Unicode down/up для 20 UTF-16 units дошли до драйвера; сообщения логировались root, не Edit |
| dev2-child-focus | Поиск фокуса в текущем GUI-потоке/проверка root ancestor не устранили сбой |
| dev2-focus-order | Ожидание дополнительного WM_SETFOCUS перед текстом не помогло |
| dev2-text-commit | Поиск фокуса owner thread и queued WM_CHAR не дали EN_CHANGE Edit |

Эти варианты меняли адресацию символов, но оставляли ложное RIGHTUP, которое происходило раньше
отправки текста. В старых журналах нет конкретных HWND/owner TID/result posting, поэтому нельзя
ретроспективно доказать полный маршрут каждой из семи попыток. Общая ошибка их кода воспроизведена
новой трассировкой; сохранённые прежние провалы не переименованы в успешные.

Вторая обнаруженная ошибка — JSON ProcessSnapshot трактовал каждый байт UTF-8 как отдельный
Latin-1 символ. Уже работающий Windows Edit с `Ж` давал Android `LAB_EDIT=Ð\x96`, отчего проверка
продолжала падать. Исправление сериализации необходимо и для достоверных Unicode-логов launcher.

## 2. Точное место потери маршрута

Диагностика **до исправления**, `text-recovery-diagnosis-2/editTextRouteDiagnosis`:

- Lab root=0x1004e, Edit=0x10064, multiline=0x1006a, owner/current GUI TID=36.
- После касания Edit: WM_SETFOCUS для Edit, затем WM_KILLFOCUS; фокус/foreground=0x1007c.
- Android text corr=5, U+0416; драйвер получает root=target=0x1007c и `posted=1`.
- Представленный кадр становится 87×126; PixelCopy показывает реальное меню Undo/Cut/Copy/Paste.
- В очереди/процедуре Lab Edit нет соответствующего WM_CHAR/EN_CHANGE.

Пакет не терялся в UTF-16 transport или JNI: менялся **input destination между frame selection
и Wine dispatch**, после ложного mouse release. Поставленный WM_CHAR был предназначен меню.
`error=5` после `posted=1` — старое значение LastError, которое успешный API не обязан сбрасывать;
это не доказательство отказа в доступе при отправке сообщения.

После первого исправления (`text-recovery-fix1`) target=0x10064, фокус Edit; Lab подтверждает
LAB_QUEUE_CHAR=258,1046 и LAB_CHAR_STATE с тем же HWND/TID, затем GetWindowTextW/EN_CHANGE.
PixelCopy показывает `Ж`. Метод ещё FAIL только из-за JSON mojibake. После исправления JSON
(`text-recovery-fix2`) тот же метод PASS с `LAB_EDIT=Ж` в Android. Было два ограниченных патча,
без очередного перебора семи вариантов доставки текста.

Первый новый диагностический запуск `text-recovery-diagnosis` не отправил символ: ошибочно ожидал
новый WM_SETFOCUS для уже активного Edit. Harness исправлен переключением Checkbox -> Edit;
в finally сохранены evidence и остановка процесса. Это не исправление Wine.

## 3. Изменённые файлы

Рабочее поведение:

- android-app/src/main/java/org/frostbox/app/display/WindowsSurface.kt — отпускание только реально
  нажатой кнопки; отсутствие release без press; включён ограниченный InputConnection.
- runtime/process/process.cpp — UTF-8 aware JSON escaping, сохранена обработка invalid bytes.
- runtime/process/process_test.cpp — проверки UTF-8 BMP, surrogate pair и запрета overlong UTF-8.
- android-app/src/main/java/org/frostbox/app/display/WindowsScreen.kt — действующая кнопка Keyboard.
- android-app/build.gradle.kts — versionCode8, versionName0.4.0-dev.2.

Диагностика и acceptance:

- DisplaySession.kt — sequence correlation в неиспользуемом input packet h, лог corr/HWND/unit count.
- runtime/display/winefrost.drv/frost.c — opt-in TRACE owner/current TID, active/focus/ancestor,
  foreground, actual target и результат NtUserPostMessage; алгоритм последней попытки сохранён.
- tests/windows/compat-lab.c — реальные HWND/thread/WM_CHAR/queue/focus probes; после Clear
  GetWindowTextW обоих Edit. Multiline SetWindowTextW не гарантирует EN_CHANGE, поэтому Clear
  проверяется непосредственно Win32 чтением, а ввод — настоящими EN_CHANGE.
- tests/windows/win32-minimal.h, scripts/build-compat-lab.sh — минимальные импорты диагностических API.
- android-app/src/androidTest/java/org/frostbox/app/runtime/DisplayDeviceTest.kt — точечная трассировка,
  расширенный text/pixel сценарий, IME/Back и усиленные прежние Unicode assertions.

Документация: README.md/README.en.md, CHANGELOG.md, ARCHITECTURE.md,
INPUT_BRIDGE_VALIDATION.md, GUI_RUNTIME_STABILITY.md (ссылка поверх исторического отчёта),
WIN32_APPLICATION_COMPATIBILITY.md, FROSTBOX_0.4.0_DEV2_CHANGELOG.md, MANUAL_DEVICE_TESTING.md,
этот отчёт. Старые APK/evidence/source archives сохранены.

## 4. Доставка Unicode

Фактический путь: WindowsSurface/InputConnection -> DisplaySession input worker -> same-UID
local display socket -> winefrost.drv -> live focus owning GUI thread -> queued WM_CHAR ->
GetMessageW/DispatchMessageW -> штатная Edit procedure -> EN_CHANGE -> GetWindowTextW.
Существующий Binder IPC/JNI ProcessManager отвечает за запуск/остановку; текст не идёт через Binder.
UTF-16 units передаются отдельно от virtual-key events. HWND дочернего окна не зашит и не кешируется;
перед отправкой проверяется живое окно и root ancestor. Нет SetFocus из Android/постороннего потока.
Backspace/Enter проходят штатный TranslateMessage для keyboard events. WM_UNICHAR не требуется
для этого Unicode EDIT; произвольные ANSI controls не квалифицированы.

Correlation: Android corr -> input packet h -> driver corr/unit/target -> fixture queue/procedure
unit/HWND/TID. Внутри run сопоставляются FIFO порядок и итоговое Win32 содержимое. Для diagnosis
U+0416 уникален, поэтому неоднозначности нет. Windows fixture не подменяет ввод/отрисовку.

## 5. Реальные проверки текста на POCO

В input qualification прошли editUnicodeBridgeExtended, прежний
compatibilityLabControlsAndUnicode, committedImeAndBack. Окончательная сборка повторяет их
в одном финальном regression cycle; результаты и APK SHA записаны ниже отдельным разделом.

- Hello, Привет, FrostBox 123, ёЁ № €: реальные WM_CHAR, EN_CHANGE/GetWindowTextW; в Surface ROI
  чёрные пиксели текста превышают пустое поле. Снимки text-bmp-0..3 сохранены.
- Пробелы сохранены, Backspace удаляет €: итог `ёЁ № `.
- Переключение single -> multiline -> single: разные focus HWND, реальные итоги обоих контролов.
- Multiline: `Line1\r\nСтрока2`; обе строки проверены Win32 чтением и пикселями.
- Activity recreation: последующий `!` достигает того же Edit.
- UTF-16 surrogate pair 😀: финальный test читает итоговое содержимое; наличие полноценного
  emoji glyph в текущих Wine fonts отдельно не утверждается.
- Clear: после SetWindowTextW оба поля читаются как пустые штатным GetWindowTextW.

Первый расширенный запуск сохраняется как FAIL: все текстовые/пиксельные шаги прошли, но
harness ожидал EN_CHANGE при очистке multiline. Исправлено только чтение результата Clear
в fixture/test, а не скрыт провал.

## 6. Android IME

Включён committed-text InputConnection и кнопка открытия настоящей системной клавиатуры.
Device test подтверждает видимость IME, commit кириллицы, Back скрывает клавиатуру и оставляет
Windows процесс активным. Поддержаны deleteSurroundingText, Enter, ограниченный local composing
buffer; он не посылается в Windows до commit/finish. Полноценная Win32 IME/inline composition,
синхронизация selection/surrounding text и все OEM/сторонние клавиатуры не квалифицированы.
Автоматизированный in-app вызов InputConnection не равен проверке физического набора человеком.

## 7. Проверки сборки и регрессий

Linux: Gradle app/test APK build, 18 JVM/Robolectric (12core+6app), lintDebug PASS.
Native CMake/Ninja: runtime_contract и process_lifecycle, 2/2 CTest PASS. Новые UTF-8 assertions
включены в process_lifecycle. Сборка display driver и tiny PE выполнялась инкрементально.
Полной пересборки Wine/Box64, изменения base ZIP, удаления prefixes/data не было.

Воспроизводимые команды (с настроенными JAVA_HOME/ANDROID SDK/NDK):
```bash
scripts/build-compat-lab.sh
scripts/build-display-driver.sh ../../work/wine-android
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true :android-app:assembleDebug :android-app:assembleDebugAndroidTest :core:test :android-app:testDebugUnitTest :android-app:lintDebug
cmake -S runtime/native -B build/native -G Ninja
cmake --build build/native -j2
ctest --test-dir build/native --output-on-failure
python3 scripts/validate-display-runtime.py YOUR_DEVICE_SERIAL --methods editTextRouteDiagnosis editUnicodeBridgeExtended compatibilityLabControlsAndUnicode committedImeAndBack compatibilityLabControlsAndClose guiPixelsMouseKeyboardAndClose tenGuiLaunches recreateSurfaceAndStop runtimeDeathClosesDisplay --output ../android-display-validation/text-recovery-final-display
python3 scripts/validate-library-runtime.py YOUR_DEVICE_SERIAL --no-install --methods addPlayRepeatLogsAndRecreate adjacentDllDirectoryAndMissingDependency --output ../android-display-validation/text-recovery-final-library
python3 scripts/validate-android-runtime.py YOUR_DEVICE_SERIAL --no-install --phase basic --output ../android-display-validation/text-recovery-final-basic
```
Для повторного прогона обязательно выбирайте новые output directories, не перезаписывайте evidence.

## 8. Статус и 9. Ограничения

**Восстановление Unicode Edit complete** в описанном тестируемом объёме; version0.4.0-dev.2,
экспериментальная debug-сборка. Это не полная Win32 application compatibility и не gaming release.
Не квалифицированы реальные right-click menus/многооконный compositor, arbitrary HWND/focus flows,
физический mouse/keyboard/touch, clipboard, selection, rich text, Unicode ANSI-codepage conversion,
продолжительные сессии и arbitrary applications. Нет Vulkan/DXVK/новых приложений/профилирования.

Исходные DNS/NSI/служебные ограничения Android и сообщения отключённых WGL/Vulkan остаются;
проверенный console/software GDI путь ими не блокируется. Неверные/обрезанные UTF-8 bytes остаются
представимыми в логах, но это не кодек для произвольной Windows codepage.

Wine base ZIP SHA256 неизменен:
`583f3072e134528598e3374c95260c2cc69f10afbc225993e10eaf0023139c52`.
Предыдущие Wine11.19, Box640.4.4, FreeType2.14.3 и Marlett сохранены.

## Финальный результат на точных release-candidate APK

Все результаты ниже получены **после стабилизации**, одним подходящим финальным cycle на POCO.
VersionCode8 / versionName0.4.0-dev.2 подтверждены dumpsys package; installed SHA совпадает
с локальным app/test APK во всех трёх runner gates.

| Gate | Реальный результат |
|---|---|
| Display | 9/9 методов PASS: diagnosis, extended Edit Unicode, прежний Unicode/controls test, IME/Back, controls/close, GDI input/close, 10/10 GUI launches, recreation/Stop, runtime death/recovery |
| Library | 2/2 методов PASS: 3 консольных 5050/42, DLL result5050/42, ожидаемая missing-DLL ошибка exit53 |
| Basic runtime | 4/4 методов PASS: ARM64, Box64 interpreter, Box64 dynarec, process cancellation |
| Linux JVM/Robolectric | 18/18 PASS (12core+6app); сохранён прежний набор |
| Native CTest | 2/2 PASS, включая дополнительные UTF-8 assertions |
| Android lint / APK builds | PASS |

Всего 15 **целевых уникальных Android test methods**, а не полный прежний набор всех prefix,
import и runtime instrumentation methods. Длительные прежние stress/lifecycle suites не повторялись.
Все text-bmp/multiline/recreate/pair events имеют droppedOut=0 и droppedErr=0.
Финальный extended process PID3255, application UID, elapsed5546ms до exit42; это не измерение FPS.
Снимок процессов после последнего gate не содержит Wine, Box64 или :runtime; это не длительная
проверка отсутствия утечек. Marlett/caption и кириллица визуально просмотрены на PixelCopy;
обе строки multiline и символы ёЁ № € видны в реальном кадре 640×400.

Точные SHA256:

- App: `8d51d41d96d6c9931f6b37daef2ab90e020a06ca3063ec37e0cb4a0d76082780`
- AndroidTest: `31f262c1254c5e6642ebadcadf37c7013c92be12b501b7de630ba6eae5793e28`
- Lab EXE: `8fbfe076e30b5e45f4ded95a79caa345a07662e0201ef329bb8b8f24a441eb63`

Артефакты рядом с репозиторием в outputs, новые имена сохраняют прежние incomplete-сборки:

- FrostBox-0.4.0-dev.2-text-recovery-arm64-debug.apk
- FrostBox-0.4.0-dev.2-text-recovery-androidTest.apk
- FrostBox-0.4.0-dev.2-text-recovery-CompatibilityLab-x64.exe
- FrostBox-0.4.0-dev.2-text-recovery-source.zip
- FrostBox-0.4.0-dev.2-text-recovery-evidence.zip
- FrostBox-0.4.0-dev.2-text-recovery-SHA256SUMS.txt

Evidence directories: android-display-validation/text-recovery-final-display,
text-recovery-final-library, text-recovery-final-basic. В ZIP входят также diagnostic failures,
fix1/fix2, input qualification, stdout/stderr/exit/PID/UID/тайминги, logcat, ApplicationExitInfo,
process/memory snapshots, PixelCopy PNG и host build/test logs. Ни один старый провал не удалён.
