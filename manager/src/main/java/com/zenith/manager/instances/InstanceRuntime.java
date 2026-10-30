package com.zenith.manager.instances;

import com.google.gson.JsonObject;

/**
 * Live, non-persisted state of one instance's process.
 */
public final class InstanceRuntime {
    public enum Phase { STOPPED, STARTING, RUNNING, STOPPING, BACKOFF }

    public final String instanceId;
    public final LogBuffer logs = new LogBuffer(2000);

    volatile Phase phase = Phase.STOPPED;
    volatile Process process;
    /** Completes once the manager has finished handling the current process's exit. */
    volatile java.util.concurrent.CompletableFuture<Void> exitHandled = java.util.concurrent.CompletableFuture.completedFuture(null);
    volatile String token;
    volatile JsonObject status;
    volatile String lastError;
    volatile long startedAt;
    volatile long restartAt;
    volatile boolean eventsConnected;
    /** Set when the manager initiated the stop, so the exit isn't treated as a crash. */
    volatile boolean stopRequested;
    int crashCount;
    /** Status polls overlap when the instance is slow; only the newest answer counts. */
    final java.util.concurrent.atomic.AtomicLong pollsSent = new java.util.concurrent.atomic.AtomicLong();
    long pollApplied;
    int pollFailures;

    InstanceRuntime(String instanceId) {
        this.instanceId = instanceId;
    }

    public Phase phase() {
        return phase;
    }

    public JsonObject status() {
        return status;
    }

    public JsonObject toJson() {
        var json = new JsonObject();
        json.addProperty("phase", phase.name());
        json.addProperty("pid", process != null && process.isAlive() ? process.pid() : null);
        json.addProperty("startedAt", startedAt > 0 ? startedAt : null);
        json.addProperty("restartAt", phase == Phase.BACKOFF ? restartAt : null);
        json.addProperty("lastError", lastError);
        json.add("status", status);
        return json;
    }
}
