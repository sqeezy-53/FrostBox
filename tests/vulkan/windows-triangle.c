// SPDX-License-Identifier: MIT
// A real Win64 HWND / Vulkan draw+present fixture. Compiling this is not device qualification.
#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_WIN32_KHR
#include <vulkan/vulkan.h>
#include <stdint.h>
#include "triangle-spirv.h"
__declspec(dllimport) int printf(const char*,...);
__declspec(dllimport) int fflush(void*);
__declspec(dllimport) int strcmp(const char*,const char*);
__declspec(dllimport) void* LoadLibraryA(const char*);
__declspec(dllimport) void* GetProcAddress(void*,const char*);
__declspec(dllimport) int FreeLibrary(void*);
__declspec(dllimport) BOOL PeekMessageW(MSG*,HWND,UINT,UINT,UINT);
__declspec(dllimport) BOOL SetWindowPos(HWND,HWND,int,int,int,int,UINT);
int _fltused=0;
#define FUNCTIONS(X) X(vkCreateInstance) X(vkEnumerateInstanceExtensionProperties) X(vkDestroyInstance) \
X(vkCreateWin32SurfaceKHR) X(vkDestroySurfaceKHR) X(vkEnumeratePhysicalDevices) X(vkGetPhysicalDeviceProperties) \
X(vkGetPhysicalDeviceQueueFamilyProperties) X(vkGetPhysicalDeviceSurfaceSupportKHR) X(vkGetPhysicalDeviceSurfaceCapabilitiesKHR) \
X(vkGetPhysicalDeviceSurfaceFormatsKHR) X(vkGetPhysicalDeviceSurfacePresentModesKHR) X(vkCreateDevice) X(vkGetDeviceProcAddr) \
X(vkDestroyDevice) X(vkGetDeviceQueue) X(vkCreateSwapchainKHR) X(vkDestroySwapchainKHR) X(vkGetSwapchainImagesKHR) \
X(vkAcquireNextImageKHR) X(vkQueuePresentKHR) X(vkCreateImageView) X(vkDestroyImageView) X(vkCreateRenderPass) \
X(vkDestroyRenderPass) X(vkCreateFramebuffer) X(vkDestroyFramebuffer) X(vkCreateShaderModule) X(vkDestroyShaderModule) \
X(vkCreatePipelineLayout) X(vkDestroyPipelineLayout) X(vkCreateGraphicsPipelines) X(vkDestroyPipeline) \
X(vkCreateCommandPool) X(vkDestroyCommandPool) X(vkAllocateCommandBuffers) X(vkResetCommandBuffer) \
X(vkBeginCommandBuffer) X(vkEndCommandBuffer) X(vkCmdBeginRenderPass) X(vkCmdEndRenderPass) X(vkCmdBindPipeline) \
X(vkCmdSetViewport) X(vkCmdSetScissor) X(vkCmdPushConstants) X(vkCmdDraw) X(vkCreateSemaphore) \
X(vkDestroySemaphore) X(vkCreateFence) X(vkDestroyFence) X(vkWaitForFences) X(vkResetFences) X(vkQueueSubmit)
#define DECL(n) static PFN_##n n;
FUNCTIONS(DECL)
static PFN_vkGetInstanceProcAddr get;
#define LOAD(n) do{n=(PFN_##n)get(instance,#n);if(!n){printf("MISSING=%s\n",#n);goto cleanup;}}while(0)
#define DEVICE(n) do{n=(PFN_##n)vkGetDeviceProcAddr(device,#n);if(!n){printf("MISSING=%s\n",#n);goto cleanup;}}while(0)
#define CHECK(x) do{VkResult r=(x);if(r!=VK_SUCCESS){printf("FAILED=%s RESULT=%d\n",#x,r);goto cleanup;}}while(0)
static VkImage images[16];static VkImageView views[16];static VkFramebuffer framebuffers[16];static VkSemaphore finished[16];
static VkExtensionProperties extensions[512];static VkPhysicalDevice physicals[8];static VkQueueFamilyProperties families[32];static VkSurfaceFormatKHR formats[64];static VkPresentModeKHR modes[32];
static int quit=0;
typedef struct { POINT reserved,maxSize,maxPosition,minTrack,maxTrack; } FrostMinMax;
typedef struct { HWND hwnd,after;int x,y,cx,cy;UINT flags; } FrostWindowPos;
_Static_assert(sizeof(RECT)==16,"Win64 RECT ABI");
_Static_assert(sizeof(WNDCLASSEXW)==80,"Win64 class ABI");
_Static_assert(sizeof(MSG)==48,"Win64 MSG ABI");
_Static_assert(sizeof(FrostMinMax)==40,"Win64 MINMAXINFO ABI");
_Static_assert(sizeof(FrostWindowPos)==40,"Win64 WINDOWPOS ABI");
__declspec(dllimport) int GetSystemMetrics(int);
__declspec(dllimport) DWORD GetLastError(void);
static void geometry(HWND h,const char *stage) {
    RECT c={0},w={0};BOOL okc=GetClientRect(h,&c),okw=GetWindowRect(h,&w);
    printf("WIN32_GEOMETRY %s CLIENT_OK=%d WINDOW_OK=%d CLIENT=%ld,%ld,%ld,%ld WINDOW=%ld,%ld,%ld,%ld ERROR=%lu\n",stage,okc,okw,c.left,c.top,c.right,c.bottom,w.left,w.top,w.right,w.bottom,GetLastError());fflush(0);
}
static LRESULT window_proc(HWND window,UINT message,WPARAM w,LPARAM l){
    if(message==0x10){DestroyWindow(window);return 0;}
    if(message==2){quit=1;PostQuitMessage(0);return 0;}
    if(message==0x5)printf("WIN32_SIZE W=%u H=%u STATE=%llu\n",(unsigned)(l&65535),(unsigned)((l>>16)&65535),w);
    if(message==0x24){FrostMinMax *m=(void*)l;printf("WIN32_MINMAX BEFORE min=%ld,%ld max=%ld,%ld\n",m->minTrack.x,m->minTrack.y,m->maxTrack.x,m->maxTrack.y);}
    if(message==0x83){RECT *r=(void*)l;printf("WIN32_NCCALC BEFORE mode=%llu rect=%ld,%ld,%ld,%ld\n",w,r->left,r->top,r->right,r->bottom);}
    if(message==0x46){FrostWindowPos *p=(void*)l;printf("WIN32_WINDOWPOS x=%d y=%d cx=%d cy=%d flags=%u\n",p->x,p->y,p->cx,p->cy,p->flags);}
    LRESULT result=DefWindowProcW(window,message,w,l);
    if(message==0x24){FrostMinMax *m=(void*)l;printf("WIN32_MINMAX AFTER min=%ld,%ld max=%ld,%ld\n",m->minTrack.x,m->minTrack.y,m->maxTrack.x,m->maxTrack.y);}
    if(message==0x83){RECT *r=(void*)l;printf("WIN32_NCCALC AFTER mode=%llu rect=%ld,%ld,%ld,%ld\n",w,r->left,r->top,r->right,r->bottom);}
    return result;
}
typedef struct { long height,width,escapement,orientation,weight;unsigned char italic,underline,strikeOut,charSet,outPrecision,clipPrecision,quality,pitchAndFamily;WCHAR faceName[32]; } FrostLogFont;
typedef struct { UINT size;int border,scrollWidth,scrollHeight,captionWidth,captionHeight;FrostLogFont captionFont;int smallCaptionWidth,smallCaptionHeight;FrostLogFont smallCaptionFont;int menuWidth,menuHeight;FrostLogFont menuFont,statusFont,messageFont;int paddedBorder; } FrostNonClient;
_Static_assert(sizeof(FrostLogFont)==92,"Win64 LOGFONTW ABI");
_Static_assert(sizeof(FrostNonClient)==504,"Win64 NONCLIENTMETRICSW ABI");
__declspec(dllimport) BOOL SystemParametersInfoW(UINT,UINT,void*,UINT);
__declspec(dllimport) DWORD GetEnvironmentVariableA(const char*,char*,DWORD);
static int repair_diagnostic_metrics(void) {
    char authorized[2]={0};
    if(GetEnvironmentVariableA("FROSTBOX_REPAIR_DIAGNOSTIC_METRICS",authorized,sizeof(authorized))!=1||authorized[0]!='1')return 1;
    FrostNonClient metrics={0};metrics.size=sizeof(metrics);
    if(!SystemParametersInfoW(0x29,sizeof(metrics),&metrics,0)){printf("METRICS_GET_FAILED=%lu\n",GetLastError());return 0;}
    printf("METRICS_BEFORE CAPTION=%d SMALL=%d MENU=%d\n",metrics.captionHeight,metrics.smallCaptionHeight,metrics.menuHeight);
    int changed=0;
    if(metrics.captionHeight<=0||metrics.captionHeight>4096){metrics.captionHeight=18;changed=1;}
    if(metrics.smallCaptionHeight<=0||metrics.smallCaptionHeight>4096){metrics.smallCaptionHeight=15;changed=1;}
    if(metrics.menuHeight<=0||metrics.menuHeight>4096){metrics.menuHeight=18;changed=1;}
    if(changed&&!SystemParametersInfoW(0x2a,sizeof(metrics),&metrics,1)){printf("METRICS_SET_FAILED=%lu\n",GetLastError());return 0;}
    FrostNonClient verified={0};verified.size=sizeof(verified);
    if(!SystemParametersInfoW(0x29,sizeof(verified),&verified,0))return 0;
    printf("METRICS_AFTER CAPTION=%d SMALL=%d MENU=%d REPAIRED=%d\n",verified.captionHeight,verified.smallCaptionHeight,verified.menuHeight,changed);fflush(0);
    return verified.captionHeight>0&&verified.captionHeight<4096&&verified.smallCaptionHeight>0&&verified.smallCaptionHeight<4096&&verified.menuHeight>0&&verified.menuHeight<4096;
}
static int probe(void){
    int result=11,pending=0;void* loader=0;HWND window=0;VkInstance instance=0;VkDevice device=0;VkSurfaceKHR surface=0;VkSwapchainKHR chain=0;
    VkRenderPass pass=0;VkShaderModule vertex=0,fragment=0;VkPipelineLayout layout=0;VkPipeline pipeline=0;VkCommandPool pool=0;VkCommandBuffer command=0;VkSemaphore acquired=0;VkFence fence=0;
    uint32_t count=512,image_count=0,family=UINT32_MAX,draws=0;VkQueue queue=0;
    printf("FROSTBOX_WINDOWS_VULKAN_TRIANGLE BEGIN\n");loader=LoadLibraryA("vulkan-1.dll");if(!loader)goto cleanup;
    get=(PFN_vkGetInstanceProcAddr)GetProcAddress(loader,"vkGetInstanceProcAddr");if(!get)goto cleanup;
    LOAD(vkEnumerateInstanceExtensionProperties);LOAD(vkCreateInstance);CHECK(vkEnumerateInstanceExtensionProperties(0,&count,extensions));
    int supported=0;for(uint32_t i=0;i<count;i++)if(!strcmp(extensions[i].extensionName,"VK_KHR_win32_surface"))supported=1;
    printf("WIN32_SURFACE=%d\n",supported);if(!supported){result=44;goto cleanup;}
    if(!repair_diagnostic_metrics())goto cleanup;
    static const WCHAR name[]={'F','r','o','s','t','V','u','l','k','a','n',0};HANDLE module=GetModuleHandleW(0);
    WNDCLASSEXW wc={0};wc.size=sizeof(wc);wc.proc=window_proc;wc.instance=module;wc.name=name;
    if(!RegisterClassExW(&wc))goto cleanup;
    window=CreateWindowExW(0,name,name,0x00CF0000,0,0,320,240,0,0,module,0);if(!window)goto cleanup;ShowWindow(window,5);
    geometry(window,"created");printf("WIN32_METRICS FRAME=%d,%d CAPTION=%d MIN=%d,%d MAX=%d,%d\n",GetSystemMetrics(32),GetSystemMetrics(33),GetSystemMetrics(4),GetSystemMetrics(34),GetSystemMetrics(35),GetSystemMetrics(59),GetSystemMetrics(60));
    const char* names[]={"VK_KHR_surface","VK_KHR_win32_surface"};VkApplicationInfo app={VK_STRUCTURE_TYPE_APPLICATION_INFO};app.apiVersion=VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici={VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ici.pApplicationInfo=&app;ici.enabledExtensionCount=2;ici.ppEnabledExtensionNames=names;CHECK(vkCreateInstance(&ici,0,&instance));
    LOAD(vkDestroyInstance);LOAD(vkCreateWin32SurfaceKHR);LOAD(vkDestroySurfaceKHR);LOAD(vkEnumeratePhysicalDevices);LOAD(vkGetPhysicalDeviceProperties);LOAD(vkGetPhysicalDeviceQueueFamilyProperties);
    LOAD(vkGetPhysicalDeviceSurfaceSupportKHR);LOAD(vkGetPhysicalDeviceSurfaceCapabilitiesKHR);LOAD(vkGetPhysicalDeviceSurfaceFormatsKHR);LOAD(vkGetPhysicalDeviceSurfacePresentModesKHR);LOAD(vkCreateDevice);LOAD(vkGetDeviceProcAddr);
    VkWin32SurfaceCreateInfoKHR sci={VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR};sci.hinstance=module;sci.hwnd=window;CHECK(vkCreateWin32SurfaceKHR(instance,&sci,0,&surface));
    count=8;CHECK(vkEnumeratePhysicalDevices(instance,&count,physicals));if(!count||count>8)goto cleanup;VkPhysicalDevice physical=physicals[0];
    VkPhysicalDeviceProperties props;vkGetPhysicalDeviceProperties(physical,&props);printf("GPU=%s TYPE=%d\n",props.deviceName,props.deviceType);if(props.deviceType==VK_PHYSICAL_DEVICE_TYPE_CPU)goto cleanup;
    count=32;vkGetPhysicalDeviceQueueFamilyProperties(physical,&count,families);if(count>32)goto cleanup;
    for(uint32_t i=0;i<count;i++){VkBool32 supported_queue=0;CHECK(vkGetPhysicalDeviceSurfaceSupportKHR(physical,i,surface,&supported_queue));if(supported_queue&&families[i].queueCount&&(families[i].queueFlags&VK_QUEUE_GRAPHICS_BIT)){family=i;break;}}
    if(family==UINT32_MAX)goto cleanup;
    count=64;CHECK(vkGetPhysicalDeviceSurfaceFormatsKHR(physical,surface,&count,formats));if(!count||count>64)goto cleanup;
    VkFormat format=VK_FORMAT_UNDEFINED;for(uint32_t i=0;i<count;i++)if(formats[i].format==VK_FORMAT_R8G8B8A8_UNORM&&formats[i].colorSpace==VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)format=formats[i].format;if(format==VK_FORMAT_UNDEFINED)goto cleanup;
    count=32;CHECK(vkGetPhysicalDeviceSurfacePresentModesKHR(physical,surface,&count,modes));if(count>32)goto cleanup;
    int fifo=0;for(uint32_t i=0;i<count;i++)if(modes[i]==VK_PRESENT_MODE_FIFO_KHR)fifo=1;if(!fifo)goto cleanup;
    float priority=1;VkDeviceQueueCreateInfo qi={VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qi.queueFamilyIndex=family;qi.queueCount=1;qi.pQueuePriorities=&priority;
    const char* dext[]={"VK_KHR_swapchain"};VkDeviceCreateInfo dci={VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};dci.queueCreateInfoCount=1;dci.pQueueCreateInfos=&qi;dci.enabledExtensionCount=1;dci.ppEnabledExtensionNames=dext;CHECK(vkCreateDevice(physical,&dci,0,&device));
    DEVICE(vkDestroyDevice);DEVICE(vkGetDeviceQueue);DEVICE(vkCreateSwapchainKHR);DEVICE(vkDestroySwapchainKHR);DEVICE(vkGetSwapchainImagesKHR);DEVICE(vkAcquireNextImageKHR);DEVICE(vkQueuePresentKHR);
    DEVICE(vkCreateImageView);DEVICE(vkDestroyImageView);DEVICE(vkCreateRenderPass);DEVICE(vkDestroyRenderPass);DEVICE(vkCreateFramebuffer);DEVICE(vkDestroyFramebuffer);DEVICE(vkCreateShaderModule);DEVICE(vkDestroyShaderModule);
    DEVICE(vkCreatePipelineLayout);DEVICE(vkDestroyPipelineLayout);DEVICE(vkCreateGraphicsPipelines);DEVICE(vkDestroyPipeline);DEVICE(vkCreateCommandPool);DEVICE(vkDestroyCommandPool);DEVICE(vkAllocateCommandBuffers);
    DEVICE(vkResetCommandBuffer);DEVICE(vkBeginCommandBuffer);DEVICE(vkEndCommandBuffer);DEVICE(vkCmdBeginRenderPass);DEVICE(vkCmdEndRenderPass);DEVICE(vkCmdBindPipeline);DEVICE(vkCmdSetViewport);DEVICE(vkCmdSetScissor);DEVICE(vkCmdPushConstants);DEVICE(vkCmdDraw);
    DEVICE(vkCreateSemaphore);DEVICE(vkDestroySemaphore);DEVICE(vkCreateFence);DEVICE(vkDestroyFence);DEVICE(vkWaitForFences);DEVICE(vkResetFences);DEVICE(vkQueueSubmit);vkGetDeviceQueue(device,family,0,&queue);
    VkAttachmentDescription attachment={0};attachment.format=format;attachment.samples=VK_SAMPLE_COUNT_1_BIT;attachment.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR;attachment.storeOp=VK_ATTACHMENT_STORE_OP_STORE;attachment.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;attachment.finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference color={0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};VkSubpassDescription subpass={0};subpass.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;subpass.colorAttachmentCount=1;subpass.pColorAttachments=&color;
    VkSubpassDependency dependency={0};dependency.srcSubpass=VK_SUBPASS_EXTERNAL;dependency.dstSubpass=0;dependency.srcStageMask=dependency.dstStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;dependency.dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo rp={VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};rp.attachmentCount=1;rp.pAttachments=&attachment;rp.subpassCount=1;rp.pSubpasses=&subpass;rp.dependencyCount=1;rp.pDependencies=&dependency;CHECK(vkCreateRenderPass(device,&rp,0,&pass));
    VkShaderModuleCreateInfo shader={VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};shader.codeSize=sizeof(triangle_vert);shader.pCode=triangle_vert;CHECK(vkCreateShaderModule(device,&shader,0,&vertex));shader.codeSize=sizeof(triangle_frag);shader.pCode=triangle_frag;CHECK(vkCreateShaderModule(device,&shader,0,&fragment));
    VkPushConstantRange push={VK_SHADER_STAGE_VERTEX_BIT,0,12};VkPipelineLayoutCreateInfo pli={VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};pli.pushConstantRangeCount=1;pli.pPushConstantRanges=&push;CHECK(vkCreatePipelineLayout(device,&pli,0,&layout));
    VkPipelineShaderStageCreateInfo stages[2]={{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO},{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}};stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT;stages[0].module=vertex;stages[0].pName="main";stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT;stages[1].module=fragment;stages[1].pName="main";
    VkPipelineVertexInputStateCreateInfo vi={VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};VkPipelineInputAssemblyStateCreateInfo ia={VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo vs={VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};vs.viewportCount=vs.scissorCount=1;VkPipelineRasterizationStateCreateInfo rs={VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};rs.polygonMode=VK_POLYGON_MODE_FILL;rs.cullMode=VK_CULL_MODE_NONE;rs.lineWidth=1;
    VkPipelineMultisampleStateCreateInfo ms={VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;VkPipelineColorBlendAttachmentState ba={0};ba.colorWriteMask=15;VkPipelineColorBlendStateCreateInfo bs={VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};bs.attachmentCount=1;bs.pAttachments=&ba;
    VkDynamicState dynamics[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};VkPipelineDynamicStateCreateInfo dy={VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};dy.dynamicStateCount=2;dy.pDynamicStates=dynamics;
    VkGraphicsPipelineCreateInfo pc={VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};pc.stageCount=2;pc.pStages=stages;pc.pVertexInputState=&vi;pc.pInputAssemblyState=&ia;pc.pViewportState=&vs;pc.pRasterizationState=&rs;pc.pMultisampleState=&ms;pc.pColorBlendState=&bs;pc.pDynamicState=&dy;pc.layout=layout;pc.renderPass=pass;CHECK(vkCreateGraphicsPipelines(device,0,1,&pc,0,&pipeline));
    VkCommandPoolCreateInfo cpi={VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};cpi.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;cpi.queueFamilyIndex=family;CHECK(vkCreateCommandPool(device,&cpi,0,&pool));VkCommandBufferAllocateInfo cb={VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};cb.commandPool=pool;cb.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;cb.commandBufferCount=1;CHECK(vkAllocateCommandBuffers(device,&cb,&command));
    VkSemaphoreCreateInfo semaphore={VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};CHECK(vkCreateSemaphore(device,&semaphore,0,&acquired));VkFenceCreateInfo fc={VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};CHECK(vkCreateFence(device,&fc,0,&fence));
    for(unsigned generation=0;generation<2;generation++){
        RECT rect;if(!GetClientRect(window,&rect))goto cleanup;VkSurfaceCapabilitiesKHR caps;CHECK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical,surface,&caps));VkExtent2D extent={(uint32_t)(rect.right-rect.left),(uint32_t)(rect.bottom-rect.top)};
        printf("WSI_CAPS RECT=%ld,%ld CURRENT=%u,%u USAGE=%u ALPHA=%u MIN=%u MAX=%u\n",rect.right-rect.left,rect.bottom-rect.top,caps.currentExtent.width,caps.currentExtent.height,caps.supportedUsageFlags,caps.supportedCompositeAlpha,caps.minImageCount,caps.maxImageCount);
        if(caps.currentExtent.width!=UINT32_MAX)extent=caps.currentExtent;if(!extent.width||!extent.height||!(caps.supportedUsageFlags&VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT))goto cleanup;
        VkSwapchainCreateInfoKHR ci={VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};ci.surface=surface;ci.minImageCount=caps.minImageCount+1;if(caps.maxImageCount&&ci.minImageCount>caps.maxImageCount)ci.minImageCount=caps.maxImageCount;ci.imageFormat=format;ci.imageColorSpace=VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;ci.imageExtent=extent;ci.imageArrayLayers=1;ci.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;ci.preTransform=caps.currentTransform;ci.presentMode=VK_PRESENT_MODE_FIFO_KHR;ci.clipped=VK_TRUE;
        for(uint32_t a=1;a<=8;a<<=1)if(caps.supportedCompositeAlpha&a){ci.compositeAlpha=(VkCompositeAlphaFlagBitsKHR)a;break;}if(!ci.compositeAlpha)goto cleanup;CHECK(vkCreateSwapchainKHR(device,&ci,0,&chain));image_count=16;CHECK(vkGetSwapchainImagesKHR(device,chain,&image_count,images));if(!image_count||image_count>16)goto cleanup;
        for(uint32_t i=0;i<image_count;i++){VkImageViewCreateInfo view={VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};view.image=images[i];view.viewType=VK_IMAGE_VIEW_TYPE_2D;view.format=format;view.subresourceRange=(VkImageSubresourceRange){VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};CHECK(vkCreateImageView(device,&view,0,&views[i]));VkFramebufferCreateInfo fb={VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};fb.renderPass=pass;fb.attachmentCount=1;fb.pAttachments=&views[i];fb.width=extent.width;fb.height=extent.height;fb.layers=1;CHECK(vkCreateFramebuffer(device,&fb,0,&framebuffers[i]));CHECK(vkCreateSemaphore(device,&semaphore,0,&finished[i]));}
        for(unsigned frame=0;frame<60&&!quit;frame++){
            MSG message;while(PeekMessageW(&message,0,0,0,1)){TranslateMessage(&message);DispatchMessageW(&message);}if(quit)goto cleanup;
            uint32_t index;VkResult r=vkAcquireNextImageKHR(device,chain,5000000000ull,acquired,0,&index);if(r!=VK_SUCCESS&&r!=VK_SUBOPTIMAL_KHR){printf("ACQUIRE_RESULT=%d\n",r);goto cleanup;}if(index>=image_count)goto cleanup;
            CHECK(vkResetFences(device,1,&fence));CHECK(vkResetCommandBuffer(command,0));VkCommandBufferBeginInfo begin={VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};CHECK(vkBeginCommandBuffer(command,&begin));
            VkClearValue clear={{{0.02f,0.02f,0.1f,1}}};VkRenderPassBeginInfo start={VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};start.renderPass=pass;start.framebuffer=framebuffers[index];start.renderArea.extent=extent;start.clearValueCount=1;start.pClearValues=&clear;vkCmdBeginRenderPass(command,&start,VK_SUBPASS_CONTENTS_INLINE);vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
            VkViewport viewport={0,0,(float)extent.width,(float)extent.height,0,1};VkRect2D scissor={{0,0},extent};vkCmdSetViewport(command,0,1,&viewport);vkCmdSetScissor(command,0,1,&scissor);float parameters[]={draws*0.05f,(float)extent.width/extent.height,0};vkCmdPushConstants(command,layout,VK_SHADER_STAGE_VERTEX_BIT,0,12,parameters);vkCmdDraw(command,3,1,0,0);vkCmdEndRenderPass(command);CHECK(vkEndCommandBuffer(command));
            VkPipelineStageFlags wait=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;VkSubmitInfo submit={VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.waitSemaphoreCount=1;submit.pWaitSemaphores=&acquired;submit.pWaitDstStageMask=&wait;submit.commandBufferCount=1;submit.pCommandBuffers=&command;submit.signalSemaphoreCount=1;submit.pSignalSemaphores=&finished[index];CHECK(vkQueueSubmit(queue,1,&submit,fence));pending=1;
            VkPresentInfoKHR present={VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};present.waitSemaphoreCount=1;present.pWaitSemaphores=&finished[index];present.swapchainCount=1;present.pSwapchains=&chain;present.pImageIndices=&index;r=vkQueuePresentKHR(queue,&present);if(r!=VK_SUCCESS&&r!=VK_SUBOPTIMAL_KHR){printf("PRESENT_RESULT=%d\n",r);goto cleanup;}
            CHECK(vkWaitForFences(device,1,&fence,VK_TRUE,5000000000ull));pending=0;draws++;printf("WINDOWS_VULKAN_DRAW_PRESENT FRAME=%u GENERATION=%u SIZE=%ux%u\n",draws,generation,extent.width,extent.height);fflush(0);
        }
        // Device idle only at resize/teardown, not every frame; present wait payloads must be retired before destruction.
        PFN_vkDeviceWaitIdle idle=(PFN_vkDeviceWaitIdle)vkGetDeviceProcAddr(device,"vkDeviceWaitIdle");if(!idle||idle(device)!=VK_SUCCESS)goto cleanup;
        for(uint32_t i=0;i<image_count;i++){vkDestroyFramebuffer(device,framebuffers[i],0);framebuffers[i]=0;vkDestroyImageView(device,views[i],0);views[i]=0;vkDestroySemaphore(device,finished[i],0);finished[i]=0;}vkDestroySwapchainKHR(device,chain,0);chain=0;
        if(!generation&&!SetWindowPos(window,0,0,0,480,360,0x0006))goto cleanup;
    }
    if(draws==120){PostMessageW(window,0x10,0,0);MSG message;while(PeekMessageW(&message,0,0,0,1)){TranslateMessage(&message);DispatchMessageW(&message);}if(quit)result=42;}
cleanup:
    // If a submit/present is unresolved, process teardown is safer than destroying in-use resources.
    if(pending){printf("WINDOWS_VULKAN_UNRETIRED\n");fflush(0);ExitProcess(12);}
    if(device){PFN_vkDeviceWaitIdle idle=vkGetDeviceProcAddr?(PFN_vkDeviceWaitIdle)vkGetDeviceProcAddr(device,"vkDeviceWaitIdle"):0;if(idle&&idle(device)!=VK_SUCCESS)ExitProcess(12);
        for(uint32_t i=0;i<16;i++){if(framebuffers[i])vkDestroyFramebuffer(device,framebuffers[i],0);if(views[i])vkDestroyImageView(device,views[i],0);if(finished[i])vkDestroySemaphore(device,finished[i],0);}if(chain)vkDestroySwapchainKHR(device,chain,0);if(pool)vkDestroyCommandPool(device,pool,0);if(fence)vkDestroyFence(device,fence,0);if(acquired)vkDestroySemaphore(device,acquired,0);if(pipeline)vkDestroyPipeline(device,pipeline,0);if(layout)vkDestroyPipelineLayout(device,layout,0);if(vertex)vkDestroyShaderModule(device,vertex,0);if(fragment)vkDestroyShaderModule(device,fragment,0);if(pass)vkDestroyRenderPass(device,pass,0);if(vkDestroyDevice)vkDestroyDevice(device,0);}
    if(surface&&vkDestroySurfaceKHR)vkDestroySurfaceKHR(instance,surface,0);if(instance&&vkDestroyInstance)vkDestroyInstance(instance,0);if(window&&!quit)DestroyWindow(window);if(loader)FreeLibrary(loader);printf("FROSTBOX_WINDOWS_TRIANGLE_EXIT=%d DRAWS=%u\n",result,draws);fflush(0);return result;
}
void mainCRTStartup(void){ExitProcess((UINT)probe());}
