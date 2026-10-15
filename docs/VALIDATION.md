# Alpha 0.1 validation — 2026-10-10

Результат: собран ARM64 debug APK, Kotlin/Compose и Android C++20/JNI компилируются.
Все проверки выполнены на Linux. Физический Android/POCO и геймпад не подключены;
`adb devices -l` вернул пустой список. Эмулятор Android не запускался.
Robolectric использует Android API 35 на JVM и не является физическим устройством.

## Выполненные команды

В рабочей среде локально установлены Temurin JDK 17, SDK 36, Build Tools 35.0.0,
NDK 27.2.12479018 и CMake 3.22.1; используется wrapper Gradle 8.13.
Команды ниже выполнялись из проекта (при запуске из родительской папки передавался `-p outputs/FrostBox`).

```bash
./gradlew :core:test :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:lintDebug --console=plain
cmake -S runtime/native -B ../../work/native-build -G Ninja
cmake --build ../../work/native-build
ctest --test-dir ../../work/native-build --output-on-failure
```

Итоговый полный Gradle запуск: **BUILD SUCCESSFUL**, 62 tasks, 25 executed / 37 up-to-date.
После добавления копии собственной MIT-лицензии в APK повторно выполнен `:android-app:assembleDebug`.

| Проверка | Результат | Предел доказательства |
|---|---|---|
| Core JUnit | 8 tests, 0 failures/errors | Модели, PE, environment, оси, статистика, hysteresis |
| Android repository / Robolectric | 3 tests, 0 failures/errors | DataStore write/read/update, PE copy/hash, invalid import rollback |
| Activity / Robolectric | 1 test, 0 failures/errors | Compose Activity startup/stop без host JNI; не визуальная приёмка |
| CTest | 1/1 passed | Контракт unavailable; не запуск Wine |
| ARM64 NDK | Compiled and linked | `libfrostbox.so`, Vulkan/JNI symbols resolve at build time |
| Android lint | 0 errors, 9 warnings | 8 обновлений версий, 1 отсутствие ChromeOS x86_64 |
| APK signature | Verified, v2 scheme | Debug key; не публичная release-подпись |
| APK zipalign | Pass: `zipalign -c -P 16 4` | ZIP alignment |
| ELF LOAD segments | 0x4000 for all bundled `.so` | 16 KiB alignment; запуск на таком устройстве не проверен |
| Permissions | Только собственное signature permission AndroidX для receiver | Нет INTERNET, storage, root или dangerous permissions |

APK проверки выполнялись через SDK `apksigner verify --verbose`, `zipalign`, `aapt dump permissions`
и host `readelf -lW` после извлечения `lib/arm64-v8a/*.so`.

## Реальные проблемы и исправления

- Изначально были только JDK 27, CMake, Ninja, adb; SDK/NDK/Gradle отсутствовали.
  Инструменты установлены локально в `work/toolchain`, вне публикуемых исходников.
- sdkmanager сообщил `Error on ZipFile unknown archive` для NDK. Прямая повторная загрузка
  официального android-ndk-r27c-linux.zip прошла ZIP-проверку; SHA-1 совпал с repository2-3.xml:
  `090e8083a715fdb1a3e402d0763c388abb03fb4e`.
- Первый wrapper запуск требовал SDK path: создан игнорируемый local.properties.
- Kotlin отклонил `IntArray.mapNotNull`: добавлено преобразование ID в List.
- Lint выявил RestrictedApi при override AndroidX dispatchKeyEvent: используется публичный
  Window.Callback с делегированием событий, без подавления этой ошибки.
- Добавлены явные backup/data-extraction exclusions; monochrome icon размещён в API-33 ресурсе.
- Импорт учитывает cancellation; финальная запись профиля завершается атомарно относительно
  coroutine cancellation, после успешного commit EXE не удаляется обработчиком отмены.

## Не проверено / не реализовано

Нативный Vulkan probe не выполнялся на Android GPU. Bluetooth/USB, стики, датчики, SAF конкретных
провайдеров, поворот экрана/клавиатура и HyperOS требуют ручной приёмки по POCO.md.
Robolectric импорт использует synthetic content stream и структурный PE fixture; это не доказательство
работоспособности Windows executable. Файл не запускается ни в тесте, ни в приложении.

Wine/Box64, настоящий prefix, окно/звук/DirectX, FPS limiter, runtime frametimes и Windows XInput
не интегрированы. Не измерялись игровой FPS, 1% low, энергопотребление и длительная thermal stability.
GitHub Actions YAML создан, но на GitHub workflow не выполнялся; удалённый репозиторий не создан.

Следующий шаг: приёмка APK на POCO и сохранение реальных GPU/thermal/controller данных, затем
Alpha 0.2 console hello.exe с проверкой stdout/exit code через APK-packaged Bionic-compatible runtime.
До этого нельзя отмечать игры как совместимые или показывать активный Play.
