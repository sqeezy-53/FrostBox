// SPDX-License-Identifier: MIT
#pragma once
#include "protocol.h"
#define FROST_GUEST_VERSION 1u
#define FROST_GUEST_HELLO 1u
#define FROST_GUEST_PRESENT 2u
#define FROST_GUEST_RESET 3u
#define FROST_GUEST_MAX_IMAGES 4u
struct FrostGuestRequest {
    uint32_t version, operation, generation, slot;
    struct FrostWsiImage image;
};
struct FrostGuestReply {
    uint32_t version;
    int32_t result;
    uint32_t generation, frame, width, height, max_images, sync_supported;
    uint8_t device_uuid[16], driver_uuid[16];
};
