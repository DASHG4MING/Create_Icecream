package com.createicecream.render.fast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.createicecream.config.OptConfig;
import com.createicecream.mixinplugin.IceCreamMixinPlugin;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;

/**
 * Builds Create's model vertices on worker threads.
 * <p>
 * Minecraft builds every entity / block entity vertex on the render thread. Here, a Create model draw is
 * turned into an immutable snapshot ({@link DrawParams}) on the render thread, which then moves on to the
 * next draw right away, while worker threads run the vertex loops ({@link MeshKernel}) into their own
 * memory. The finished vertices are appended to the target {@link BufferBuilder} right before that buffer
 * is built for drawing (hook in {@code BufferBuilder.build()}), so nothing is ever drawn incomplete. Quad
 * order inside a buffer doesn't matter (translucent buffers are sorted when uploaded anyway).
 * <p>
 * Only draws that don't read the world (level light) go to workers. Everything else, and everything when
 * the build hook isn't present, stays on the render thread exactly as before.
 */
public final class ParallelMeshes {
    private static final Logger LOGGER = LoggerFactory.getLogger("CreateIceCream/Parallel");
    private static final int BATCH = 16;
    private static final int MAX_PENDING = 8192;
    /** Tiny models aren't worth the hand-off. */
    private static final int MIN_VERTICES = 24;
    private static final int PUSH_CHUNK_BYTES = 256 * 1024;

    /** Runtime.availableProcessors() is a slow native call on Windows: read it once. */
    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static final ThreadLocal<MeshKernel> KERNELS = ThreadLocal.withInitial(MeshKernel::new);
    private static final MemoryStack STACK = MemoryStack.create(64 * 1024);

    private static final Map<BufferBuilder, ArrayList<Job>> PENDING = new IdentityHashMap<>();
    private static final ArrayDeque<Job> JOB_POOL = new ArrayDeque<>();
    private static final ArrayDeque<Batch> BATCH_POOL = new ArrayDeque<>();
    private static final ArrayDeque<ArrayList<Job>> LIST_POOL = new ArrayDeque<>();
    private static Batch open;
    private static int pendingJobs;

    private static ThreadPoolExecutor executor;
    private static int executorThreads;
    private static boolean broken;
    private static int droppingFrames;

    // statistics (render thread), per second snapshots for F3
    public static long jobs, inline;
    public static volatile long jobsPerSec, inlinePerSec, dropped;
    public static volatile int threads;

    private ParallelMeshes() {
    }

    private static final class Job {
        final DrawParams params = new DrawParams();
        BufferBuilder target;
        Batch batch;
        long out;
        long capacity;
        int count;

        void ensure(long bytes) {
            if (bytes > capacity || capacity > (1 << 20) && bytes < capacity / 8) {
                // grow, or give back a buffer that once held a huge model
                long cap = bytes > capacity ? Math.max(bytes, Math.max(4096, capacity * 2)) : Math.max(4096, bytes * 2);
                long addr = MemoryUtil.nmemRealloc(out, cap);
                if (addr == 0L) {
                    throw new OutOfMemoryError("ParallelMeshes");
                }
                out = addr;
                capacity = cap;
            }
        }
    }

    private static final class Batch implements Runnable {
        final Job[] jobs = new Job[BATCH];
        int n;
        int remaining;
        boolean submitted;
        volatile boolean done;
        volatile Throwable error;

        @Override
        public void run() {
            MeshKernel kernel = KERNELS.get();
            try {
                for (int i = 0; i < n; i++) {
                    Job job = jobs[i];
                    job.ensure(MeshKernel.maxBytes(job.params));
                    job.count = kernel.write(job.params, job.out);
                }
            } catch (Throwable t) {
                error = t;
            } finally {
                synchronized (this) {
                    done = true;
                    notifyAll();
                }
            }
        }

        void await() {
            if (done) {
                return;
            }
            boolean interrupted = false;
            synchronized (this) {
                while (!done) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Worker count from the config: -1 = auto (about half the cores, max 4), 0 = off. */
    private static int wantedThreads() {
        int cfg = OptConfig.workerThreads;
        if (cfg >= 0) {
            return Math.min(cfg, 8);
        }
        return Math.max(0, Math.min(4, CORES / 2 - 1));
    }

    private static int activeFrame = -1;
    private static boolean activeCached;

    private static boolean active() {
        int frame = com.createicecream.render.cache.FrameClock.frame;
        if (frame != activeFrame) {
            activeFrame = frame;
            activeCached = computeActive();
        }
        return activeCached;
    }

    private static boolean computeActive() {
        if (broken || !OptConfig.enabled || !IceCreamMixinPlugin.APPLIED.contains("BufferBuilderBuildMixin")) {
            return false;
        }
        int want = wantedThreads();
        if (want <= 0) {
            if (executor != null && pendingJobs == 0) {
                executor.shutdown();
                executor = null;
                executorThreads = 0;
            }
            threads = 0;
            return false;
        }
        if (executor == null || executorThreads != want) {
            if (executor != null) {
                executor.shutdown();
            }
            AtomicInteger id = new AtomicInteger();
            executor = new ThreadPoolExecutor(want, want, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "CreateIceCream-Mesh-" + id.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
            executorThreads = want;
            threads = want;
        }
        return true;
    }

    /**
     * Render thread. Queues a copy of {@code params} for {@code target}.
     *
     * @return false if the draw must be built right now instead
     */
    static boolean trySubmit(DrawParams params, BufferBuilder target) {
        if (params.useLevelLight || params.template.vertexCount() < MIN_VERTICES || pendingJobs >= MAX_PENDING
                || !RenderSystem.isOnRenderThread() || !active()) {
            return false;
        }
        Job job = JOB_POOL.pollFirst();
        if (job == null) {
            job = new Job();
        }
        job.params.copyFrom(params);
        job.target = target;
        Batch batch = open;
        if (batch == null) {
            batch = BATCH_POOL.pollFirst();
            if (batch == null) {
                batch = new Batch();
            }
            open = batch;
        }
        job.batch = batch;
        batch.jobs[batch.n++] = job;
        batch.remaining++;
        ArrayList<Job> list = PENDING.get(target);
        if (list == null) {
            list = LIST_POOL.pollFirst();
            if (list == null) {
                list = new ArrayList<>();
            }
            PENDING.put(target, list);
        }
        list.add(job);
        pendingJobs++;
        jobs++;
        if (batch.n == BATCH) {
            dispatch(batch);
            open = null;
        }
        return true;
    }

    private static void dispatch(Batch batch) {
        batch.submitted = true;
        try {
            executor.execute(batch);
        } catch (RejectedExecutionException e) {
            batch.run();
        }
    }

    /** Called from {@code BufferBuilder.build()} (HEAD): appends every finished job for this buffer. */
    public static void flush(BufferBuilder builder) {
        if (pendingJobs == 0 || !RenderSystem.isOnRenderThread()) {
            return;
        }
        ArrayList<Job> list = PENDING.remove(builder);
        if (list == null) {
            return;
        }
        VertexBufferWriter writer = VertexBufferWriter.tryOf(builder);
        for (int i = 0, size = list.size(); i < size; i++) {
            Job job = list.get(i);
            Batch batch = job.batch;
            if (!batch.submitted) {
                // still collecting: build it here instead of waiting for a worker
                batch.submitted = true;
                if (open == batch) {
                    open = null;
                }
                batch.run();
                inline++;
            }
            batch.await();
            if (batch.error != null) {
                fail(batch.error);
            } else if (writer != null && job.count > 0) {
                push(writer, job.out, job.count, job.params.format);
            }
            finish(job);
        }
        list.clear();
        LIST_POOL.addLast(list);
    }

    private static void finish(Job job) {
        pendingJobs--;
        Batch batch = job.batch;
        if (--batch.remaining == 0) {
            for (int i = 0; i < batch.n; i++) {
                Job j = batch.jobs[i];
                j.params.clearRefs();
                j.target = null;
                j.batch = null;
                JOB_POOL.addLast(j);
                batch.jobs[i] = null;
            }
            batch.n = 0;
            batch.submitted = false;
            batch.done = false;
            batch.error = null;
            BATCH_POOL.addLast(batch);
        }
    }

    private static void fail(Throwable t) {
        if (!broken) {
            broken = true;
            LOGGER.error("Worker-thread model building failed; falling back to the render thread for this session", t);
        }
        activeCached = false;
    }

    /**
     * End of the frame: anything still pending belongs to a buffer that was never built (normally none).
     * Those vertices are dropped; if that keeps happening the feature turns itself off.
     */
    public static void endFrame() {
        if (pendingJobs == 0) {
            droppingFrames = 0;
            return;
        }
        if (open != null) {
            open.submitted = true;
            open.run();
            open = null;
        }
        int lost = 0;
        for (ArrayList<Job> list : PENDING.values()) {
            for (Job job : list) {
                job.batch.await();
                finish(job);
                lost++;
            }
            list.clear();
            LIST_POOL.addLast(list);
        }
        PENDING.clear();
        dropped += lost;
        if (++droppingFrames >= 3 && !broken) {
            broken = true;
            LOGGER.warn("Worker-thread model building disabled: {} draws were never flushed (a mod reads buffers without building them)", lost);
        }
        if (broken) {
            activeCached = false;
        }
    }

    public static void onSecond() {
        jobsPerSec = jobs;
        inlinePerSec = inline;
        jobs = inline = 0;
    }

    public static boolean isBroken() {
        return broken;
    }

    /** Appends finished vertices to a buffer in chunks the vertex writer handles comfortably. */
    static void push(VertexBufferWriter writer, long ptr, int vertices, VertexFormat format) {
        if (vertices <= 0) {
            return;
        }
        int stride = format.getVertexSize();
        int perChunk = Math.max(4, (PUSH_CHUNK_BYTES / stride) & ~3);
        for (int done = 0; done < vertices; ) {
            int n = Math.min(perChunk, vertices - done);
            STACK.push();
            try {
                writer.push(STACK, ptr + (long) done * stride, n, format);
            } finally {
                STACK.pop();
            }
            done += n;
        }
    }
}
