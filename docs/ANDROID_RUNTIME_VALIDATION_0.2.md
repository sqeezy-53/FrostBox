# FrostBox 0.2.0-dev.2 — Android Runtime Validation

Отчёт обновлён 2026-10-11. Точные UTC timestamps запусков сохранены в validation.json.
Продолжение существующего проекта dev.1/dev.2, без нового проекта и без графической интеграции.

**Первый исходный Windows x64 EXE действительно выполнен на POCO F8 Ultra:**
`FROSTBOX_PE_RESULT=5050\n`, exit42. Подтверждены также 20 повторных Windows-запусков с очисткой
wineserver. Результат относится к консольному bootstrap-пути Wine, не к пользовательским играм.

## Устройство и контекст исполнения

| Проверка | Фактический результат |
|---|---|
| ADB | Авторизованное USB-устройство, device |
| Модель / device | 25102PCBEG / myron; POCO F8 Ultra по идентификации пользователя |
| Android / ABI | 16 / API36 / arm64-v8a;32-bit ABI отсутствует |
| Страница памяти |4096 байт |
| SELinux |Enforcing |
| Установка обоих окончательных APK |Success; предыдущая блокировка HyperOS устранена пользователем |
| Runner |org.frostbox.app.test/androidx.test.runner.AndroidJUnitRunner |
| Runtime UID |10263, совпадает с UID instrumentation/целевого приложения |
| Runtime process |org.frostbox.app:runtime, отдельный PID |
| Logcat / exit-info |Собраны для каждой фазы, включая неудачные |

Instrumentation вызывает RuntimeProbeClient → non-exported RuntimeProbeService → JNI Process →
APK-installed ARM64 Box64. Guest ELF/Wine — данные для транслятора. ADB shell и run-as использованы
для инспекции файлов/процессов, **не для исполнения засчитанных guest-тестов**. Root, смена SELinux,
снижение targetSdk, INJECT_EVENTS и удалённое выполнение не применялись.

## Окончательные результаты на Android / POCO

| Фаза | Instrumentation | Runtime-результаты | Итог |
|---|---:|---:|---|
| ARM64 process, interpreter, dynarec, cancellation |4 теста|4|PASS |
| Dynamic x86-64 Bionic pthread/TLS |1|1|5050 /42, PASS |
|20 ELF-запусков |1|20:10 interpreter+10 dynarec|PASS |
| Wine --version |1|1|wine-11.19 /0, PASS |
| wineboot --init, bootstrap |1|1|0, shutdown/wait завершены; реестр присутствует |
| Исходный Windows x64 console.exe |1|1|5050 /42, PASS |
| wineserver -w |1|1|0, PASS |
|20 Windows x64-запусков |1|20|все 5050 /42, shutdown/wait PASS |

Всего окончательный набор:11 instrumentation-тестов,49 основных runtime-результатов.
Подчинённые команды kill/wait присутствуют отдельными объектами в результатах Wine.
Базовый dynarec-тест требует фактической строки `Emitting `; сохранён дамп сгенерированных ARM64
инструкций. Это подтверждает JIT для проверенного fixture, не все инструкции/игры/16KiB-устройства.
Bionic probe реально использует pthread_create/join и отдельное TLS-значение для main/worker.
Cancellation фиксирует отмену и SIGKILL после игнорируемого helper-ом SIGTERM; дочерний PID отсутствует.

Пример окончательного Windows-результата:

```json
{
  "pid": 31586,
  "exitCode": 42,
  "signal": 0,
  "execErrno": 0,
  "timedOut": false,
  "stdout": "FROSTBOX_PE_RESULT=5050\n",
  "runtimeUid": 10263,
  "runtimePid": 31542,
  "testUid": 10263,
  "testPid": 31488,
  "prefixRegistryPresent": true
}
```

На всех 20 Windows-повторах нет timeout, signal, execErrno или native error. Оригинальный child reaped;
prefix-scoped wineserver -k и -w завершены до финального сообщения IPC. Presence system.reg/user.reg/
userdef.reg проверяется отдельно. Это **не доказывает полную установку DLL в обычный prefix**:
kernel32.dll в system32 ещё отсутствует; запуск использует WINEBOOTSTRAPMODE=1 и встроенные DLL.
Счётчики elapsed/RSS/CPU принадлежат прямому ребёнку, не всему дереву Wine; FPS не измерялся.

## Что собрано и что исполнено на Linux

- Android application APK и AndroidTest APK: Kotlin/Compose/C++ JNI скомпилированы NDK.
- Wine11.19 x86-64/Bionic, собственные host build tools и исходные Windows-fixtures собраны Linux-хостом.
-12 JVM/Robolectric-тестов:8 core,3 repository,1 Activity — PASS.
- CMake/Ninja и 2 CTest-набора — PASS.
- Android lint:0 ошибок,11 предупреждений.
- Исторический host Wine x64/x86 PE тест остаётся отдельным Linux-результатом; он не использован
  для объявления Android-успеха. Текущий отчёт Android основан на instrumentation и app UID.
- Обе локальные Wine-поправки применились к pristine source archive через patch; shell/Python recipes
  прошли синтаксические проверки. Полная cross-build исполнена с этими поправками в рабочем source tree.

## Реальные ошибки и исправления

1. Первые interpreter/dynarec завершились 255: Box64 требует S_IXUSR у guest-файла. Service после
   проверки SHA устанавливает этот mode bit. Guest не передаётся kernel execve. После этого 4 базовых
   теста и 20 повторов прошли на POCO.
2. Wine configure/build требовал рабочий Gradle и host nls/locale.nls; __tooldeps__ было недостаточно.
   Рецепт собирает NLS отдельно. Host/configure логи сохранены.
3. Первый wineboot получил SIGABRT в anon_mmap_alloc: таблица защиты для меньшего ARM64 адресного
   пространства не была выровнена на страницу. Wine patch округляет размер; assertion сохранён.
4. File-backed executable PE .text из private files запрещён Android: map_image_into_view failed.
   Второй patch использует существующий Wine read/copy путь для гостевых PE, без изменения системной
   политики. ARM-инструкции генерирует Box64. Shared writable mapping semantics сохранены.
5. Prefix был неполным, kernel32.dll не загружалась, exit53. Для фиксированного консольного probe
   включён Wine bootstrap lookup. WINEDLLPATH исправлен на корень Wine lib; wineboot вызывается
   по абсолютному guest-пути. Это ограничение отражено в UI/README, не объявлено общей совместимостью.
6. Один service-процесс завершён HyperOS: exit-info OTHER KILLS BY SYSTEM, normal_mem_pressure.
   stdout/native result для того запуска отсутствует; он FAIL. Долговременная устойчивость не доказана.
7. Завершение instrumentation force-stop-ит target: без явного wineserver shutdown реестр не успевал
   сохраниться. Service теперь исполняет -k/-w перед завершением, включая finally при отмене.
   -k exit1 означает отсутствие владельца lock; -w должен завершиться 0.
8. Слишком подробный dynarec/log вывод обрезался; bounded limit увеличен до 32768 байт на поток,
   Wine логирование ограничено, счётчики droppedOut/droppedErr сохранены. Первые обрезанные логи
   не удалены. Неотображаемый IPC-результат теперь даёт понятную ошибку теста вместо JSONException.

Оставшиеся нефатальные сообщения: Android не содержит lscpu; Wine dnsapi собран без libresolv;
есть предупреждение о signal restorer. Вывод не скрывается и не считается доказательством сетевой/
полной signal-совместимости.

## Файлы и воспроизведение

Главные изменения: RuntimeProbeService/Client, process_jni.cpp, box64_backend.cpp,
RuntimeDeviceTest, BionicDeviceTest, WineDeviceTest, WineStressDeviceTest, FrostModel/FrostApp,
android-app/build.gradle.kts, bionic-probe.c, build-runtime-fixtures.py, validate-android-runtime.py,
build-wine-bionic.sh, package-wine-probe.py, два Wine patches, runtime-lock.json и документация.
Полный исходный inventory: FILES.md. Новый проект/переписывание UI не выполнялись.

```sh
# JDK17 / SDK36 / NDK27.2.12479018, Box64/Wine уже упакованы по WINE_BIONIC.md
./gradlew -PwithBox64=true -PwithWineProbe=true \
  :android-app:assembleDebug :android-app:assembleDebugAndroidTest \
  :core:test :android-app:testDebugUnitTest :android-app:lintDebug
cmake -S runtime/native -B build/native -G Ninja
cmake --build build/native
ctest --test-dir build/native --output-on-failure
export SERIAL=your_authorized_adb_serial
python3 scripts/validate-android-runtime.py "$SERIAL" --phase basic --output build/device/basic
python3 scripts/validate-android-runtime.py "$SERIAL" --no-install --phase stress \
  --baseline build/device/basic/validation.json --output build/device/stress
python3 scripts/validate-android-runtime.py "$SERIAL" --no-install --phase bionic --output build/device/bionic
python3 scripts/validate-android-runtime.py "$SERIAL" --no-install --phase wine-exe --output build/device/wine-exe
python3 scripts/validate-android-runtime.py "$SERIAL" --no-install --phase wine-stress --output build/device/wine-stress
```

Исполнены эти Gradle/CTest/ADB команды и все отдельные фазы runner-а. Runner сохраняет stdout/stderr,
exit codes, PID/UID, instrumentation text, logcat, getprop, exit-info и processes-after; SHA-256
установленных APK сверяет с локальными. Android evidence находится рядом с исходниками в
`../android-runtime-validation-live/`; final-* — окончательный набор, остальные — история ошибок.

App APK SHA256: `1142b385ec92d002e9066fc8a20c125640b47f77da8049cddf22d8414b0fb157`.
Test APK SHA256: `755d7876a997e7187f7214feddf8ad14ae3dbc78702e43e90b23807e3507c656`.
Guest PE SHA256: `64a1a734e0375217cf97ea580b75037d2e67bac7cdbbe735697ad36b2fcaeae4`. Архив Wine source и patches распространяются с бинарной сборкой;
лицензионные границы и точные версии: WINE_BIONIC.md и third-party/runtime-lock.json.

## Следующий технический шаг

Полноценное штатное наполнение prefix без bootstrap, свежий prefix с locking/rollback, тест отмены
дерева Wine и восстановление после HyperOS memory pressure. Затем независимая проверка WoW64/x86.
Оконный вывод — последующий этап; DXVK/Vulkan/XInput и игры в текущей работе не интегрировались.
