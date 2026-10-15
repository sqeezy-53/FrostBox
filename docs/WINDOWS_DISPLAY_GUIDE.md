# Windows Display candidate — build and device gate

**Первый software GDI GUI получен на POCO. Совместимость произвольных GUI/игр не заявлена.**
Нужен обычный Linux host с работающими Gradle и ADB/USB; доступ восстановлен; результаты сохраняются отдельно от прежних blocked attempts.
Базовый 0.3.0-dev.2 runtime/APK сохранён.

```bash
# Из корня существующего FrostBox; SDK/JDK по инструкции BUILD.md.
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
scripts/build-windows-fixtures.sh
scripts/build-gui-fixture.sh
# Каталог уже собранного pinned Bionic Wine с host-tools и patched исходниками:
scripts/build-display-driver.sh /absolute/path/to/work/wine-android
python3 scripts/verify-display-package.py
./gradlew -PwithBox64=true -PwithWineProbe=true -PwithWindowsDisplay=true \
  :core:test :android-app:testDebugUnitTest :android-app:assembleDebug \
  :android-app:assembleDebugAndroidTest :android-app:lintDebug
adb devices -l
adb -s YOUR_DEVICE_SERIAL shell pm list instrumentation
python3 scripts/validate-display-runtime.py YOUR_DEVICE_SERIAL \
  --methods guiPixelsMouseKeyboardAndClose --output ../android-display-validation/first-device
```

Runner проверяет наличие display assets в APK и совпадение SHA256 установленного APK.
При failed Gradle build он не должен установить прежний APK и выдать его за Display.
Он сохраняет instrumentation, logcat, app UID/PID/exit/stdout/stderr, Surface PNG через PixelCopy,
process snapshot, ApplicationExitInfo и meminfo. Файлы пользователя и данные app не очищаются.
Тесты создают свои библиотечные записи. Все четыре Display gate исполнены на POCO и прошли; см. WINDOWS_DISPLAY_VALIDATION.md.

После устранения ошибок первого кадра (включая font backend):

```bash
python3 scripts/validate-display-runtime.py YOUR_DEVICE_SERIAL --no-install \
  --output ../android-display-validation/full-device
scripts/validate-launcher-release.sh YOUR_DEVICE_SERIAL ../android-display-validation/regressions
```

Вручную после успешного smoke gate: импортировать `build/windows-fixtures/gui-x64.exe` через
Library → Add Game → системный SAF dialog. Play должен открыть экран Windows. Касание меняет
красный прямоугольник, аппаратная клавиша — зелёный, Esc/Back закрывает EXE с кодом42.
Close window отправляет WM_CLOSE; Stop отменяет процесс через прежний Runtime Manager.
Проверить уход/возврат приложения, поворот/Surface recreate, повторное открытие и отсутствие
оставшихся Wine процессов. Это **инструкция ожидаемого поведения**, не отчёт о выполнении.

Не использовать privileged INJECT_EVENTS. Если HyperOS блокирует внешнюю автоматизацию,
ручной ввод выполняется пользователем; ограничение записывается отдельно.
При отсутствии первого кадра watchdog останавливает процесс с диагностической ошибкой.

## Полный сценарий воспроизведения

Для текущего рабочего checkout (обычный Linux-терминал, не restricted tool-shell):

```bash
cd <workspace>/outputs/FrostBox
export ANDROID_HOME="$PWD/../../work/toolchain/sdk"
export JAVA_HOME="$PWD/../../work/toolchain/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
# Копия cache уже существует; системный ~/.gradle не удалять.
export GRADLE_USER_HOME="$PWD/../../work/gradle-home"
scripts/finish-display-validation.sh "$PWD/../../work/wine-android" \
  "$PWD/../android-display-validation/terminal-font-gui-1" YOUR_DEVICE_SERIAL
```

Выбрать новый results directory при повторе. Скрипт сохраняет APK после успешной сборки,
до device gate; наличие APK не означает PASS. При failed smoke дальнейшие тесты останавливаются.
В output останутся build log и device failure evidence. Скрипт требует USB/sockets/network;
он не меняет sandbox, SELinux или HyperOS и не использует root.

Для offline FreeType сначала поместить проверенный upstream archive в выбранный work directory:

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export FREETYPE_WORK="$PWD/../../work/wine-display/freetype"
python3 scripts/build-display-freetype.py --work "$FREETYPE_WORK" \
  --archive /absolute/path/freetype-2.14.3.tar.xz
```

SHA256 обязателен: `36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f`.
Поддержка шрифтов включается только в schema-2 display overlay; обычный Wine base не меняется.
Нужны реальные TextOutW Latin/Cyrillic pixels, а не один successful return code.
