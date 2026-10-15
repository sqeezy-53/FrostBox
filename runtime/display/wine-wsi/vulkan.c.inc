/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Experimental Wine 11.19 interface48, compiled only in the separate WSI overlay.
 */
#include <dlfcn.h>
#include "wine/vulkan_driver.h"
static VkResult (*frost_surface_create)(VkInstance,VkSurfaceKHR *);
static VkBool32 (*frost_present_support)(VkPhysicalDevice,uint32_t);
static VkResult frost_vulkan_surface_create(struct client_surface *client,const struct vulkan_instance *instance,VkSurfaceKHR *surface)
{
    if (!client->hwnd) return VK_ERROR_SURFACE_LOST_KHR;
    return frost_surface_create(instance->host.instance,surface);
}
static VkBool32 frost_vulkan_support(struct vulkan_physical_device *physical,uint32_t family)
{
    return frost_present_support(physical->host.physical_device,family);
}
static void frost_map_instance(struct vulkan_instance_extensions *extensions)
{
    /* libfrostvk advertises Win32 only after authenticating a real host WSI session.
     * Win32u removes Win32 before native instance creation; native KHR_surface remains. */
    if (extensions->has_VK_KHR_win32_surface) extensions->has_VK_KHR_surface=1;
}
static void frost_map_device(struct vulkan_device_extensions *extensions)
{
    if (extensions->has_VK_KHR_external_memory_win32) extensions->has_VK_KHR_external_memory_fd=1;
    if (extensions->has_VK_KHR_external_memory_fd) extensions->has_VK_KHR_external_memory_win32=1;
    if (extensions->has_VK_KHR_external_semaphore_win32) extensions->has_VK_KHR_external_semaphore_fd=1;
    if (extensions->has_VK_KHR_external_semaphore_fd) extensions->has_VK_KHR_external_semaphore_win32=1;
    if (extensions->has_VK_KHR_external_fence_win32) extensions->has_VK_KHR_external_fence_fd=1;
    if (extensions->has_VK_KHR_external_fence_fd) extensions->has_VK_KHR_external_fence_win32=1;
}
static const struct vulkan_driver_funcs frost_vulkan_funcs={
    .p_vulkan_surface_create=frost_vulkan_surface_create,
    .p_get_physical_device_presentation_support=frost_vulkan_support,
    .p_map_instance_extensions=frost_map_instance,
    .p_map_device_extensions=frost_map_device,
};
static UINT frost_vulkan_init(UINT version,void *library,const struct vulkan_driver_funcs **out)
{
    if(version!=WINE_VULKAN_DRIVER_VERSION)return STATUS_REVISION_MISMATCH;
    frost_surface_create=dlsym(library,"frostCreateSurface");
    frost_present_support=dlsym(library,"frostPresentationSupport");
    if(!frost_surface_create||!frost_present_support)return STATUS_DLL_NOT_FOUND;
    *out=&frost_vulkan_funcs;return STATUS_SUCCESS;
}
static void frost_client_noop(struct client_surface *client) { (void)client; }
static void frost_client_update(struct client_surface *client)
{
    RECT rect={0};
    if(!client->hwnd || !NtUserGetClientRect(client->hwnd,&rect,96)) return;
    NtUserMapWindowPoints(client->hwnd,0,(POINT *)&rect,2,96);
    if(rect.right<=rect.left || rect.bottom<=rect.top) return;
    struct packet p={MAGIC,8,HandleToULong(client->hwnd),rect.left,rect.top,rect.right-rect.left,rect.bottom-rect.top,0};
    send_packet(&p,NULL);
}
static void frost_client_present(struct client_surface *client,HDC dc) { (void)client;(void)dc; }
static BOOL frost_client_color(struct client_surface *client,VkColorSpaceKHR color) { (void)client;return color==VK_COLOR_SPACE_SRGB_NONLINEAR_KHR; }
static const struct client_surface_funcs frost_client_funcs={
    .size=sizeof(struct client_surface),.destroy=frost_client_noop,.detach=frost_client_noop,
    .update=frost_client_update,.present=frost_client_present,.set_color_space=frost_client_color,
};
static struct client_surface *frost_create_client(HWND hwnd,int format,BOOL raw)
{
    /* Wine owns reference count, HWND detach and final allocation release. */
    struct client_surface *client=client_surface_create(&frost_client_funcs,hwnd,format,raw);
    if(client)frost_client_update(client);
    return client;
}
