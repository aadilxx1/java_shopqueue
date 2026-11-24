package com.aadil.shopqueue.worker;

import com.aadil.shopqueue.client.JobQueueClient;
import com.aadil.shopqueue.client.LockLostException;
import com.aadil.shopqueue.client.QueuedJob;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Polls the queue for job types it has handlers for and runs them on a bounded
 * thread pool, heartbeating while each one runs.
 *
 * <p>Runs as a {@link SmartLifecycle} in the last phase, so it starts after the web
 * server (it calls the queue API over HTTP) and stops before it.
 */
public class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobQueueClient client;
    private final Map<String, JobHandler> handlers;
    private final WorkerProperties settings;
    private final String workerId;

    /** One permit per free execution slot; the poller never claims more jobs than it can start. */
    private final Semaphore freeSlots;

    private volatile boolean running;
    private CountDownLatch stopSignal;
    private Thread poller;
    private ExecutorService executor;
    private ScheduledExecutorService heartbeats;

    public JobWorker(JobQueueClient client, List<JobHandler> handlers, WorkerProperties settings, String workerId) {
        this.client = client;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("Two handlers for job type " + a.type() + ": "
                            + a.getClass().getName() + " and " + b.getClass().getName());
                }));
        this.settings = settings;
        this.workerId = workerId;
        this.freeSlots = new Semaphore(settings.concurrency());
    }

    public String workerId() {
        return workerId;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (handlers.isEmpty()) {
            log.warn("Worker {} has no JobHandlers registered; not polling", workerId);
            return;
        }
        running = true;
        stopSignal = new CountDownLatch(1);
        executor = Executors.newFixedThreadPool(settings.concurrency(), namedThreads(workerId + "-job-"));
        heartbeats = Executors.newScheduledThreadPool(2, namedThreads(workerId + "-heartbeat-"));
        poller = new Thread(this::pollLoop, workerId + "-poller");
        poller.start();
        log.info("Worker {} started: types={}, concurrency={}", workerId, handlers.keySet(), settings.concurrency());
    }

    /** Stops claiming new jobs and waits up to the shutdown timeout for running ones to finish. */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        stopSignal.countDown();
        try {
            poller.join();
            executor.shutdown();
            if (!executor.awaitTermination(settings.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Worker {}: jobs still running after {}, interrupting them",
                        workerId, settings.shutdownTimeout());
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            heartbeats.shutdownNow();
        }
        log.info("Worker {} stopped", workerId);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        while (running) {
            try {
                if (!freeSlots.tryAcquire(settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS)) {
                    continue; // all slots busy; re-check running and wait again
                }
                int slots = 1 + freeSlots.drainPermits();
                List<QueuedJob> jobs = claim(slots);
                freeSlots.release(slots - jobs.size());
                for (QueuedJob job : jobs) {
                    executor.execute(() -> run(job));
                }
                if (jobs.isEmpty()) {
                    idle();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private List<QueuedJob> claim(int limit) throws InterruptedException {
        try {
            return client.claim(workerId, handlers.keySet(), limit);
        } catch (RuntimeException e) {
            log.warn("Worker {}: claim failed, retrying in {}: {}", workerId, settings.pollInterval(), e.toString());
            return List.of();
        }
    }

    /** Sleeps for the poll interval, waking early on stop. */
    private void idle() throws InterruptedException {
        stopSignal.await(settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void run(QueuedJob job) {
        JobRun jobRun = new JobRun(job, Thread.currentThread());
        long interval = settings.heartbeatInterval().toMillis();
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
                jobRun::heartbeat, interval, interval, TimeUnit.MILLISECONDS);
        try {
            Exception failure = null;
            try {
                handlers.get(job.type()).handle(job);
            } catch (Exception e) {
                failure = e;
            }
            heartbeat.cancel(false);
            if (!jobRun.finish()) {
                log.warn("Worker {}: lost lock on job {} ({}) while running; dropping its result",
                        workerId, job.id(), job.type());
                return;
            }
            report(job, failure);
        } finally {
            heartbeat.cancel(false);
            freeSlots.release();
        }
    }

    private void report(QueuedJob job, Exception failure) {
        try {
            if (failure == null) {
                client.complete(job.id(), workerId);
            } else {
                log.info("Worker {}: job {} ({}) failed on attempt {}/{}: {}",
                        workerId, job.id(), job.type(), job.attempts(), job.maxAttempts(), failure.toString());
                client.fail(job.id(), workerId, stackTrace(failure));
            }
        } catch (LockLostException e) {
            log.warn("Worker {}: job {} was taken over before its result was saved", workerId, job.id());
        } catch (RuntimeException e) {
            // Job stays RUNNING; once its heartbeat goes stale the reaper hands it to another worker.
            log.error("Worker {}: could not report result of job {}", workerId, job.id(), e);
        }
    }

    /**
     * Coordinates the handler thread and its heartbeat. A 409 on heartbeat interrupts
     * the handler, but never after the handler has finished — otherwise the interrupt
     * could land on the complete/fail call or on the next job using this pool thread.
     */
    private final class JobRun {
        private final QueuedJob job;
        private final Thread handlerThread;
        private boolean finished;
        private boolean lockLost;

        JobRun(QueuedJob job, Thread handlerThread) {
            this.job = job;
            this.handlerThread = handlerThread;
        }

        void heartbeat() {
            try {
                client.heartbeat(job.id(), workerId);
            } catch (LockLostException e) {
                synchronized (this) {
                    if (!finished && !lockLost) {
                        lockLost = true;
                        handlerThread.interrupt();
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Worker {}: heartbeat for job {} failed, will retry: {}", workerId, job.id(), e.toString());
            }
        }

        /** Marks the handler done and clears any stray interrupt. Returns false if the lock was lost. */
        synchronized boolean finish() {
            finished = true;
            Thread.interrupted();
            return !lockLost;
        }
    }

    private static String stackTrace(Exception e) {
        StringWriter out = new StringWriter();
        e.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static java.util.concurrent.ThreadFactory namedThreads(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
