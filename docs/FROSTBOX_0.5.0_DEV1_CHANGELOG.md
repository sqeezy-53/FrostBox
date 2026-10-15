# FrostBox 0.5.0-dev.1 · фактические изменения

## Реализовано и скомпилировано

Новый независимый GPU module: native Android ARM64 Vulkan capability probe, VkDevice/Surface/swapchain,
собственные SPIR-V shaders, rotating RGB triangle, корректная pre-rotation90°, renderer worker,
bounded submission/presentation lifecycle, GPU timestamp/CPU wall+thread/pacing/PSS/thermal diagnostics.
ViewModel сохраняет session при Activity recreation; уничтожение native resources вынесено с UI thread.
Capability/render JSON записывается атомарно в private files, Save сообщает местоположение.
Ошибки отсутствующего loader/GPU/extensions/Surface отражаются явно. Версия9 /0.5.0-dev.1.

## Проверено

POCO API36, application UID:2 Vulkan +6 Display +3 Library +4 basic runtime methods =15 PASS.
Включены PixelCopy геометрия/движение/пропорции после поворота, Stop/Start, background/foreground,
GDI GUI10/10, Unicode/committed IME, runtime death/recovery, console5050/exit42, DLL и missing-DLL error.
Linux:18 JVM/Robolectric,2 host CTest, Gradle app/test builds, JNI C++20/Werror и lint PASS.
Точные SHA, метрики, первичные провалы и границы: [validation](VULKAN_RENDER_VALIDATION.md).

## Новые файлы

- `graphics/vulkan/gpu.hpp`, `capabilities.cpp`: публичный Android Vulkan probe/Instance ownership.
- `graphics/vulkan/renderer.hpp`, `renderer.cpp`: реальный GPU/WSI renderer и telemetry.
- `graphics/vulkan/jni.cpp`: отдельный JNI API/handle registry без дублирования ProcessManager.
- `graphics/vulkan/shaders/triangle.vert`, `triangle.frag`, `shaders.hpp`: собственные GLSL/SPIR-V.
- `android-app/src/main/java/org/frostbox/app/gpu/NativeVulkan.kt`, `VulkanSession.kt`, `VulkanScreen.kt`:
  отдельный diagnostics mode и SurfaceView lifecycle.
- `android-app/src/androidTest/java/org/frostbox/app/runtime/VulkanDeviceTest.kt`: native device/pixels/lifecycle.
- `scripts/build-vulkan-shaders.py`, `scripts/validate-vulkan-runtime.py`: воспроизводимые build/evidence gates.
- `docs/VULKAN_GPU_ARCHITECTURE.md`, `VULKAN_DEVICE_CAPABILITIES.md`, `VULKAN_RENDER_VALIDATION.md`,
  `FROSTBOX_0.5.0_DEV1_CHANGELOG.md`: архитектура, actual data, результаты и список изменений.
- `docs/evidence/vulkan-poco-capabilities.json`, `vulkan-poco-render.json`, `vulkan-poco-lifecycle.json`:
  real app-UID snapshots, без подставленных capabilities.
- `third-party/SOURCE_BUNDLE.md`: состав соответствующих исходников в архиве.

## Изменённые существующие файлы

- `runtime/native/CMakeLists.txt`: добавлен отдельный Android target frostbox_vulkan, сохранены manager/host tests.
- `android-app/build.gradle.kts`: версия0.5.0-dev.1, versionCode9.
- `FrostModel.kt`: отдельная VulkanSession, lifecycle cleanup.
- `MainActivity.kt`: foreground/background signal GPU session, прежний launcher сохранён.
- `ui/FrostApp.kt`: отдельная Vulkan Diagnostics navigation ветка, сохранены WindowsScreen/Library.
- `README.md`, `README.en.md`, `CHANGELOG.md`, `ARCHITECTURE.md`, `TODO.md`, `docs/BUILD.md`, `docs/POCO.md`,
  `third-party/README.md`, `third-party/runtime-lock.json`: текущий статус, build/device commands и ABI аудит.

## Сохранено / не реализовано

Wine/Box64/GDI/font runtime assets побайтно совпадают с0.4 APK; никаких полных runtime rebuilds,
prefix/data очисток, root/SELinux изменений и автоматического запуска пользовательских EXE.
CI workflow уже компилирует новый Android native target; shader header checked-in, новый CI download
не требуется. GitHub workflow не запускался из этой сессии и не считается device qualification.

Windows Vulkan, Vulkan-enabled Wine build, Win32 WSI/Surface IPC, DXVK/VKD3D, Turnip, FPS/game optimization,
AHB/fd sharing, feature manifest и long-session/forced device-loss validation не реализованы.
В Wine всё ещё SONAME_LIBVULKAN undefined; Box64 native loader name libvulkan.so.1 требует отдельной
проверки против Android libvulkan.so; winefrost pVulkanInit/version48 callbacks отсутствуют.
Никаких утверждений о DXVK/игровом FPS. Следующий шаг изложен конкретно в архитектурном документе.
