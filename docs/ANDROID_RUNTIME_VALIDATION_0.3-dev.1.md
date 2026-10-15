# FrostBox 0.3.0-dev.1 — Android Wine Prefix & Runtime Recovery

Обновлено 2026-10-11. Это продолжение существующего репозитория. Исторический bootstrap-отчёт
сохранён в [ANDROID_RUNTIME_VALIDATION_0.2.md](ANDROID_RUNTIME_VALIDATION_0.2.md).

**Windows x64 EXE выполнен через штатно инициализированный prefix на POCO: 5050 / exit 42,
20/20 повторов. Новый путь не использует WINEBOOTSTRAPMODE.** Проверены реальный импорт и
явный запуск исходного EXE, отмена, восстановление после SIGKILL runtime, прерванный wineboot,
блокировки, контроль версии/целостности и запуск после force-stop приложения.
Это квалификация консольной инфраструктуры, не заявление о production-готовности всех Wine API или игр.

## Где и что проверено

| Уровень | Реальный результат |
|---|---|
| Реализовано | AndroidWineManager, WineRuntimeClient, командный адаптер/JNI поверх существующего ProcessManager; staging/commit/recovery; сохраняемая argv-конфигурация |
| Скомпилировано на Linux | APK + AndroidTest APK, Kotlin/JNI/C++20, существующие Box64/Wine-компоненты включены в APK |
| Протестировано на Linux | 13 JVM/Robolectric-тестов; 2 CTest; lint: 0 ошибок / 11 предупреждений |
| Протестировано на Android/POCO | 10 новых lifecycle-тестов + 11 прежних regression-тестов = 21 instrumentation-тест |
| Не подтверждено | GUI/игры/WoW64; произвольные пользовательские приложения; все варианты HyperOS kill/storage/power failure; обновление Wine |

Устройство: ADB YOUR_DEVICE_SERIAL, модель 25102PCBEG/myron (POCO F8 Ultra по идентификации пользователя),
Android 16/API36, arm64-v8a, страницы 4096, SELinux Enforcing. Оба APK установлены успешно.
Runner: org.frostbox.app.test/androidx.test.runner.AndroidJUnitRunner. Runtime application UID совпадает
с UID instrumentation и обычного приложения. Runtime PID отличается от instrumentation PID.
Root, изменение SELinux/targetSdk, скрытое удалённое исполнение и привилегированное управление
частотами не использовались. ADB/run-as инспектируют файлы; засчитанные исполнения идут через app IPC.

## Новый путь и десять device-тестов

WineRuntimeClient → non-exported :runtime/Messenger → AndroidWineManager → существующий
JNI ProcessManager → APK-installed ARM64 Box64 → x64/Bionic Wine → исходный Windows PE.

| Метод Android instrumentation | Итог на POCO |
|---|---|
| freshNormalPrefix | Новый UUID/profile, provision системных PE, настоящий wineboot --init без bootstrap, реестр, commit, EXE 5050/42 |
| reuseAndTwentyLaunches | Тот же prefix reused=true; 20/20 запусков, 5050/42, child reaped, server kill/wait и critical integrity успешны |
| cancelWindowsProcess | Реальный sleep-x64.exe сообщает READY; отмена фиксируется, child исчезает, prefix valid, следующий EXE 5050/42 |
| recoverKilledRuntime | Сигнал 9 отдельному :runtime под app UID; IPC_DISCONNECTED; recovery очищает записанный child/server, prefix valid; следующий EXE 5050/42 |
| resumeInterruptedWineboot | Уничтожен runtime с запущенным wineboot; staging остаётся, final ещё отсутствует; новый процесс завершает инициализацию, EXE 5050/42 |
| crossProcessPrefixLock | FileChannel lock удерживается instrumentation: init отвергнут; после освобождения запуск успешен; отдельная terminateWineServer выполнена, запуск снова успешен |
| integrityAndExplicitDeletion | Подмена версии в тестовом marker отвергнута без сброса; после восстановления marker valid. Удаление critical DLL выявлено, init отказал. Delete без подтверждения отказал; явное удаление сохранило EXE/config/sentinel, не обходя Z: symlink |
| importContentUriAndExplicitLaunch | Реальный ContentResolver content URI с опасным DISPLAY_NAME; файл записан только как UUID/game.exe, SHA/config сохранены, import не запустил код; явный запуск импортированного исходного PE 5050/42 |
| applicationRestartBefore | Успешный запуск и сохранение ID/PID для отдельной restart-фазы |
| applicationRestartAfter | Runner выполнил am force-stop org.frostbox.app; новый app PID, тот же prefix reused, EXE 5050/42 |

Content URI в тесте предоставляет debug-only non-exported provider; это реальное provider I/O,
но не автоматизированный проход системного SAF picker. UI SAF импорт остаётся существующим
ACTION_OPEN_DOCUMENT путём. В GUI Library общего пользовательского Play пока нет; проверен новый
runtime API с импортированным EXE. Тесты создают собственные UUID-профили, не перечисляют/удаляют
пользовательские профили. Диагностические данные сохраняются; для их удаления нужна отдельная явная
операция. Оригинальный импортированный PE принадлежит FrostBox, игровые файлы не использовались.

Фактический пример первого успешного результата окончательного APK:

```json
{
  "profileId": "52710084-da12-4b1b-b0b3-47789a0478e0",
  "pid": 23121,
  "runtimeUid": 10263,
  "runtimePid": 22945,
  "testUid": 10263,
  "testPid": 22911,
  "bootstrap": false,
  "stdout": "FROSTBOX_PE_RESULT=5050\n",
  "exitCode": 42,
  "signal": 0,
  "execErrno": 0,
  "elapsedMs": 1658
}
```

wineboot прямого дочернего процесса: 4479 ms. Двадцать последовательных EXE:
медиана 1863.5 ms, диапазон 1651–1973 ms; peak RSS прямого ребёнка
84172–99088 KiB. Метрики не включают всю инициализацию/cleanup/дерево Wine,
не являются FPS/frametime и не доказывают отсутствие долгосрочных утечек. В журнале есть полные
stdout/stderr, elapsed/user/system/peak RSS, PID/UID, exit/signal/exec errors и dropped-output counters.
После каждого запуска server -k/-w завершён, child reaped; после методов процесс-снимки не содержат
Wine/Box64 под UID приложения. Это проверенные конечные точки, а не доказательство всех возможных races.

В fresh prefix подтверждены drive_c/windows/system32, Fonts, inf, winsxs, системные файлы/реестры,
C:/Z: mapping. `du -sk`: 670856 KiB (~655 MiB) для измеренного fresh prefix.
Проверка validatePrefix покрывает owner/schema/version/package SHA/commit, три registry headers,
C: mapping, отсутствие redirects в critical directories и SHA четырёх critical PE. Она не проверяет
все DLL/значения реестра и не является автоматическим ремонтом любого повреждения.

## Прежние Android-регрессии на том же финальном APK

| Фаза | Тесты / основные runtime-результаты | Результат |
|---|---:|---|
| basic: ARM64 / interpreter / dynarec / cancellation | 4 / 4 | PASS; dynarec имеет фактический Emitting trace |
| stress: повторные ELF | 1 / 20 | PASS: 10 interpreter + 10 dynarec |
| bionic: pthread/TLS | 1 / 1 | PASS: 5050/42 |
| wine-version | 1 / 1 | PASS: Wine 11.19 |
| wine-prefix (старый bootstrap) | 1 / 1 | PASS: wineboot/shutdown |
| wine-exe (старый bootstrap) | 1 / 1 | PASS: 5050/42 |
| wine-stop | 1 / 1 | PASS: wineserver -w |
| wine-stress (старый bootstrap) | 1 / 20 | PASS: 20/20, 5050/42 |

Итого прежний набор: 11 тестов, 49 основных native-результатов; старый bootstrap сохранён.
Эти результаты относятся к физическому POCO, а не к host Wine. Новые 20 повторов используют final
managed prefix и учитываются отдельно. Список версий/лицензий: third-party/runtime-lock.json.
Box64 0.4.4 / commit 2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a — MIT; Wine 11.19 /
commit 455e3509b98a6919fd4ad1def4803e08c41c03b2 — LGPL-2.1-or-later + per-file notices.
Новые версии компонентов не вводились. Обе прежние portability-поправки Wine сохранены.

## Найденные ошибки и исправления

1. Первая normal-prefix инициализация/EXE уже дали правильный результат, но тест считал наличие
   поля error ошибкой даже при error="". Исправлена проверка на непустое содержимое. Исходный
   неудачный JUnit-прогон сохранён в history-first-prefix, не засчитан как прошедший тест.
2. При расширении repeat-теста одна сборка Kotlin не прошла из-за затенения iteration переменной
   внутри also. Исправлено явным именем iteration; последующие сборки прошли.
3. Реальное recovery и следующий EXE проходили, но assertion наличия строки recovered в последних
   32 KiB логов не проходил: verbose Wine вытеснял запись. Добавлен отдельный AtomicFile
   last-recovery.json, доступный через getRuntimeLogs; все recovery assertion теперь прошли.
4. Уточнён recovery PID guard: Box64/Wine меняет argv0, поэтому используется /proc/PID/exe плюс
   UID/start-time, а не имя cmdline. Добавлены отказ на symlink metadata/critical paths,
   проверка владельца staging и аргументов после чтения сохранённой конфигурации.
5. В Wine stderr остаются headless-ошибки No driver could be loaded, недоступный lscpu,
   отсутствие libresolv и невозможность старта winebth. Они не помешали проверенному console пути,
   но являются ограничениями этой сборки; GUI, Bluetooth внутри Wine, DNS и полный service API
   не объявляются рабочими. ApplicationExitInfo/logcat сохранены; SIGKILL-сценарий не имитирует
   доказанную причину реального HyperOS low-memory kill.

## Основные файлы 0.3

Новые: AndroidWineManager.kt, WineRuntimeClient.kt, runtime/wine/android_wine_command.{hpp,cpp},
PrefixDeviceTest.kt, debug ImportFixtureProvider + debug manifest, sleep.c/sleep-kernel32.def,
scripts/validate-prefix-runtime.py, docs/PREFIX_RUNTIME.md, исторический отчёт 0.2,
.github/workflows/wine-prefix.yml. Обновлены: RuntimeProbeService/NativeRuntime/process_jni/CMake,
GameProfile/ProfileRepository/CoreTest/RepositoryTest, versionCode=4/versionName=0.3.0-dev.1,
точечные status-тексты UI, fixture/package recipes, README ru/en, CHANGELOG/ARCHITECTURE/TODO,
BUILD/POCO/WINE_BIONIC, runtime-lock. Полный inventory: docs/FILES.md.

Нативное исполнение не переписано: новые команды используют существующие start/poll/cancel/release.
DataStore остаётся в основном процессе; runtime читает отдельную locked/AtomicFile конфигурацию.
Операции initialize/validate/launch/stop/status/logs/recover/terminate server/delete доступны через
новый IPC client. Delete требует точную строку DELETE_PREFIX:<UUID>, владелец проверяется,
Files.walk не следует symlink; автоматического сброса пользовательских данных нет.

## Исполненные команды и артефакты

```bash
scripts/build-windows-fixtures.sh
./gradlew -PwithBox64=true -PwithWineProbe=true \
  :core:test :android-app:testDebugUnitTest :android-app:lintDebug \
  :android-app:assembleDebug :android-app:assembleDebugAndroidTest
cmake -S runtime/native -B build/native -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build build/native -j4
ctest --test-dir build/native --output-on-failure
python3 scripts/validate-prefix-runtime.py YOUR_DEVICE_SERIAL --output ../android-runtime-validation-dev3/release-prefix
python3 scripts/validate-android-runtime.py YOUR_DEVICE_SERIAL --no-install --phase basic --output ../android-runtime-validation-dev3/release-regressions/basic
python3 scripts/validate-android-runtime.py YOUR_DEVICE_SERIAL --no-install --phase stress \
  --baseline ../android-runtime-validation-dev3/release-regressions/basic/validation.json \
  --output ../android-runtime-validation-dev3/release-regressions/stress
# Отдельно исполнены bionic, wine-version, wine-prefix, wine-exe, wine-stop, wine-stress.
```

Использован JDK17 из workspace toolchain, NDK27.2.12479018 и SDK36, не системный JDK27.
Python compile, bash -n и YAML parsing прошли. Новый GitHub workflow проверен только синтаксически:
на GitHub его не запускали; commit/push/release publication не выполнялись.

Полный evidence: ../android-runtime-validation-dev3/. Окончательные папки release-prefix и
release-regressions; final-prefix/regressions — предшествующий проход, prefix — ранний набор
с известной ошибкой assertion. Не заменяйте failed history окончательным PASS.
RELEASE_VALIDATION.json содержит компактный агрегат; host/ содержит XML тестов/lint и CTest/build logs.
Установленные APK SHA сравниваются с локальными в каждой фазе, все финальные фазы используют одни APK:

- App SHA256: `fd4d45c1b6de5cf17a3c13fcb2cc6aa8fbf29d2b0a0559228eec47f89b682a66`
- AndroidTest SHA256: `cea076372e4ecf76ea2f5692b7bc752cebd5f8237d1b5a0a0ae28257ed489448`
- Исходный console-x64 PE SHA256: `64a1a734e0375217cf97ea580b75037d2e67bac7cdbbe735697ad36b2fcaeae4`
- sleep-x64 PE SHA256: `fb03cdd341b9510f90471d05c2b573b42b58968564009701f62d93dff6c6a986`

APK распространяется с corresponding upstream Wine source archive, локальными патчами и build recipe.
Собственный код MIT не меняет обязательства Wine LGPL. В сборке нет игровых файлов/проприетарных
GPU-драйверов/телеметрии/сетевого разрешения.

## Осталось и следующий шаг

Законченный проверяемый этап: normal-prefix console API и recovery. Для следующего этапа подключить
его к Library: явные launch/stop/status/logs, lastLaunch только после реального старта, сохранение argv,
SAF directory import для соседних DLL/assets. Затем расширять acceptance на дополнительные WinAPI
fixtures. При смене Wine требуется отдельная backup/migration процедура; сейчас версия отвергается.
При прерывании между записью launch.json и DataStore возможна разница двух атомарных хранилищ;
двухфазная согласованность профиля/config ещё не реализована. Runtime конфигурация восстанавливается
из своего AtomicFile, неизвестный EXE автоматически не перезапускается.

Критическая integrity ограничена; disk-full, reboot/power loss, kill во время каждого commit мгновения,
длительные утечки и реальный background low-memory HyperOS kill не полностью проверены. Console timeout
90s, server timeout 15s; configurable long-session policy — следующая работа. Все Wine prefixes имеют
app UID и не изолируют вредоносный Windows-код от других private файлов FrostBox.
Оконный вывод, WoW64/x86, звук, DirectX/DXVK/Vulkan integration, XInput, игры и оптимизации не начинались.
