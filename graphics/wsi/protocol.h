// SPDX-License-Identifier: MIT
// Fixed-width wire data, never Vulkan handles or addresses. Dedicated opaque-FD image only.
#pragma once
#include <stdint.h>
#define FROST_WSI_MAGIC 0x46575331u
#define FROST_WSI_USAGE (VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
struct FrostWsiImage {
    uint32_t magic, width, height, format, memory_type, frame;
    uint64_t allocation_size;
    uint8_t device_uuid[16], driver_uuid[16];
    uint32_t sync_fd; // 0: bounded fence+ACK fallback; 1: SCM_RIGHTS FD; 2: Vulkan signalled SYNC_FD (-1).
    uint32_t reserved;
};
#if defined(__cplusplus)
static_assert(sizeof(FrostWsiImage) == 72);
#else
_Static_assert(sizeof(struct FrostWsiImage) == 72, "Wire ABI");
#endif
