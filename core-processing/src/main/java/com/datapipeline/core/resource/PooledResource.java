package com.datapipeline.core.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PooledResource {

    public enum State {
        CREATED,
        ACQUIRED,
        RELEASED,
        INVALID
    }

    private String id;
    @Builder.Default
    private State state = State.CREATED;
    @Builder.Default
    private Instant createdAt = Instant.now();
    private Instant acquiredAt;
    private Instant releasedAt;
    @Builder.Default
    private AtomicLong useCount = new AtomicLong(0);
    @Builder.Default
    private boolean valid = true;

    public void markAcquired() {
        this.state = State.ACQUIRED;
        this.acquiredAt = Instant.now();
        this.useCount.incrementAndGet();
    }

    public void markReleased() {
        this.state = State.RELEASED;
        this.releasedAt = Instant.now();
    }

    public void invalidate() {
        this.state = State.INVALID;
        this.valid = false;
    }

    public boolean isAcquired() {
        return state == State.ACQUIRED;
    }

}
