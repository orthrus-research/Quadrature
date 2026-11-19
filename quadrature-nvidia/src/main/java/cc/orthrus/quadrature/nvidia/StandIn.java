package cc.orthrus.quadrature.nvidia;

import static cc.orthrus.quadrature.nvidia.VulkanDevice.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memAllocLong;
import static org.lwjgl.system.MemoryUtil.memAllocPointer;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_QUEUE_FAMILY_EXTERNAL;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * Vulkan's side of the round trip: a batch that waits for OpenGL's signal, takes the shared images from
 * the external queue family, runs the stand-in where DLSS would evaluate (or nothing, to time the handoff
 * alone), gives the images back to the external family and signals OpenGL.
 *
 * <p>The batches are recorded once per set of images, one per slot of a ring and per kind, and submitted
 * again each frame; each writes a timestamp before and after its work. A slot is reused only after its
 * fence says the last submission from it is done, and its timestamps are read before that.
 *
 * <p>With NGX given, the evaluation is DLSS Super Resolution instead of the stand-in. Its batch is recorded again
 * for every submission, since NGX records the frame's inputs into it, and two more timestamps bracket NGX's own
 * work. After DLSS, the stand-in runs on the output's first pixel alone, carrying the stamp the check reads,
 * unless the evaluation takes the scaler's picture, where that pixel is the picture's and the picture is the check.
 */
final class StandIn implements AutoCloseable {

    /** Slots of the ring: more than the frames after which OpenGL's results are read. */
    static final int RING = 6;

    private final VulkanDevice vulkan;
    private final SharedSemaphores semaphores;
    private final long shader;
    private final long setLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long linear;
    private final long nearest;
    private final long commandPool;
    private final long queryPool;
    /** Timestamps a slot holds: the batch's start and end, then NGX's start and end. */
    private static final int STAMPS = 4;
    /** NGX's feature slot the handoff uses. */
    private static final int FEATURE = 0;

    /** [0] the handoff alone, [1] with the stand-in, [2] with DLSS (recorded again for each submission); one per slot. */
    private final VkCommandBuffer[][] batches = new VkCommandBuffer[3][RING];
    /** DLSS through NGX, or null for the stand-in. */
    private final Ngx ngx;
    private int dlssQuality;
    private final int dlssPreset;
    private int dlssFlags;
    /** This frame's sub-pixel offset, in render pixels, as DLSS takes it. */
    private float jitterX;
    private float jitterY;
    /** Whether the stand-in carries the check's stamp into the output's first pixel after DLSS. */
    private boolean stamp = true;
    private SharedImage color;
    private SharedImage depth;
    private SharedImage motion;
    private SharedImage output;
    private java.lang.foreign.MemorySegment ngxImages;
    /** Set by a new feature and by {@link #resetHistory}; DLSS's next evaluation starts its history afresh. */
    private boolean reset = true;
    private final long[] fences = new long[RING];
    private final boolean[] submitted = new boolean[RING];

    // Kept for every submission, so a frame allocates nothing.
    private final VkSubmitInfo submit;
    private final VkTimelineSemaphoreSubmitInfo timelineValues;
    private final LongBuffer waitSemaphore;
    private final LongBuffer signalSemaphore;
    private final LongBuffer waitValue;
    private final LongBuffer signalValue;
    private final IntBuffer waitStage;
    private final PointerBuffer batch;
    private final LongBuffer results;

    /**
     * @param ngx        DLSS through NGX, already started on the device, or null to evaluate with the stand-in
     * @param dlssQuality NGX's quality mode, {@link Ngx#PERFORMANCE} for instance
     * @param dlssPreset NGX's render preset (10 J, 11 K, 12 L, 13 M; 0 its default)
     * @param dlssFlags  NGX's feature flags
     */
    StandIn(VulkanDevice vulkan, SharedSemaphores semaphores, Ngx ngx, int dlssQuality, int dlssPreset, int dlssFlags) {
        this.vulkan = vulkan;
        this.semaphores = semaphores;
        this.ngx = ngx;
        this.dlssQuality = dlssQuality;
        this.dlssPreset = dlssPreset;
        this.dlssFlags = dlssFlags;
        try (MemoryStack stack = stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            ByteBuffer code = spirv();
            try {
                check(vkCreateShaderModule(vulkan.device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),
                    null, handle), "vkCreateShaderModule");
            } finally {
                memFree(code);
            }
            shader = handle.get(0);

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
            for (int i = 0; i < 4; i++) {
                bindings.get(i).binding(i).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                    .descriptorType(i < 3 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            }
            check(vkCreateDescriptorSetLayout(vulkan.device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                .pBindings(bindings), null, handle), "vkCreateDescriptorSetLayout");
            setLayout = handle.get(0);

            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(8);
            check(vkCreatePipelineLayout(vulkan.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, handle), "vkCreatePipelineLayout");
            pipelineLayout = handle.get(0);

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().layout(pipelineLayout).stage().sType$Default()
                .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
            check(vkCreateComputePipelines(vulkan.device, VK_NULL_HANDLE, pipelineInfo, null, handle),
                "vkCreateComputePipelines");
            pipeline = handle.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(3);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            check(vkCreateDescriptorPool(vulkan.device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1)
                .pPoolSizes(sizes), null, handle), "vkCreateDescriptorPool");
            descriptorPool = handle.get(0);
            check(vkAllocateDescriptorSets(vulkan.device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(descriptorPool).pSetLayouts(stack.longs(setLayout)), handle), "vkAllocateDescriptorSets");
            descriptorSet = handle.get(0);

            linear = sampler(stack, VK_FILTER_LINEAR);
            nearest = sampler(stack, VK_FILTER_NEAREST);

            check(vkCreateCommandPool(vulkan.device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(vulkan.queueFamily), null, handle),
                "vkCreateCommandPool");
            commandPool = handle.get(0);
            PointerBuffer buffers = stack.mallocPointer(3 * RING);
            check(vkAllocateCommandBuffers(vulkan.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(3 * RING), buffers),
                "vkAllocateCommandBuffers");
            for (int kind = 0; kind < 3; kind++) {
                for (int slot = 0; slot < RING; slot++) {
                    batches[kind][slot] = new VkCommandBuffer(buffers.get(kind * RING + slot), vulkan.device);
                }
            }
            check(vkCreateQueryPool(vulkan.device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(STAMPS * RING), null, handle), "vkCreateQueryPool");
            queryPool = handle.get(0);
            for (int slot = 0; slot < RING; slot++) {
                check(vkCreateFence(vulkan.device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle),
                    "vkCreateFence");
                fences[slot] = handle.get(0);
            }
        }
        waitSemaphore = memAllocLong(1).put(0, semaphores.vkToVulkan);
        signalSemaphore = memAllocLong(1).put(0, semaphores.vkToOpenGl);
        waitValue = memAllocLong(1);
        signalValue = memAllocLong(1);
        // The whole batch waits, its first timestamp included, so that timestamp marks when OpenGL's signal arrived.
        waitStage = memAllocInt(1).put(0, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
        batch = memAllocPointer(1);
        results = memAllocLong(2 * STAMPS);
        timelineValues = VkTimelineSemaphoreSubmitInfo.calloc().sType$Default().pWaitSemaphoreValues(waitValue)
            .pSignalSemaphoreValues(signalValue);
        submit = VkSubmitInfo.calloc().sType$Default().waitSemaphoreCount(1).pWaitSemaphores(waitSemaphore)
            .pWaitDstStageMask(waitStage).pCommandBuffers(batch).pSignalSemaphores(signalSemaphore);
        if (semaphores.timeline) {
            submit.pNext(timelineValues.address());
        }
    }

    private long sampler(MemoryStack stack, int filter) {
        LongBuffer handle = stack.mallocLong(1);
        check(vkCreateSampler(vulkan.device, VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(filter)
            .minFilter(filter).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            .maxLod(0), null, handle), "vkCreateSampler");
        return handle.get(0);
    }

    private static ByteBuffer spirv() {
        try (InputStream in = StandIn.class.getResourceAsStream("standin.spv")) {
            if (in == null) {
                throw new IllegalStateException("The stand-in's SPIR-V is missing from the module.");
            }
            byte[] bytes = in.readAllBytes();
            return MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Points the stand-in at a new set of images and records every batch again. Nothing may be in flight:
     * the caller has waited for the queue.
     */
    void bind(SharedImage color, SharedImage depth, SharedImage motion, SharedImage output) {
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            write(writes.get(0), stack, 0, linear, color.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            write(writes.get(1), stack, 1, nearest, depth.view, VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            write(writes.get(2), stack, 2, linear, motion.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            write(writes.get(3), stack, 3, VK_NULL_HANDLE, output.view, VK_IMAGE_LAYOUT_GENERAL,
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            vkUpdateDescriptorSets(vulkan.device, writes, null);
        }
        for (int kind = 0; kind < 2; kind++) {
            for (int slot = 0; slot < RING; slot++) {
                record(batches[kind][slot], slot, kind == 1, color, depth, motion, output);
            }
        }
        this.color = color;
        this.depth = depth;
        this.motion = motion;
        this.output = output;
        if (ngx != null) {
            createFeature();
            ngxImages = ngx.images(color, depth, motion, output);
            reset = true;
        }
    }

    /** Makes the DLSS feature for the images' sizes, replacing any earlier one; nothing is in flight. */
    private void createFeature() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer handle = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(vulkan.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), handle),
                "vkAllocateCommandBuffers");
            VkCommandBuffer commands = new VkCommandBuffer(handle.get(0), vulkan.device);
            try {
                check(vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
                ngx.create(FEATURE, commands.address(), color.width, color.height, output.width, output.height, dlssQuality,
                    dlssPreset, dlssFlags);
                check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
                check(vkQueueSubmit(vulkan.queue, VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(commands)), VK_NULL_HANDLE), "vkQueueSubmit");
                check(vkQueueWaitIdle(vulkan.queue), "vkQueueWaitIdle");
            } finally {
                vkFreeCommandBuffers(vulkan.device, commandPool, commands);
            }
        }
    }

    /** DLSS's next evaluation starts its history afresh, as after a world change. */
    void resetHistory() {
        reset = true;
    }

    /** The quality mode and flags the next {@link #bind} makes DLSS's feature with. */
    void feature(int quality, int flags) {
        dlssQuality = quality;
        dlssFlags = flags;
    }

    /** The next submission's sub-pixel offset in render pixels, and whether it has no history to draw on. */
    void inputs(double offsetX, double offsetY, boolean noHistory) {
        jitterX = (float) offsetX;
        jitterY = (float) offsetY;
        if (noHistory) {
            reset = true;
        }
    }

    /** Whether the stand-in writes the check's stamp into the output's first pixel after DLSS. */
    void stamp(boolean carried) {
        stamp = carried;
    }

    /** Whether the evaluation is DLSS. */
    boolean dlss() {
        return ngx != null;
    }

    private void write(VkWriteDescriptorSet write, MemoryStack stack, int binding, long sampler, long view, int layout,
                       int type) {
        VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack);
        image.get(0).sampler(sampler).imageView(view).imageLayout(layout);
        write.sType$Default().dstSet(descriptorSet).dstBinding(binding).descriptorCount(1).descriptorType(type)
            .pImageInfo(image);
    }

    private void record(VkCommandBuffer commands, int slot, boolean evaluates, SharedImage color, SharedImage depth,
                        SharedImage motion, SharedImage output) {
        try (MemoryStack stack = stackPush()) {
            check(vkResetCommandBuffer(commands, 0), "vkResetCommandBuffer");
            check(vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack).sType$Default()),
                "vkBeginCommandBuffer");
            vkCmdResetQueryPool(commands, queryPool, STAMPS * slot, STAMPS);
            vkCmdWriteTimestamp(commands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, STAMPS * slot);
            // Acquire from the external family, in the layouts OpenGL's signal left the images in. The output's
            // contents are not kept: the stand-in writes every pixel.
            VkImageMemoryBarrier.Buffer acquire = VkImageMemoryBarrier.calloc(4, stack);
            barrier(acquire.get(0), color, VK_QUEUE_FAMILY_EXTERNAL, vulkan.queueFamily, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, VK_ACCESS_SHADER_READ_BIT);
            barrier(acquire.get(1), depth, VK_QUEUE_FAMILY_EXTERNAL, vulkan.queueFamily,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL, 0,
                VK_ACCESS_SHADER_READ_BIT);
            barrier(acquire.get(2), motion, VK_QUEUE_FAMILY_EXTERNAL, vulkan.queueFamily, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, VK_ACCESS_SHADER_READ_BIT);
            barrier(acquire.get(3), output, VK_QUEUE_FAMILY_EXTERNAL, vulkan.queueFamily, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null,
                null, acquire);
            if (evaluates) {
                vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
                vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(output.width, output.height));
                vkCmdDispatch(commands, (output.width + 7) / 8, (output.height + 7) / 8, 1);
            }
            // Release to the external family, in the layouts OpenGL's wait names.
            VkImageMemoryBarrier.Buffer release = VkImageMemoryBarrier.calloc(4, stack);
            barrier(release.get(0), color, vulkan.queueFamily, VK_QUEUE_FAMILY_EXTERNAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, 0);
            barrier(release.get(1), depth, vulkan.queueFamily, VK_QUEUE_FAMILY_EXTERNAL,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL, 0, 0);
            barrier(release.get(2), motion, vulkan.queueFamily, VK_QUEUE_FAMILY_EXTERNAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, 0);
            barrier(release.get(3), output, vulkan.queueFamily, VK_QUEUE_FAMILY_EXTERNAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_SHADER_WRITE_BIT, 0);
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null,
                null, release);
            vkCmdWriteTimestamp(commands, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, STAMPS * slot + 1);
            check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
        }
    }

    /**
     * Records a slot's DLSS batch for this submission: take the images, evaluate between NGX's two timestamps, carry
     * the stamp into the output's first pixel with the stand-in, give the images back.
     */
    private void recordDlss(VkCommandBuffer commands, int slot) {
        try (MemoryStack stack = stackPush()) {
            check(vkResetCommandBuffer(commands, 0), "vkResetCommandBuffer");
            check(vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
            vkCmdResetQueryPool(commands, queryPool, STAMPS * slot, STAMPS);
            vkCmdWriteTimestamp(commands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, STAMPS * slot);
            VkImageMemoryBarrier.Buffer acquire = VkImageMemoryBarrier.calloc(4, stack);
            barrier(acquire.get(0), color, VK_QUEUE_FAMILY_EXTERNAL, vulkan.queueFamily, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, VK_ACCESS_SHADER_READ_BIT);
            barrier(acquire.get(1), depth, VK_QUEUE_FAMILY_EXTERNAL, vulkan.queueFamily,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL, 0,
                VK_ACCESS_SHADER_READ_BIT);
            barrier(acquire.get(2), motion, VK_QUEUE_FAMILY_EXTERNAL, vulkan.queueFamily, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, VK_ACCESS_SHADER_READ_BIT);
            barrier(acquire.get(3), output, VK_QUEUE_FAMILY_EXTERNAL, vulkan.queueFamily, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null,
                null, acquire);
            // Written once everything before it is done, so the pair below brackets NGX's commands alone.
            vkCmdWriteTimestamp(commands, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, STAMPS * slot + 2);
            int result = ngx.evaluate(FEATURE, commands.address(), ngxImages, color.width, color.height, jitterX, jitterY,
                reset);
            reset = false;
            ngx.check("evaluation", result);
            vkCmdWriteTimestamp(commands, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, STAMPS * slot + 3);
            if (stamp) {
                // DLSS leaves the images in the states it found them in; the stand-in then writes the stamp's pixel.
                vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                    org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT), null, null);
                vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
                vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(1, 1));
                vkCmdDispatch(commands, 1, 1, 1);
            }
            VkImageMemoryBarrier.Buffer release = VkImageMemoryBarrier.calloc(4, stack);
            barrier(release.get(0), color, vulkan.queueFamily, VK_QUEUE_FAMILY_EXTERNAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, 0);
            barrier(release.get(1), depth, vulkan.queueFamily, VK_QUEUE_FAMILY_EXTERNAL,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL, 0, 0);
            barrier(release.get(2), motion, vulkan.queueFamily, VK_QUEUE_FAMILY_EXTERNAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, 0);
            barrier(release.get(3), output, vulkan.queueFamily, VK_QUEUE_FAMILY_EXTERNAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_SHADER_WRITE_BIT, 0);
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null,
                null, release);
            vkCmdWriteTimestamp(commands, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, STAMPS * slot + 1);
            check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
        }
    }

    private static void barrier(VkImageMemoryBarrier barrier, SharedImage image, int fromFamily, int toFamily, int oldLayout,
                                int newLayout, int srcAccess, int dstAccess) {
        barrier.sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess).oldLayout(oldLayout).newLayout(newLayout)
            .srcQueueFamilyIndex(fromFamily).dstQueueFamilyIndex(toFamily).image(image.image);
        int aspect = image.depth ? VK_IMAGE_ASPECT_DEPTH_BIT | (image.stencil ? VK_IMAGE_ASPECT_STENCIL_BIT : 0)
            : VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
    }

    /**
     * Makes a slot ready for a new submission: waits for its last one to finish. Returns the nanoseconds the
     * client waited, usually none, since OpenGL has long since waited for that batch on the GPU.
     */
    long reclaim(int slot) {
        if (!submitted[slot]) {
            return 0;
        }
        long start = System.nanoTime();
        check(vkWaitForFences(vulkan.device, fences[slot], true, 5_000_000_000L), "vkWaitForFences");
        long waited = System.nanoTime() - start;
        check(vkResetFences(vulkan.device, fences[slot]), "vkResetFences");
        submitted[slot] = false;
        return waited;
    }

    /**
     * The slot's timestamps in device ticks, into {@code ticks}, if the GPU has written them; never waits. [0] and [1]
     * bracket the batch; [2] and [3] bracket NGX's work, or are -1 where the batch had none. Returns false when the
     * batch's own are not there yet.
     */
    boolean timestamps(int slot, long[] ticks) {
        int result = vkGetQueryPoolResults(vulkan.device, queryPool, STAMPS * slot, STAMPS, results, 16,
            VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
        if ((result != VK_SUCCESS && result != VK_NOT_READY) || results.get(1) == 0 || results.get(3) == 0) {
            return false;
        }
        ticks[0] = results.get(0) & vulkan.timestampMask;
        ticks[1] = results.get(2) & vulkan.timestampMask;
        boolean evaluated = results.get(5) != 0 && results.get(7) != 0;
        ticks[2] = evaluated ? results.get(4) & vulkan.timestampMask : -1;
        ticks[3] = evaluated ? results.get(6) & vulkan.timestampMask : -1;
        return true;
    }

    /**
     * Waits up to {@code timeoutNanos} for a slot's last batch to finish; returns whether it did. A batch that does
     * not finish has not received OpenGL's signal.
     */
    boolean finished(int slot, long timeoutNanos) {
        int result = vkWaitForFences(vulkan.device, fences[slot], true, timeoutNanos);
        if (result == VK_TIMEOUT) {
            return false;
        }
        check(result, "vkWaitForFences");
        return true;
    }

    /** Signals a timeline value from the client, to let a batch whose OpenGL signal never came run out. */
    void signalFromClient(long value) {
        try (MemoryStack stack = stackPush()) {
            check(org.lwjgl.vulkan.VK12.vkSignalSemaphore(vulkan.device, org.lwjgl.vulkan.VkSemaphoreSignalInfo.calloc(stack)
                .sType$Default().semaphore(semaphores.vkToVulkan).value(value)), "vkSignalSemaphore");
        }
    }

    /** Submits a slot's batch: wait for {@code wait}, signal {@code signal} (values ignored for binary semaphores). */
    void submit(int slot, boolean evaluates, long wait, long signal) {
        waitValue.put(0, wait);
        signalValue.put(0, signal);
        VkCommandBuffer commands = batches[evaluates ? 1 : 0][slot];
        if (evaluates && ngx != null) {
            commands = batches[2][slot];
            recordDlss(commands, slot);
        }
        batch.put(0, commands);
        check(vkQueueSubmit(vulkan.queue, submit, fences[slot]), "vkQueueSubmit");
        submitted[slot] = true;
    }

    @Override
    public void close() {
        vkQueueWaitIdle(vulkan.queue);
        for (long fence : fences) {
            vkDestroyFence(vulkan.device, fence, null);
        }
        vkDestroyQueryPool(vulkan.device, queryPool, null);
        vkDestroyCommandPool(vulkan.device, commandPool, null);
        vkDestroySampler(vulkan.device, linear, null);
        vkDestroySampler(vulkan.device, nearest, null);
        vkDestroyDescriptorPool(vulkan.device, descriptorPool, null);
        vkDestroyPipeline(vulkan.device, pipeline, null);
        vkDestroyPipelineLayout(vulkan.device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(vulkan.device, setLayout, null);
        vkDestroyShaderModule(vulkan.device, shader, null);
        submit.free();
        timelineValues.free();
        memFree(waitSemaphore);
        memFree(signalSemaphore);
        memFree(waitValue);
        memFree(signalValue);
        memFree(waitStage);
        memFree(batch);
        memFree(results);
    }
}
