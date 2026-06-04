package com.battle.platform.replay;

import lombok.Data;

import java.util.Comparator;
import java.util.PriorityQueue;

public class ReplayPlayback {

    private final PriorityQueue<ReplayEvent> eventQueue;
    private long currentTimestamp;
    private boolean isPlaying;
    private float speed;
    private long playbackStartTime;
    private ReplayData replayData;

    public ReplayPlayback(ReplayData replayData) {
        this.replayData = replayData;
        this.eventQueue = new PriorityQueue<>(Comparator.comparingLong(ReplayEvent::getTimestamp));
        this.speed = 1.0f;
        reset();
    }

    public void reset() {
        this.currentTimestamp = 0;
        this.isPlaying = false;
        this.playbackStartTime = 0;
        this.eventQueue.clear();

        if (replayData != null) {
            eventQueue.addAll(replayData.getMoveEvents());
            eventQueue.addAll(replayData.getCombatEvents());
            eventQueue.addAll(replayData.getSkillEvents());
        }
    }

    public void play() {
        if (isPlaying) return;
        isPlaying = true;
        playbackStartTime = System.currentTimeMillis() - (long) (currentTimestamp / speed);
    }

    public void pause() {
        isPlaying = false;
    }

    public void setSpeed(float speed) {
        if (speed <= 0) throw new IllegalArgumentException("Speed must be positive");
        long actualPlayedTime = isPlaying ? System.currentTimeMillis() - playbackStartTime : 0;
        this.currentTimestamp = (long) (actualPlayedTime * speed);
        this.speed = speed;
        if (isPlaying) {
            this.playbackStartTime = System.currentTimeMillis() - (long) (currentTimestamp / speed);
        }
    }

    public float getSpeed() {
        return speed;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public long getCurrentTimestamp() {
        if (isPlaying) {
            long elapsed = System.currentTimeMillis() - playbackStartTime;
            return (long) (elapsed * speed);
        }
        return currentTimestamp;
    }

    public ReplayEvent pollNextEvent() {
        if (!isPlaying) {
            return null;
        }

        long now = getCurrentTimestamp();

        ReplayEvent nextEvent = eventQueue.peek();
        if (nextEvent != null && nextEvent.getTimestamp() <= now) {
            this.currentTimestamp = nextEvent.getTimestamp();
            return eventQueue.poll();
        }

        return null;
    }

    public boolean hasMoreEvents() {
        return !eventQueue.isEmpty();
    }

    public ReplayEvent peekNextEvent() {
        return eventQueue.peek();
    }

    public long getDuration() {
        if (replayData == null) return 0;
        return replayData.getDuration();
    }

    public void seekTo(long timestamp) {
        reset();
        this.currentTimestamp = timestamp;
        if (isPlaying) {
            this.playbackStartTime = System.currentTimeMillis() - (long) (timestamp / speed);
        }

        while (!eventQueue.isEmpty() && eventQueue.peek().getTimestamp() < timestamp) {
            eventQueue.poll();
        }
    }

    public int getRemainingEventCount() {
        return eventQueue.size();
    }

    @Data
    public static class ReplayData {
        private final long duration;
        private final java.util.List<MoveEvent> moveEvents;
        private final java.util.List<CombatEvent> combatEvents;
        private final java.util.List<SkillEvent> skillEvents;

        public java.util.List<? extends ReplayEvent> getAllEvents() {
            java.util.List<ReplayEvent> all = new java.util.ArrayList<>();
            all.addAll(moveEvents);
            all.addAll(combatEvents);
            all.addAll(skillEvents);
            return all;
        }
    }

    public interface ReplayEvent {
        long getTimestamp();
        EventType getType();
    }

    public enum EventType {
        MOVE, COMBAT, SKILL
    }

    @Data
    public static class MoveEvent implements ReplayEvent {
        private final long timestamp;
        private final long playerId;
        private final double x;
        private final double y;
        private final double z;

        @Override
        public EventType getType() {
            return EventType.MOVE;
        }
    }

    @Data
    public static class CombatEvent implements ReplayEvent {
        private final long timestamp;
        private final long attackerId;
        private final long targetId;
        private final int damage;
        private final String skillId;

        @Override
        public EventType getType() {
            return EventType.COMBAT;
        }
    }

    @Data
    public static class SkillEvent implements ReplayEvent {
        private final long timestamp;
        private final long casterId;
        private final String skillId;
        private final double x;
        private final double y;
        private final double z;

        @Override
        public EventType getType() {
            return EventType.SKILL;
        }
    }
}
