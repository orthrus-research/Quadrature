// The shim between Quadrature's Vulkan handoff and NVIDIA's NGX runtime, for DLSS Super Resolution.
//
// NGX's entry points are compiled into the program that calls them, from a library in NVIDIA's DLSS SDK, so
// Java cannot call them directly; this library links that one and offers a flat C interface the module's
// Java side calls through the Foreign Function and Memory API. It holds none of the SDK's material: it is
// compiled against an SDK the builder supplies (D19), and what it builds is never committed or put in the JAR.
//
// Every entry point returns an NGX result (NVSDK_NGX_Result_Success is 1; failures carry 0xBAD in their
// top bits); the shim's own refusals use 0xBAD1xxxx. Used from one thread, the game's render thread.

#include <vulkan/vulkan.h>
#include <nvsdk_ngx_vk.h>
#include <nvsdk_ngx_helpers.h>
#include <nvsdk_ngx_helpers_vk.h>

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <wchar.h>

#define QN_API extern "C" __declspec(dllexport)

namespace {

const unsigned NOT_INITIALIZED = 0xBAD10001u;
const unsigned NO_SUCH_SLOT = 0xBAD10002u;
const unsigned NO_ROOM = 0xBAD10003u;
const unsigned NO_FEATURE = 0xBAD10004u;

const int SLOTS = 4;

// One of the four images an evaluation reads or writes, as the Java side lays it out.
struct Image {
    uint64_t image;
    uint64_t view;
    int32_t format;
    int32_t aspect;
    uint32_t width;
    uint32_t height;
};
// Ngx.IMAGE on the Java side states the same layout; NgxTest holds it there.
static_assert(sizeof(Image) == 32 && offsetof(Image, format) == 16 && offsetof(Image, width) == 24,
              "The image layout must match Ngx.IMAGE.");

VkDevice device = VK_NULL_HANDLE;
NVSDK_NGX_Parameter *parameters = nullptr;
NVSDK_NGX_Handle *features[SLOTS] = {};
wchar_t featurePath[1024];
const wchar_t *featurePaths[1] = {featurePath};
NVSDK_NGX_FeatureCommonInfo common = {};

// Where NGX looks for nvngx_dlss.dll besides the program's own folder, and its logging, set before any call.
void setCommon(const wchar_t *dllFolder) {
    memset(&common, 0, sizeof(common));
    if (dllFolder != nullptr && dllFolder[0] != 0) {
        wcsncpy_s(featurePath, sizeof(featurePath) / sizeof(featurePath[0]), dllFolder, _TRUNCATE);
        common.PathListInfo.Path = featurePaths;
        common.PathListInfo.Length = 1;
    }
    common.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_ON;
}

NVSDK_NGX_FeatureDiscoveryInfo discovery(const char *projectId, const char *engineVersion, const wchar_t *dataPath) {
    NVSDK_NGX_FeatureDiscoveryInfo info = {};
    info.SDKVersion = NVSDK_NGX_Version_API;
    info.FeatureID = NVSDK_NGX_Feature_SuperSampling;
    info.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
    info.Identifier.v.ProjectDesc.ProjectId = projectId;
    info.Identifier.v.ProjectDesc.EngineType = NVSDK_NGX_ENGINE_TYPE_CUSTOM;
    info.Identifier.v.ProjectDesc.EngineVersion = engineVersion;
    info.ApplicationDataPath = dataPath;
    info.FeatureInfo = &common;
    return info;
}

// Writes the extensions' names into out, each ended by a newline, and the count into count.
unsigned names(const VkExtensionProperties *properties, uint32_t found, char *out, int capacity, int *count) {
    int at = 0;
    for (uint32_t i = 0; i < found; i++) {
        size_t length = strnlen(properties[i].extensionName, VK_MAX_EXTENSION_NAME_SIZE);
        if (at + (int) length + 2 > capacity) {
            return NO_ROOM;
        }
        memcpy(out + at, properties[i].extensionName, length);
        at += (int) length;
        out[at++] = '\n';
    }
    if (capacity > 0) {
        out[at < capacity ? at : capacity - 1] = 0;
    }
    *count = (int) found;
    return NVSDK_NGX_Result_Success;
}

NVSDK_NGX_Resource_VK resource(const Image &image, bool readWrite) {
    VkImageSubresourceRange range = {};
    range.aspectMask = (VkImageAspectFlags) image.aspect;
    range.baseMipLevel = 0;
    range.levelCount = 1;
    range.baseArrayLayer = 0;
    range.layerCount = 1;
    return NVSDK_NGX_Create_ImageView_Resource_VK((VkImageView) (uintptr_t) image.view, (VkImage) (uintptr_t) image.image,
        range, (VkFormat) image.format, image.width, image.height, readWrite);
}

} // namespace

// The instance extensions NGX needs for DLSS, asked before the Vulkan instance is made.
QN_API unsigned qn_instance_extensions(const char *projectId, const char *engineVersion, const wchar_t *dataPath,
                                       const wchar_t *dllFolder, char *out, int capacity, int *count) {
    setCommon(dllFolder);
    NVSDK_NGX_FeatureDiscoveryInfo info = discovery(projectId, engineVersion, dataPath);
    uint32_t found = 0;
    VkExtensionProperties *properties = nullptr;
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&info, &found, &properties);
    if (NVSDK_NGX_FAILED(result)) {
        return result;
    }
    return names(properties, found, out, capacity, count);
}

// The device extensions NGX needs for DLSS on a physical device, asked before the Vulkan device is made.
QN_API unsigned qn_device_extensions(const char *projectId, const char *engineVersion, const wchar_t *dataPath,
                                     const wchar_t *dllFolder, void *instance, void *physicalDevice, char *out,
                                     int capacity, int *count) {
    setCommon(dllFolder);
    NVSDK_NGX_FeatureDiscoveryInfo info = discovery(projectId, engineVersion, dataPath);
    uint32_t found = 0;
    VkExtensionProperties *properties = nullptr;
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements((VkInstance) instance,
        (VkPhysicalDevice) physicalDevice, &info, &found, &properties);
    if (NVSDK_NGX_FAILED(result)) {
        return result;
    }
    return names(properties, found, out, capacity, count);
}

// Starts NGX on the handoff's Vulkan device and takes its capability parameters.
QN_API unsigned qn_init(const char *projectId, const char *engineVersion, const wchar_t *dataPath, const wchar_t *dllFolder,
                        void *instance, void *physicalDevice, void *vkDevice, void *getInstanceProcAddr,
                        void *getDeviceProcAddr) {
    setCommon(dllFolder);
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_Init_with_ProjectID(projectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM, engineVersion,
        dataPath, (VkInstance) instance, (VkPhysicalDevice) physicalDevice, (VkDevice) vkDevice,
        (PFN_vkGetInstanceProcAddr) getInstanceProcAddr, (PFN_vkGetDeviceProcAddr) getDeviceProcAddr, &common,
        NVSDK_NGX_Version_API);
    if (NVSDK_NGX_FAILED(result)) {
        return result;
    }
    device = (VkDevice) vkDevice;
    result = NVSDK_NGX_VULKAN_GetCapabilityParameters(&parameters);
    if (NVSDK_NGX_FAILED(result)) {
        NVSDK_NGX_VULKAN_Shutdown1(device);
        device = VK_NULL_HANDLE;
        parameters = nullptr;
    }
    return result;
}

// Whether DLSS Super Resolution runs here, and the driver it would need if not.
QN_API unsigned qn_capability(int *available, int *needsUpdatedDriver, unsigned *minimumMajor, unsigned *minimumMinor) {
    if (parameters == nullptr) {
        return NOT_INITIALIZED;
    }
    int value = 0;
    *needsUpdatedDriver = 0;
    *minimumMajor = 0;
    *minimumMinor = 0;
    NVSDK_NGX_Result result = NVSDK_NGX_Parameter_GetI(parameters, NVSDK_NGX_Parameter_SuperSampling_Available, &value);
    *available = NVSDK_NGX_SUCCEED(result) ? value : 0;
    NVSDK_NGX_Parameter_GetI(parameters, NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, needsUpdatedDriver);
    NVSDK_NGX_Parameter_GetUI(parameters, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMajor, minimumMajor);
    NVSDK_NGX_Parameter_GetUI(parameters, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMinor, minimumMinor);
    return result;
}

// The render size DLSS asks for at an output size and quality mode.
QN_API unsigned qn_optimal(unsigned outputWidth, unsigned outputHeight, int quality, unsigned *renderWidth,
                           unsigned *renderHeight) {
    if (parameters == nullptr) {
        return NOT_INITIALIZED;
    }
    unsigned maxWidth = 0, maxHeight = 0, minWidth = 0, minHeight = 0;
    float sharpness = 0;
    return NGX_DLSS_GET_OPTIMAL_SETTINGS(parameters, outputWidth, outputHeight, (NVSDK_NGX_PerfQuality_Value) quality,
        renderWidth, renderHeight, &maxWidth, &maxHeight, &minWidth, &minHeight, &sharpness);
}

// Records the creation of a DLSS feature in a slot into a command buffer, which the caller submits and waits for.
QN_API unsigned qn_create(int slot, void *commandBuffer, unsigned renderWidth, unsigned renderHeight, unsigned outputWidth,
                          unsigned outputHeight, int quality, int preset, int flags) {
    if (parameters == nullptr) {
        return NOT_INITIALIZED;
    }
    if (slot < 0 || slot >= SLOTS) {
        return NO_SUCH_SLOT;
    }
    if (features[slot] != nullptr) {
        NVSDK_NGX_VULKAN_ReleaseFeature(features[slot]);
        features[slot] = nullptr;
    }
    // The preset is asked for in every quality mode, so it holds whichever mode the feature is made in.
    const char *hints[] = {NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality,
        NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance,
        NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraQuality};
    for (const char *hint : hints) {
        NVSDK_NGX_Parameter_SetUI(parameters, hint, (unsigned) preset);
    }
    NVSDK_NGX_DLSS_Create_Params create = {};
    create.Feature.InWidth = renderWidth;
    create.Feature.InHeight = renderHeight;
    create.Feature.InTargetWidth = outputWidth;
    create.Feature.InTargetHeight = outputHeight;
    create.Feature.InPerfQualityValue = (NVSDK_NGX_PerfQuality_Value) quality;
    create.InFeatureCreateFlags = flags;
    return NGX_VULKAN_CREATE_DLSS_EXT1(device, (VkCommandBuffer) commandBuffer, 1, 1, &features[slot], parameters, &create);
}

// Records one evaluation of a slot's feature: images[0] color, [1] depth, [2] motion, [3] the output it writes.
QN_API unsigned qn_evaluate(int slot, void *commandBuffer, const void *images, unsigned renderWidth, unsigned renderHeight,
                            float jitterX, float jitterY, float motionScaleX, float motionScaleY, int reset) {
    if (parameters == nullptr) {
        return NOT_INITIALIZED;
    }
    if (slot < 0 || slot >= SLOTS) {
        return NO_SUCH_SLOT;
    }
    if (features[slot] == nullptr) {
        return NO_FEATURE;
    }
    const Image *four = (const Image *) images;
    NVSDK_NGX_Resource_VK color = resource(four[0], false);
    NVSDK_NGX_Resource_VK depth = resource(four[1], false);
    NVSDK_NGX_Resource_VK motion = resource(four[2], false);
    NVSDK_NGX_Resource_VK output = resource(four[3], true);
    NVSDK_NGX_VK_DLSS_Eval_Params evaluation = {};
    evaluation.Feature.pInColor = &color;
    evaluation.Feature.pInOutput = &output;
    evaluation.pInDepth = &depth;
    evaluation.pInMotionVectors = &motion;
    evaluation.InJitterOffsetX = jitterX;
    evaluation.InJitterOffsetY = jitterY;
    evaluation.InRenderSubrectDimensions.Width = renderWidth;
    evaluation.InRenderSubrectDimensions.Height = renderHeight;
    evaluation.InReset = reset;
    evaluation.InMVScaleX = motionScaleX;
    evaluation.InMVScaleY = motionScaleY;
    return NGX_VULKAN_EVALUATE_DLSS_EXT((VkCommandBuffer) commandBuffer, features[slot], parameters, &evaluation);
}

// Releases a slot's feature; the caller has waited for the GPU to finish with it.
QN_API unsigned qn_release(int slot) {
    if (slot < 0 || slot >= SLOTS) {
        return NO_SUCH_SLOT;
    }
    if (features[slot] == nullptr) {
        return NVSDK_NGX_Result_Success;
    }
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_ReleaseFeature(features[slot]);
    features[slot] = nullptr;
    return result;
}

// Releases every feature and the parameters, and shuts NGX down on the device; the device itself stays.
QN_API unsigned qn_shutdown(void) {
    for (int slot = 0; slot < SLOTS; slot++) {
        qn_release(slot);
    }
    if (parameters != nullptr) {
        NVSDK_NGX_VULKAN_DestroyParameters(parameters);
        parameters = nullptr;
    }
    if (device == VK_NULL_HANDLE) {
        return NVSDK_NGX_Result_Success;
    }
    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_Shutdown1(device);
    device = VK_NULL_HANDLE;
    return result;
}

// NGX's text for a result, into out (UTF-16, ended by a zero); the shim's own refusals are named here.
QN_API void qn_result_text(unsigned result, wchar_t *out, int capacity) {
    if (capacity <= 0) {
        return;
    }
    const wchar_t *text;
    switch (result) {
        case NOT_INITIALIZED: text = L"the shim's NGX is not initialized"; break;
        case NO_SUCH_SLOT: text = L"no such feature slot"; break;
        case NO_ROOM: text = L"the extension names do not fit"; break;
        case NO_FEATURE: text = L"no feature in that slot"; break;
        default: text = GetNGXResultAsString((NVSDK_NGX_Result) result); break;
    }
    if (text == nullptr) {
        text = L"unknown";
    }
    wcsncpy_s(out, (size_t) capacity, text, _TRUNCATE);
}
