// SPDX-License-Identifier: MIT
#include "renderer.hpp"
#include "shaders.hpp"
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <deque>
#include <optional>
#include <time.h>
#include <unistd.h>
namespace frostbox::gpu {
namespace {
using Clock=std::chrono::steady_clock;
double millis(Clock::duration d) {return std::chrono::duration<double,std::milli>(d).count();}
double cpuMillis() { timespec t{};clock_gettime(CLOCK_THREAD_CPUTIME_ID,&t);return t.tv_sec*1000.0+t.tv_nsec/1e6; }
struct Stats {
    uint64_t frames=0,contexts=0,acquireTimeouts=0,outOfDate=0,suboptimal=0,gpuSamples=0,cpuSamples=0;
    double cpuSum=0,recordSum=0,gpuSum=0,initMs=0,firstMs=0;
    std::deque<double> intervals;
    Clock::time_point started=Clock::now(),lastPresent{};
    std::string caps="{}";
};
struct Context {
    std::unique_ptr<Instance> instance;
    std::shared_ptr<ANativeWindow> window;
    VkSurfaceKHR surface=VK_NULL_HANDLE;
    VkPhysicalDevice physical=VK_NULL_HANDLE;
    VkDevice device=VK_NULL_HANDLE;
    VkQueue queue=VK_NULL_HANDLE;
    uint32_t family=0,timestampBits=0;
    float timestampPeriod=0;
    VkSwapchainKHR swapchain=VK_NULL_HANDLE;
    VkExtent2D extent{},surfaceExtent{};
    float preRotation=0;
    VkFormat format=VK_FORMAT_UNDEFINED;
    VkRenderPass renderPass=VK_NULL_HANDLE;
    VkPipelineLayout layout=VK_NULL_HANDLE;
    VkPipeline pipeline=VK_NULL_HANDLE;
    VkShaderModule vertex=VK_NULL_HANDLE,fragment=VK_NULL_HANDLE;
    VkCommandPool pool=VK_NULL_HANDLE;
    VkCommandBuffer command=VK_NULL_HANDLE;
    VkQueryPool queries=VK_NULL_HANDLE;
    VkFence submitted=VK_NULL_HANDLE;
    VkSemaphore acquired=VK_NULL_HANDLE;
    bool pending=false,timestamps=false;
    std::vector<VkImage> images;
    std::vector<VkImageView> views;
    std::vector<VkFramebuffer> framebuffers;
    std::vector<VkSemaphore> finished;
    std::vector<VkFence> presented;
    std::vector<bool> presentPending;
    ~Context() {
        if(device) {
            // Retirement happens on the GPU worker, never the Android UI thread.
            // Present fences (maintenance1) cover WSI use; DeviceWaitIdle alone does not.
            for(size_t i=0;i<presented.size();i++)if(presentPending[i])vkWaitForFences(device,1,&presented[i],VK_TRUE,UINT64_MAX);
            vkDeviceWaitIdle(device);
            for(auto x:framebuffers)if(x)vkDestroyFramebuffer(device,x,nullptr);
            if(pipeline)vkDestroyPipeline(device,pipeline,nullptr);
            if(layout)vkDestroyPipelineLayout(device,layout,nullptr);
            if(vertex)vkDestroyShaderModule(device,vertex,nullptr);
            if(fragment)vkDestroyShaderModule(device,fragment,nullptr);
            if(renderPass)vkDestroyRenderPass(device,renderPass,nullptr);
            for(auto x:views)if(x)vkDestroyImageView(device,x,nullptr);
            if(queries)vkDestroyQueryPool(device,queries,nullptr);
            if(pool)vkDestroyCommandPool(device,pool,nullptr);
            if(submitted)vkDestroyFence(device,submitted,nullptr);
            if(acquired)vkDestroySemaphore(device,acquired,nullptr);
            for(auto x:finished)if(x)vkDestroySemaphore(device,x,nullptr);
            for(auto x:presented)if(x)vkDestroyFence(device,x,nullptr);
            if(swapchain)vkDestroySwapchainKHR(device,swapchain,nullptr);
            vkDestroyDevice(device,nullptr);
        }
        if(surface && instance)vkDestroySurfaceKHR(instance->handle,surface,nullptr);
    }
    void init(std::shared_ptr<ANativeWindow> native,int width,int height,Stats& stats) {
        window=std::move(native);instance=std::make_unique<Instance>(true);
        VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};sci.window=window.get();
        check(vkCreateAndroidSurfaceKHR(instance->handle,&sci,nullptr,&surface),"vkCreateAndroidSurfaceKHR");
        stats.caps=capabilities(instance->handle,instance->api,surface);
        uint32_t count=0;check(vkEnumeratePhysicalDevices(instance->handle,&count,nullptr),"devices");std::vector<VkPhysicalDevice> devices(count);check(vkEnumeratePhysicalDevices(instance->handle,&count,devices.data()),"devices");
        for(auto candidate:devices) {
            VkPhysicalDeviceProperties p{};vkGetPhysicalDeviceProperties(candidate,&p);
            if(p.deviceType==VK_PHYSICAL_DEVICE_TYPE_CPU)continue; // Never claim SwiftShader as hardware GPU.
            auto ext=extensions(candidate);if(!has(ext,VK_KHR_SWAPCHAIN_EXTENSION_NAME) || !has(ext,VK_EXT_SWAPCHAIN_MAINTENANCE_1_EXTENSION_NAME))continue;
            uint32_t qn=0;vkGetPhysicalDeviceQueueFamilyProperties(candidate,&qn,nullptr);std::vector<VkQueueFamilyProperties> q(qn);vkGetPhysicalDeviceQueueFamilyProperties(candidate,&qn,q.data());
            for(uint32_t j=0;j<qn;j++) {VkBool32 present=0;check(vkGetPhysicalDeviceSurfaceSupportKHR(candidate,j,surface,&present),"present queue");
                if((q[j].queueFlags&VK_QUEUE_GRAPHICS_BIT) && present && q[j].queueCount) { physical=candidate;family=j;timestampBits=q[j].timestampValidBits;timestampPeriod=p.limits.timestampPeriod;break; }
            }if(physical)break;
        }
        if(!physical)throw std::runtime_error("No hardware graphics+present GPU with swapchain and EXT_swapchain_maintenance1 for safe WSI retirement");
        if(instance->api<VK_API_VERSION_1_1)throw std::runtime_error("Renderer requires Vulkan 1.1 feature queries");
        VkPhysicalDeviceSwapchainMaintenance1FeaturesEXT maintenance{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SWAPCHAIN_MAINTENANCE_1_FEATURES_EXT};
        VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};features.pNext=&maintenance;vkGetPhysicalDeviceFeatures2(physical,&features);
        if(!maintenance.swapchainMaintenance1)throw std::runtime_error("swapchainMaintenance1 feature unavailable");
        float priority=1;VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qci.queueFamilyIndex=family;qci.queueCount=1;qci.pQueuePriorities=&priority;
        const char* ext[]={VK_KHR_SWAPCHAIN_EXTENSION_NAME,VK_EXT_SWAPCHAIN_MAINTENANCE_1_EXTENSION_NAME};
        VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};dci.pNext=&maintenance;dci.queueCreateInfoCount=1;dci.pQueueCreateInfos=&qci;dci.enabledExtensionCount=2;dci.ppEnabledExtensionNames=ext;
        check(vkCreateDevice(physical,&dci,nullptr,&device),"vkCreateDevice");vkGetDeviceQueue(device,family,0,&queue);
        VkSurfaceCapabilitiesKHR caps{};check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical,surface,&caps),"surface capabilities");
        if(!(caps.supportedUsageFlags&VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT))throw std::runtime_error("Surface lacks color attachment usage");
        extent=caps.currentExtent;
        if(extent.width==UINT32_MAX)extent={std::clamp(static_cast<uint32_t>(width),caps.minImageExtent.width,caps.maxImageExtent.width),std::clamp(static_cast<uint32_t>(height),caps.minImageExtent.height,caps.maxImageExtent.height)};
        if(!extent.width || !extent.height)throw std::runtime_error("Zero Surface extent");
        surfaceExtent=extent;
        switch(caps.currentTransform) {
            case VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR:break;
            case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:preRotation=1.5707963268f;std::swap(extent.width,extent.height);break;
            case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR:preRotation=3.1415926536f;break;
            case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR:preRotation=4.7123889804f;std::swap(extent.width,extent.height);break;
            default:throw std::runtime_error("Unsupported mirrored/inherit Surface transform");
        }
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical,surface,&count,nullptr),"formats");std::vector<VkSurfaceFormatKHR> formats(count);check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical,surface,&count,formats.data()),"formats");
        if(formats.empty())throw std::runtime_error("No Surface formats");
        auto chosen=formats.front();for(auto f:formats)if(f.format==VK_FORMAT_R8G8B8A8_UNORM && f.colorSpace==VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)chosen=f;
        if(chosen.format==VK_FORMAT_UNDEFINED)chosen.format=VK_FORMAT_R8G8B8A8_UNORM;format=chosen.format;
        VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};ci.surface=surface;ci.minImageCount=caps.minImageCount+1;
        if(caps.maxImageCount)ci.minImageCount=std::min(ci.minImageCount,caps.maxImageCount);
        ci.imageFormat=format;ci.imageColorSpace=chosen.colorSpace;ci.imageExtent=extent;ci.imageArrayLayers=1;ci.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;ci.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE;
        ci.preTransform=caps.currentTransform; // Matches current platform transform, queried on every recreation.
        for(auto alpha:{VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR})if(caps.supportedCompositeAlpha&alpha){ci.compositeAlpha=alpha;break;}
        ci.presentMode=VK_PRESENT_MODE_FIFO_KHR;ci.clipped=VK_TRUE;
        check(vkCreateSwapchainKHR(device,&ci,nullptr,&swapchain),"vkCreateSwapchainKHR");
        check(vkGetSwapchainImagesKHR(device,swapchain,&count,nullptr),"images");images.resize(count);check(vkGetSwapchainImagesKHR(device,swapchain,&count,images.data()),"images");
        views.resize(count);framebuffers.resize(count);finished.resize(count);presented.resize(count);presentPending.resize(count);
        VkAttachmentDescription attachment{};attachment.format=format;attachment.samples=VK_SAMPLE_COUNT_1_BIT;attachment.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR;attachment.storeOp=VK_ATTACHMENT_STORE_OP_STORE;attachment.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE;attachment.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;attachment.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;attachment.finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkAttachmentReference reference{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};VkSubpassDescription subpass{};subpass.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;subpass.colorAttachmentCount=1;subpass.pColorAttachments=&reference;
        VkSubpassDependency dependency{};dependency.srcSubpass=VK_SUBPASS_EXTERNAL;dependency.dstSubpass=0;dependency.srcStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;dependency.dstStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;dependency.dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};rp.attachmentCount=1;rp.pAttachments=&attachment;rp.subpassCount=1;rp.pSubpasses=&subpass;rp.dependencyCount=1;rp.pDependencies=&dependency;check(vkCreateRenderPass(device,&rp,nullptr,&renderPass),"render pass");
        VkSemaphoreCreateInfo sem{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};VkFenceCreateInfo fence{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        for(uint32_t i=0;i<count;i++) {
            VkImageViewCreateInfo iv{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};iv.image=images[i];iv.viewType=VK_IMAGE_VIEW_TYPE_2D;iv.format=format;iv.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};check(vkCreateImageView(device,&iv,nullptr,&views[i]),"image view");
            VkFramebufferCreateInfo fb{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};fb.renderPass=renderPass;fb.attachmentCount=1;fb.pAttachments=&views[i];fb.width=extent.width;fb.height=extent.height;fb.layers=1;check(vkCreateFramebuffer(device,&fb,nullptr,&framebuffers[i]),"framebuffer");
            check(vkCreateSemaphore(device,&sem,nullptr,&finished[i]),"present semaphore");check(vkCreateFence(device,&fence,nullptr,&presented[i]),"present fence");
        }
        check(vkCreateSemaphore(device,&sem,nullptr,&acquired),"acquire semaphore");fence.flags=VK_FENCE_CREATE_SIGNALED_BIT;check(vkCreateFence(device,&fence,nullptr,&submitted),"submit fence");
        VkCommandPoolCreateInfo pc{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pc.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;pc.queueFamilyIndex=family;check(vkCreateCommandPool(device,&pc,nullptr,&pool),"command pool");
        VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=pool;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=1;check(vkAllocateCommandBuffers(device,&ca,&command),"command buffer");
        timestamps=timestampBits>0 && timestampPeriod>0;
        if(timestamps){VkQueryPoolCreateInfo qp{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};qp.queryType=VK_QUERY_TYPE_TIMESTAMP;qp.queryCount=2;check(vkCreateQueryPool(device,&qp,nullptr,&queries),"timestamp queries");}
        VkShaderModuleCreateInfo module{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};module.codeSize=sizeof(triangle_vert);module.pCode=triangle_vert;check(vkCreateShaderModule(device,&module,nullptr,&vertex),"vertex shader");module.codeSize=sizeof(triangle_frag);module.pCode=triangle_frag;check(vkCreateShaderModule(device,&module,nullptr,&fragment),"fragment shader");
        VkPipelineShaderStageCreateInfo stages[2]{};stages[0].sType=stages[1].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT;stages[0].module=vertex;stages[0].pName="main";stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT;stages[1].module=fragment;stages[1].pName="main";
        VkPushConstantRange push{VK_SHADER_STAGE_VERTEX_BIT,0,12};VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};pl.pushConstantRangeCount=1;pl.pPushConstantRanges=&push;check(vkCreatePipelineLayout(device,&pl,nullptr,&layout),"pipeline layout");
        VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};vp.viewportCount=1;vp.scissorCount=1;
        VkPipelineRasterizationStateCreateInfo raster{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};raster.polygonMode=VK_POLYGON_MODE_FILL;raster.cullMode=VK_CULL_MODE_NONE;raster.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;raster.lineWidth=1;
        VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState blend{};blend.colorWriteMask=VK_COLOR_COMPONENT_R_BIT|VK_COLOR_COMPONENT_G_BIT|VK_COLOR_COMPONENT_B_BIT|VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};cb.attachmentCount=1;cb.pAttachments=&blend;
        VkDynamicState dynamic[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};VkPipelineDynamicStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};ds.dynamicStateCount=2;ds.pDynamicStates=dynamic;
        VkGraphicsPipelineCreateInfo pi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};pi.stageCount=2;pi.pStages=stages;pi.pVertexInputState=&vi;pi.pInputAssemblyState=&ia;pi.pViewportState=&vp;pi.pRasterizationState=&raster;pi.pMultisampleState=&ms;pi.pColorBlendState=&cb;pi.pDynamicState=&ds;pi.layout=layout;pi.renderPass=renderPass;
        check(vkCreateGraphicsPipelines(device,VK_NULL_HANDLE,1,&pi,nullptr,&pipeline),"graphics pipeline");stats.contexts++;
    }
    VkResult draw(Stats& s) {
        auto r=vkWaitForFences(device,1,&submitted,VK_TRUE,100000000);
        if(r==VK_TIMEOUT)return r;check(r,"submit fence wait");
        if(pending && timestamps) {
            uint64_t ticks[2]{};r=vkGetQueryPoolResults(device,queries,0,2,sizeof(ticks),ticks,sizeof(uint64_t),VK_QUERY_RESULT_64_BIT);
            if(r==VK_SUCCESS) {uint64_t mask=timestampBits==64?UINT64_MAX:(1ull<<timestampBits)-1;s.gpuSum+=((ticks[1]-ticks[0])&mask)*timestampPeriod/1e6;s.gpuSamples++;}
            else if(r!=VK_NOT_READY)check(r,"GPU query read");
        }pending=false;
        uint32_t index=0;r=vkAcquireNextImageKHR(device,swapchain,100000000,acquired,VK_NULL_HANDLE,&index);
        if(r==VK_TIMEOUT || r==VK_NOT_READY)return r;
        if(r==VK_ERROR_OUT_OF_DATE_KHR || r==VK_ERROR_SURFACE_LOST_KHR)return r;
        if(r==VK_SUBOPTIMAL_KHR)s.suboptimal++;else check(r,"vkAcquireNextImageKHR");
        if(presentPending[index]) {check(vkWaitForFences(device,1,&presented[index],VK_TRUE,UINT64_MAX),"present retirement");presentPending[index]=false;}
        check(vkResetFences(device,1,&presented[index]),"reset present fence");
        auto begin=Clock::now();auto cpu=cpuMillis();check(vkResetCommandBuffer(command,0),"reset commands");
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;check(vkBeginCommandBuffer(command,&bi),"begin commands");
        if(timestamps){vkCmdResetQueryPool(command,queries,0,2);vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queries,0);}
        VkClearValue clear{};clear.color={{0.025f,0.045f,0.085f,1}};
        VkRenderPassBeginInfo rb{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};rb.renderPass=renderPass;rb.framebuffer=framebuffers[index];rb.renderArea.extent=extent;rb.clearValueCount=1;rb.pClearValues=&clear;
        vkCmdBeginRenderPass(command,&rb,VK_SUBPASS_CONTENTS_INLINE);vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
        VkViewport viewport{0,0,static_cast<float>(extent.width),static_cast<float>(extent.height),0,1};VkRect2D scissor{{0,0},extent};vkCmdSetViewport(command,0,1,&viewport);vkCmdSetScissor(command,0,1,&scissor);
        float push[]={static_cast<float>(millis(Clock::now()-s.started)/2000.0),static_cast<float>(surfaceExtent.width)/surfaceExtent.height,preRotation};vkCmdPushConstants(command,layout,VK_SHADER_STAGE_VERTEX_BIT,0,sizeof(push),push);vkCmdDraw(command,3,1,0,0);vkCmdEndRenderPass(command);
        if(timestamps)vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queries,1);check(vkEndCommandBuffer(command),"end commands");check(vkResetFences(device,1,&submitted),"reset submit fence");
        VkPipelineStageFlags stage=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.waitSemaphoreCount=1;submit.pWaitSemaphores=&acquired;submit.pWaitDstStageMask=&stage;submit.commandBufferCount=1;submit.pCommandBuffers=&command;submit.signalSemaphoreCount=1;submit.pSignalSemaphores=&finished[index];
        check(vkQueueSubmit(queue,1,&submit,submitted),"vkQueueSubmit");pending=true;
        s.recordSum+=millis(Clock::now()-begin);
        VkSwapchainPresentFenceInfoEXT fence{VK_STRUCTURE_TYPE_SWAPCHAIN_PRESENT_FENCE_INFO_EXT};fence.swapchainCount=1;fence.pFences=&presented[index];
        VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};present.pNext=&fence;present.waitSemaphoreCount=1;present.pWaitSemaphores=&finished[index];present.swapchainCount=1;present.pSwapchains=&swapchain;present.pImageIndices=&index;
        r=vkQueuePresentKHR(queue,&present);
        // Present-fence signal is guaranteed for success/suboptimal/out-of-date/surface-lost.
        if(r==VK_SUCCESS || r==VK_SUBOPTIMAL_KHR || r==VK_ERROR_OUT_OF_DATE_KHR || r==VK_ERROR_SURFACE_LOST_KHR)presentPending[index]=true;
        s.cpuSum+=cpuMillis()-cpu;s.cpuSamples++;
        if(r==VK_SUCCESS || r==VK_SUBOPTIMAL_KHR) {
            auto now=Clock::now();if(!s.frames)s.firstMs=millis(now-s.started);s.frames++;
            if(s.lastPresent!=Clock::time_point{}) {s.intervals.push_back(millis(now-s.lastPresent));if(s.intervals.size()>256)s.intervals.pop_front();}s.lastPresent=now;
        }
        return r;
    }
};
std::string snapshot(const Stats& s,const Context* c,const char* state,const std::string& error="") {
    double sum=0;std::vector<double> intervals(s.intervals.begin(),s.intervals.end());for(double t:intervals)sum+=t;
    std::sort(intervals.begin(),intervals.end());
    std::ostringstream o;o<<"{\"schema\":1,\"uid\":"<<getuid()<<",\"pid\":"<<getpid()<<",\"state\":"<<quote(state)<<",\"error\":"<<quote(error)<<",\"capabilities\":"<<s.caps<<",\"presentedFrames\":"<<s.frames<<",\"swapchainCreations\":"<<s.contexts<<",\"deviceCreated\":"<<(c && c->device?"true":"false")<<",\"swapchainCreated\":"<<(c && c->swapchain?"true":"false")<<",\"width\":"<<(c?c->extent.width:0)<<",\"height\":"<<(c?c->extent.height:0)<<",\"surfaceWidth\":"<<(c?c->surfaceExtent.width:0)<<",\"surfaceHeight\":"<<(c?c->surfaceExtent.height:0)<<",\"presentMode\":2,\"targetCadenceHz\":60,\"maxSubmissionsInFlight\":1,\"presentFences\":true,\"initializationMs\":"<<s.initMs<<",\"timeToFirstFrameMs\":"<<s.firstMs<<",\"cpuRecordSubmitWallMsMean\":"<<(s.cpuSamples?s.recordSum/s.cpuSamples:0)<<",\"cpuThreadRecordSubmitPresentMsMean\":"<<(s.cpuSamples?s.cpuSum/s.cpuSamples:0)<<",\"gpuExecutionMsMean\":";
    if(s.gpuSamples)o<<s.gpuSum/s.gpuSamples;else o<<"null";
    o<<",\"gpuTimestampSamples\":"<<s.gpuSamples<<",\"gpuTimestampScope\":\"TOP_OF_PIPE to BOTTOM_OF_PIPE; not display latency\",\"presentCallIntervalsMs\":[";
    for(size_t i=0;i<intervals.size();i++){if(i)o<<',';o<<intervals[i];}o<<"],\"presentCallIntervalMsMean\":"<<(intervals.empty()?0:sum/intervals.size())<<",\"presentCallIntervalMsP95\":"<<(intervals.empty()?0:intervals[static_cast<size_t>((intervals.size()-1)*0.95)])<<",\"presentCallCadenceHz\":"<<(sum>0?1000*intervals.size()/sum:0)<<",\"acquireTimeouts\":"<<s.acquireTimeouts<<",\"outOfDateEvents\":"<<s.outOfDate<<",\"suboptimalEvents\":"<<s.suboptimal<<",\"droppedDisplayFrames\":null,\"gpuMemoryUsageBytes\":null,\"memoryReason\":\"No allocated vertex/textures; WSI memory managed by driver. Heap size is not application usage.\"}";return o.str();
}
}
Renderer::Renderer() {thread=std::thread(&Renderer::loop,this);}
Renderer::~Renderer() {{std::lock_guard guard(mutex);shutdown=true;wake.notify_all();}thread.join();}
void Renderer::window(ANativeWindow* owned,int w,int h) {
    std::shared_ptr<ANativeWindow> next(owned,[](ANativeWindow* p){if(p)ANativeWindow_release(p);});
    std::lock_guard guard(mutex);requested=std::move(next);width=w;height=h;generation++;wake.notify_all();
}
void Renderer::start(bool value) {std::lock_guard guard(mutex);if(value && !running)generation++;running=value;wake.notify_all();}
void Renderer::foreground(bool value) {std::lock_guard guard(mutex);visible=value;wake.notify_all();}
std::string Renderer::snapshot() {std::lock_guard guard(mutex);return report;}
void Renderer::loop() {
    std::unique_ptr<Context> context;uint64_t token=0,failedToken=UINT64_MAX;Stats stats;bool wasRunning=false;
    auto deadline=Clock::now();
    while(true) {
        std::shared_ptr<ANativeWindow> native;bool active,foreground,quit;int w,h;uint64_t current;
        {std::unique_lock guard(mutex);native=requested;active=running;foreground=visible;quit=shutdown;w=width;h=height;current=generation;}
        if(quit)break;
        if(active && !wasRunning) {stats=Stats{};failedToken=UINT64_MAX;}wasRunning=active;
        if(!active || !foreground || !native || w<=0 || h<=0) {
            context.reset();stats.lastPresent={};
            {std::unique_lock guard(mutex);report=::frostbox::gpu::snapshot(stats,nullptr,!active?"Stopped":!foreground?"Paused":"WaitingSurface");wake.wait_for(guard,std::chrono::milliseconds(250));}continue;
        }
        if(current==failedToken) {std::unique_lock guard(mutex);wake.wait_for(guard,std::chrono::milliseconds(250));continue;}
        try {
            if(!context || current!=token) {
                context.reset();auto begin=Clock::now();context=std::make_unique<Context>();context->init(native,w,h,stats);stats.lastPresent={};stats.initMs=millis(Clock::now()-begin);token=current;deadline=Clock::now();
                __android_log_print(ANDROID_LOG_INFO,"FrostBoxVulkan","VkDevice/swapchain ready %ux%u generation=%llu",context->extent.width,context->extent.height,static_cast<unsigned long long>(token));
            }
            // Surface changes are single-slot requests. Do not submit a fresh frame for a retired generation.
            {std::lock_guard guard(mutex);if(generation!=token || !running || !visible || shutdown)continue;}
            auto result=context->draw(stats);
            if(result==VK_ERROR_OUT_OF_DATE_KHR || result==VK_SUBOPTIMAL_KHR) {if(result==VK_ERROR_OUT_OF_DATE_KHR)stats.outOfDate++;else stats.suboptimal++;context.reset();}
            else if(result==VK_ERROR_SURFACE_LOST_KHR) {context.reset();throw std::runtime_error("VK_ERROR_SURFACE_LOST_KHR; recreate Android Surface before retry");}
            else if(result==VK_TIMEOUT || result==VK_NOT_READY)stats.acquireTimeouts++;
            else check(result,"vkQueuePresentKHR");
            {std::lock_guard guard(mutex);report=::frostbox::gpu::snapshot(stats,context.get(),"Running");}
            deadline+=std::chrono::nanoseconds(16666667);if(deadline<Clock::now())deadline=Clock::now()+std::chrono::nanoseconds(16666667);
            std::unique_lock guard(mutex);wake.wait_until(guard,deadline,[&]{return shutdown || generation!=token || !running || !visible;});
        } catch(const std::exception& e) {
            context.reset();failedToken=current;{std::lock_guard guard(mutex);report=::frostbox::gpu::snapshot(stats,nullptr,"Failed",e.what());}
            __android_log_print(ANDROID_LOG_ERROR,"FrostBoxVulkan","%s",e.what());
        }
    }
    context.reset();
}
}
