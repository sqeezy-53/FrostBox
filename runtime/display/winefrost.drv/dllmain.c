/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Entry pattern from Wine wineandroid.drv, Copyright 2022 Jacek Caban for CodeWeavers. */
#include <stdarg.h>
#include "windef.h"
#include "winbase.h"
#include "wine/unixlib.h"
BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID reserved)
{
    if (reason != DLL_PROCESS_ATTACH) return TRUE;
    DisableThreadLibraryCalls(inst);
    return !__wine_init_unix_call();
}
