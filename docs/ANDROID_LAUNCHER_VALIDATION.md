# FrostBox 0.3.0-dev.2 — Windows EXE Library & Launcher

Проверено 2026-10-11. Разработка продолжает существующий репозиторий; Wine + Box64 не заменены.
**Реальная кнопка Play в Library запускает импортированный console Windows x64 EXE на POCO:
`FROSTBOX_PE_RESULT=5050`, exit code 42.** Есть повторный запуск, Stop, реальные логи, сохранение
профиля/библиотеки, folder import и восстановление после гибели `:runtime`. Окон Windows пока нет.

## Уровни подтверждения

| Уровень | Результат |
|---|---|
| Реализовано | Compose Library/Details/Settings/Logs; SAF EXE/tree streaming, progress/cancel/rollback; shared prefix; Play/Stop/state/recovery; export JSON |
| Собрано на Linux | ARM64 Android APK и AndroidTest APK; Kotlin, JNI и C++20; Wine/Box64 включены |
| Протестировано на Linux | 16 JVM/Robolectric (10 core + 5 repository + 1 Activity), 2 CTest; lint 0 ошибок / 14 предупреждений |
| Протестировано на физическом POCO | 8 новых launcher tests + 10 prefix lifecycle + 11 прежних Android regressions = **29 passed**, без skipped среди этих запусков |
| Не подтверждено | Автоматизированный проход системного SAF picker: HyperOS запретил INJECT_EVENTS; отдельный optional test помечен Ignore и не засчитан |
| Не реализовано/не квалифицировано | Windows GUI/игры/WoW64, DXVK/Vulkan rendering/XInput/FPS; общий sandbox для враждебного EXE; все storage/kill/encoding сценарии |

Устройство ADB `YOUR_DEVICE_SERIAL`: модель 25102PCBEG/myron, POCO F8 Ultra по идентификации пользователя;
Android 16/API36, только arm64-v8a, page size 4096, SELinux Enforcing. Приложение UID **10263**;
исполнение действительно идёт в отдельном `:runtime` с тем же обычным UID. Root, изменение
SELinux/targetSdk, remote Windows execution и обход системных запретов не использовались.

Окончательный APK versionCode=5 / versionName=0.3.0-dev.2:

```text
app:  0ee5aab51c78a8f434f5f800ff16661a31fbc91ca704dd73e6a50fa0dd8e5c7d
test: 3c3d44490b7da87a81690410026b2d388d7440199643c3bc9f2705253c038fca
```

Все **final-library / final-prefix / final-regressions** проверили совпадение установленного APK
с этими локальными файлами. Промежуточные first/second/third/fourth/picker-first/release-* сохранены
как история и не подменяют квалификацию окончательной сборки.

## Восемь launcher-тестов на телефоне

| Метод LibraryDeviceTest | Реальный результат |
|---|---|
| addPlayRepeatLogsAndRecreate | Add Game/Select EXE; импорт console.exe без автоматического запуска; Play 5050/42; ещё два Play; логи stdout; Activity recreate сохраняет экран/результат; lastLaunch установлен после старта |
| stopRecreateAndRuntimeDeath | Настоящий Windows sleep.exe сообщает FROSTBOX_SLEEP_READY; recreate/Stop отменяют процесс; следующий READY runtime убит SIGKILL под app UID; UI получает Failed/died; Recover → Ready; следующий Play/Stop работают |
| adjacentDllDirectoryAndMissingDependency | Без DLL EXE действительно завершается с кодом **53**, stderr содержит frostmath.dll, UI Failed. SAF tree импортирует DLL/data/resource.txt и data/雪.txt; выбирается content/dependency.exe, work=content; Play выдаёт FROSTBOX_DLL_RESULT=5050 / 42. Отмена замены папки сохраняет прежние DLL и generation; собственный 655 MiB prefix не дублируется |
| unsafeFolderCancellationAndInvalidExe | Traversal ../escape.exe, A.dat/a.dat collision, truncated read отвергнуты; slow 8 MiB provider copy отменён; content.new и ошибочный content отсутствуют; короткий invalid.exe показывает PE-ошибку и не создаёт запись |
| editArgumentsEnvironmentAndRemoveRecord | Через Settings сохранены имя, буквальные arguments ["literal argument", "$(no_shell)"] и BOX64_DYNAREC=1; launch.json проверен; Play 5050/42; Remove record через подтверждение удаляет запись, сохраняет EXE и общий prefix |
| landscapeRetainsLogs | Play 5050/42; фактический переход Activity в landscape и обратно portrait; stdout остаётся доступным |
| cancelBeforeIpcStarts | API lifecycle в одном UI turn: Play затем Stop до binding; cancelled=true, notStarted=true, нет PID/runtime journal и lastLaunch. Это API-тест ранней отмены, не проверка двух физических касаний |
| restartPersistence | Runner выполняет am force-stop; новая Activity читает прежнюю Library запись/lastLaunch и снова Play 5050/42 без импорта |

В основных сценариях нажимаются **реальные Compose кнопки**. Только ответ системного picker
контролируется Espresso Intents; URI указывает на настоящий debug DocumentsProvider и реальные
потоки ContentResolver. Ни runtime, ни Wine output не замоканы. Данные — собственные MIT EXE/DLL
FrostBox и тестовые ресурсы, а не пользовательские игры. Сторонние оригиналы не изменяются.

Попытка systemPickerImport открыла настоящий com.google.android.documentsui, но UiAutomator click
получил SecurityException: `Injecting input events requires ... INJECT_EVENTS`. Ошибка и logcat
сохранены в picker-first. Защита HyperOS не менялась. Метод explicitly Ignore, не входит в 29 passed.
Ручной выбор EXE/дерева в системном диалоге требует дополнительной приёмки пользователя.

Пример фактического результата Play из final-library:

```json
{
  "profileId": "8702654b-c6db-406f-8790-4a8f86844b7c",
  "prefixId": "8b1d4112-ccd7-4a26-8ace-f1ea86f66b5f",
  "pid": 28384,
  "runtimePid": 28354,
  "testPid": 28202,
  "runtimeUid": 10263,
  "testUid": 10263,
  "bootstrap": false,
  "stdout": "FROSTBOX_PE_RESULT=5050\n",
  "exitCode": 42,
  "signal": 0,
  "execErrno": 0,
  "elapsedMs": 1024
}
```

## Сохранённые runtime-регрессии

**10/10 PrefixDeviceTest** на том же APK: freshNormalPrefix, reuseAndTwentyLaunches,
cancelWindowsProcess, recoverKilledRuntime, resumeInterruptedWineboot, crossProcessPrefixLock,
integrityAndExplicitDeletion, importContentUriAndExplicitLaunch, applicationRestartBefore/After.
Штатный wineboot без WINEBOOTSTRAPMODE, критическая целостность/реестр, безопасное удаление только
диагностического prefix, блокировки, recovery и reuse сохранены. Повторы **20/20**, каждый 5050/42;
медиана native elapsed 1844 ms, диапазон 1740–1948 ms. Это длительность прямого дочернего процесса,
не FPS, не полное время инициализации/cleanup и не долгосрочный benchmark.

**11/11 прежних тестов**, 49 основных runtime results:

| Фаза | Instrumentation tests | Native results | Итог |
|---|---:|---:|---|
| basic ARM64/interpreter/dynarec/cancel | 4 | 4 | PASS |
| stress ELF | 1 | 20 (10 interpreter + 10 dynarec) | PASS |
| bionic pthread/TLS | 1 | 1 | PASS |
| wine-version | 1 | 1 | Wine 11.19 |
| wine-prefix, старый bootstrap | 1 | 1 | PASS |
| wine-exe, старый bootstrap | 1 | 1 | 5050/42 |
| wine-stop | 1 | 1 | PASS |
| wine-stress, старый bootstrap | 1 | 20 | 20/20, 5050/42 |

После каждого метода сохранены ps и ApplicationExitInfo; final-process-audit.json не содержит
посторонних Wine/Box64 дочерних процессов под application UID — остаются только процессы самого app,
если они ещё существуют в момент снимка. Native reaping/server shutdown и guards также проверяются
в тестах. Это проверка конечных точек, а не доказательство отсутствия всех возможных descendant races.
SIGKILL-сценарии не являются симуляцией всех причин реального HyperOS low-memory kill.

Box64 **0.4.4**, commit 2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a, MIT;
Wine **11.19**, commit 455e3509b98a6919fd4ad1def4803e08c41c03b2, LGPL-2.1-or-later + per-file notices.
Сборка Wine x86_64/Bionic, APK-installed ARM64 Box64, прежний immutable Wine ZIP и обе portability
поправки сохранены. Glibc Linux Wine не загружается в Bionic. Corresponding Wine source archive,
патчи и build recipe поставляются вместе с Wine APK; собственной MIT-лицензией Wine не перелицензирован.

## Найденные проблемы и изменения

- AtomicFile не синхронизирует потоки самостоятельно: частый read мог удалить pending `.new`,
  оставляя UI в Running/Starting после реального завершения. Read/write сериализованы; добавлен
  конкурентный JVM-тест и повторно пройдены device cancel/recovery сценарии.
- К каждому запросу привязаны runId и отдельные binding/reply lifetimes; old state не становится
  завершением следующего запуска. Collector работает в Main scope, дисковые операции — Dispatchers.IO.
- Ранняя Stop до IPC теперь сохраняет отмену без запуска PE; hash/import cancellation проверяются
  между блоками. Явный пользовательский launch не ограничен прежними 90 секундами; native timeout=0
  означает отсутствие deadline, сохраняя отмену. Init=90s и server=15s, legacy diagnostics не изменены.
  CTest проверяет unbounded session cancellation; device тест отменяет действующий Windows процесс.
- JNI передаёт стандартный UTF-8 для путей вместо Modified UTF-8. Unicode ресурсное имя сохранено
  на POCO; отдельный запуск EXE с Unicode basename в этой итерации не квалифицирован.
- Default launch WINEDEBUG больше не вытесняет missing-DLL error большим +loaddll trace. Init trace сохранён.
- Исправлены ошибки тестов: ожидание прежнего Finished вместо нового runId; exact-vs-substring stdout
  assertion; scrollTo на кнопке вне scroll container; Robolectric free-space fixture (его default был 0).
  Первые неудачные прогоны сохранены, они не засчитаны как PASS.
- Старые SECURITY/README/POCO сообщения «Windows execution unavailable» заменены текущими фактами.

## Основные файлы

Новые: LibraryLauncher.kt; data/ManagedFiles.kt и TreeImporter.kt; debug FixtureDocumentsProvider;
LibraryDeviceTest; собственные math.c/math.def/dependency.c; validate-library-runtime.py и
validate-launcher-release.sh; LAUNCHER_USER_GUIDE/LIBRARY_ARCHITECTURE/этот отчёт, исторический отчёт dev.1.

Обновлены: FrostModel/FrostApp, существующие ProfileRepository/GameProfile, AndroidWineManager
(shared prefix reference/lease/locks), WineRuntimeClient, NativeRuntime/process_jni UTF-8/environment,
android_wine_command (launch environment/deadline), ProcessManager timeout=0 и соответствующий CTest,
CoreTest/RepositoryTest, Gradle version/test dependencies/debug fixture assets, fixture recipe,
README ru/en, CHANGELOG/ARCHITECTURE/TODO/SECURITY, BUILD/POCO/COMPATIBILITY, notices/source lock,
Wine source-build workflow artifact name. Полный source inventory — FILES.md.

## Реальные команды

```bash
scripts/build-windows-fixtures.sh
./gradlew -PwithBox64=true -PwithWineProbe=true :core:test :android-app:testDebugUnitTest \
  :android-app:assembleDebug :android-app:assembleDebugAndroidTest :android-app:lintDebug
cmake -S runtime/native -B build/native -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build build/native -j4
ctest --test-dir build/native --output-on-failure
python3 scripts/validate-library-runtime.py YOUR_DEVICE_SERIAL --output ../android-launcher-validation/final-library
python3 scripts/validate-prefix-runtime.py YOUR_DEVICE_SERIAL --no-install --output ../android-launcher-validation/final-prefix
# Последовательно basic/stress/bionic/wine-version/wine-prefix/wine-exe/wine-stop/wine-stress:
python3 scripts/validate-android-runtime.py YOUR_DEVICE_SERIAL --no-install --phase basic --output ../android-launcher-validation/final-regressions/basic
# Для stress дополнительно --baseline .../basic/validation.json.
```

Для нового воспроизводимого прогона всех этапов: `scripts/validate-launcher-release.sh SERIAL NEW_OUTPUT_DIR`.
Runner не выполняет pm clear и не удаляет пользовательские профили. SHA установленного APK проверяется
перед каждым набором; stdout/stderr/exit/PID/UID/timing, logcat, exit-info и process snapshots сохранены.
GitHub Actions обновлён как host/source-build workflow; запуск на GitHub и публикация не выполнялись.
Lint warnings относятся к pinned newer-dependency suggestions и ChromeOS ABI, не к runtime errors.

Поставка: FrostBox-0.3.0-dev.2-arm64-debug.apk, AndroidTest APK, source.zip, device-evidence.zip,
SHA256SUMS и исходный FrostBox-Wine-11.19-upstream-source.tar.gz. Debug key — не release signing.

## Практические ограничения и следующий этап

- Нет вывода Windows windows, GUI/игр/x86 WoW64; пока только проверенные консольные x64 fixtures.
- System SAF picker нужно вручную проверить на HyperOS. Controlled URI tests не доказывают его UX.
- Export реализован/собран, но ручной round-trip через системный CreateDocument не выполнен.
- Folder 16 GiB / 10000 / depth32 и EXE512 MiB — лимиты, а не результаты полного multi-GiB теста.
  Проверены реальное дерево/DLL/ресурсы, faults и отмена 8 MiB потока. Blocked provider read может
  задерживать отмену; все power-loss/storage-full/crash-at-commit варианты ещё не квалифицированы.
- Shared prefix разделяет registry/C:. Library removal оставляет файлы; нет UI восстановления записи
  и общего безопасного удаления данных. Self prefix selection в UI собран; отдельные prefix qualified API.
- ASCII console output проверен. Старый native byte-preserving JSON не выбирает кодовую страницу;
  не-ASCII вывод может отображаться неверно. Аргументы сохраняются буквально; отдельный Windows
  GetCommandLine quoting/encoding набор не выполнен.
- Групповая очистка и общий app UID не являются security sandbox. Не запускайте враждебные EXE.
- Не квалифицированы произвольные приложения, бесконечная сессия/утечки или все HyperOS kill modes.

Следующий технический этап — **FrostBox Windows Display**: минимальный Win32 window test →
реальный Android Surface/display bridge, сохраняя 29-test console gate. До этого никаких DXVK,
DirectX, game FPS/thermal optimizations или фиктивных graphic settings.
