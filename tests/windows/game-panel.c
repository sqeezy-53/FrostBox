// SPDX-License-Identifier: MIT
// Debug-only long-running version of the qualified D3D11 fixture.
// The normal fixture remains 120 frames. This variant has mouse/key diagnostics.
#define FROSTBOX_CUBE_FRAMES 7200
#define FROSTBOX_PANEL_PROBE 1
#include "../d3d11/cube.c"
