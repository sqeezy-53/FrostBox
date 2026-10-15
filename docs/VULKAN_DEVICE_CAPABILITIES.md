# Реальные Vulkan capabilities · POCO F8 Ultra

Проверено 12 октября 2026, модель Android `25102PCBEG`, API36, ARM64, без root.
Первичный источник — native JNI probe внутри FrostBox application UID. Headless probe PID29882
(точное значение также сохранено в JSON), renderer PID30070. ADB shell `cmd gpu vkjson`
использовался только как предварительная справка; не заменяет доказательство под UID приложения.

Полный исходный JSON (local checkpoint artifact, not part of the source repository) содержит все 183 device extensions,
queue families, heaps/types и image-format results. JSON с настоящей Surface (local checkpoint artifact, not part of the source repository)
дополняет его WSI. Это снимки конкретного установленного драйвера, а не универсальная совместимость
модели телефона. После обновления HyperOS/драйвера нужно повторить probe.

| Property | Реальное значение |
|---|---|
| Physical device | Adreno (TM) 840 |
| Vendor | 20803 / 0x5143 |
| Device ID | 1141180977 / 0x44050a31 |
| Device type | 1, integrated GPU; не CPU/SwiftShader |
| Physical-device Vulkan API | 1.4.295, raw4210983 |
| Requested instance API | 1.1.0; renderer сознательно использует этот baseline |
| Driver ID | 8 |
| Driver name | Qualcomm Technologies Inc. Adreno Vulkan Driver |
| Driver version | raw2150932507, не декодируется как универсальная semver |
| Timestamp period | 52.0833 ns |
| Device extensions | 183 |

Driver info из vkGetPhysicalDeviceProperties2:
```text
Driver Build: 0a73ec43e7, I489da0dd30, 1770115872
Date: 02/03/26
Compiler Version: E031.50.19.18
Driver Branch:
```
Строка даты оставлена как возвращена драйвером, без предположения о формате месяца/дня.

## Критические capabilities

| Extension | Статус |
|---|---|
| VK_KHR_swapchain | Есть; настоящий swapchain создан |
| VK_EXT_swapchain_maintenance1 | Есть; feature запрос выполнен, present fences использованы |
| VK_ANDROID_external_memory_android_hardware_buffer | Есть; RGBA8 image-format compatibility query успешен |
| VK_KHR_external_memory_fd | Есть; импорт/экспорт fd в этом срезе не проверялся |
| VK_KHR_external_semaphore_fd / VK_KHR_external_fence_fd | Есть; межпроцессный sharing не проверялся |
| VK_KHR_timeline_semaphore / VK_KHR_synchronization2 | Есть; этот renderer использует binary sync |
| VK_EXT_descriptor_indexing / VK_KHR_dynamic_rendering | Есть; feature bits для DXVK не квалифицированы |
| VK_EXT_memory_budget | Отсутствует; GPU usage не подменяется размером heap |

Instance WSI extensions KHR_surface, KHR_android_surface, KHR_get_surface_capabilities2,
EXT_surface_maintenance1 проверяются перед созданием instance; реальное создание прошло.
Не включены сторонние ICD, Turnip или проприетарные драйверы в APK.

## Queues, memory и formats

| Family | Count | Flags | Graphics | Compute | Transfer | Timestamp bits |
|---|---:|---:|---|---|---|---:|
| 0 | 4 | 415 | Да | Да | Да | 48 |
| 1 | 1 | 6 | Нет | Да | Да | 48 |
| 2 | 1 | 8 | Нет | Нет | Нет | 48 |
| 3 | 1 | 1024 | Нет | Нет | Нет | 0 |

Graphics+present используется на family0. Present support запрашивался для каждой family на
реальной Surface; результат не означает возможность графического submission на non-graphics queue.
В headless JSON этот параметр null, потому что Surface ещё нет.

Heaps: 15 742 963 712 bytes flags1; 4 294 963 200 bytes flags1; 14 929 920 bytes flags9.
11 memory types с реальными propertyFlags/heapIndex находятся в JSON. Это capacities,
не свободная VRAM и не количество памяти, занятое FrostBox. PSS — отдельная Android process metric.

RGBA8 UNORM (format37), BGRA8 UNORM (44), D32 SFLOAT (126) вернули imageResult VK_SUCCESS,
max image extent16384×16384 для проверенных optimal-tiling usage. Это не проверка всех сочетаний
image usages, samples и external handle types. RGBA8 AHB query: result0,
externalMemoryFeatures7, compatibleHandleTypes1024; реальный AHB import/export не выполнялся.

Surface formats: 37, 43, 4, 97, 64; colorSpace0 (sRGB nonlinear). Present modes: MAILBOX(1), FIFO(2),
SHARED_DEMAND_REFRESH(1000111000), SHARED_CONTINUOUS_REFRESH(1000111001). Renderer выбирает FIFO.
minImageCount4, maxImageCount64; requested min5. currentTransform1 в portrait и 2 (90°) в landscape.
Portrait Surface/image1128×1300; landscape Surface2392×300, pre-rotated image300×2392.
Это размеры диагностического SurfaceView, не разрешение всего дисплея телефона.

## Граница вывода

VkDevice, графический pipeline, real swapchain и timestamp queries действительно выполнялись.
Этот probe не доказывает Wine Vulkan, wrapper coverage Box64, Win32 WSI, DirectX или DXVK.
Особенно нельзя автоматически перенести Android AHB/fd semantics на Windows NT handles.
Воспроизведение и измерения: [VULKAN_RENDER_VALIDATION.md](VULKAN_RENDER_VALIDATION.md).
