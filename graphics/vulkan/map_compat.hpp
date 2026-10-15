// SPDX-License-Identifier: MIT
// ABI declarations absent in NDK27 headers. Values/layout verified against
// Khronos Vulkan-Headers v1.3.296 vulkan_core.h, VK_EXT_map_memory_placed.
#pragma once
#ifndef VK_EXT_map_memory_placed
#define VK_EXT_map_memory_placed 1
#define VK_EXT_MAP_MEMORY_PLACED_EXTENSION_NAME "VK_EXT_map_memory_placed"
#define VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_FEATURES_EXT static_cast<VkStructureType>(1000272000)
#define VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_PROPERTIES_EXT static_cast<VkStructureType>(1000272001)
#define VK_STRUCTURE_TYPE_MEMORY_MAP_PLACED_INFO_EXT static_cast<VkStructureType>(1000272002)
#define VK_MEMORY_MAP_PLACED_BIT_EXT 0x00000001
struct VkPhysicalDeviceMapMemoryPlacedFeaturesEXT {
    VkStructureType sType;void* pNext;VkBool32 memoryMapPlaced,memoryMapRangePlaced,memoryUnmapReserve;
};
struct VkPhysicalDeviceMapMemoryPlacedPropertiesEXT {
    VkStructureType sType;void* pNext;VkDeviceSize minPlacedMemoryMapAlignment;
};
struct VkMemoryMapPlacedInfoEXT {
    VkStructureType sType;const void* pNext;void* pPlacedAddress;
};
static_assert(sizeof(VkPhysicalDeviceMapMemoryPlacedFeaturesEXT)==32);
static_assert(sizeof(VkPhysicalDeviceMapMemoryPlacedPropertiesEXT)==24);
static_assert(sizeof(VkMemoryMapPlacedInfoEXT)==24);
#endif
