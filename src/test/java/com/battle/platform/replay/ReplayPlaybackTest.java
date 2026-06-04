package com.battle.platform.replay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplayPlaybackTest {

    private ReplayPlayback.ReplayData replayData;

    @BeforeEach
    void setUp() {
        List<ReplayPlayback.MoveEvent> moveEvents = new ArrayList<>();
        moveEvents.add(new ReplayPlayback.MoveEvent(100, 1, 10.0, 0, 10.0));
        moveEvents.add(new ReplayPlayback.MoveEvent(300, 1, 20.0, 0, 20.0));
        moveEvents.add(new ReplayPlayback.MoveEvent(500, 1, 30.0, 0, 30.0));

        List<ReplayPlayback.CombatEvent> combatEvents = new ArrayList<>();
        combatEvents.add(new ReplayPlayback.CombatEvent(200, 1, 2, 100, "skill_001"));
        combatEvents.add(new ReplayPlayback.CombatEvent(400, 1, 2, 150, "skill_002"));

        List<ReplayPlayback.SkillEvent> skillEvents = new ArrayList<>();
        skillEvents.add(new ReplayPlayback.SkillEvent(150, 1, "skill_001", 15.0, 0, 15.0));

        replayData = new ReplayPlayback.ReplayData(1000, moveEvents, combatEvents, skillEvents);
    }

    @Test
    void testEventOrderingByTimestamp() {
        ReplayPlayback playback = new ReplayPlayback(replayData);

        List<Long> timestamps = new ArrayList<>();
        while (playback.hasMoreEvents()) {
            ReplayPlayback.ReplayEvent event = playback.peekNextEvent();
            timestamps.add(event.getTimestamp());
            playback.seekTo(event.getTimestamp() + 1);
        }

        for (int i = 1; i < timestamps.size(); i++) {
            assertTrue(timestamps.get(i) >= timestamps.get(i - 1),
                    "Events should be ordered by timestamp");
        }
    }

    @Test
    void testMixedEventTypesInOrder() {
        ReplayPlayback playback = new ReplayPlayback(replayData);

        List<ReplayPlayback.EventType> eventOrder = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();

        while (playback.hasMoreEvents()) {
            ReplayPlayback.ReplayEvent event = playback.peekNextEvent();
            timestamps.add(event.getTimestamp());
            eventOrder.add(event.getType());
            playback.seekTo(event.getTimestamp() + 1);
        }

        assertEquals(6, eventOrder.size());
        assertEquals(ReplayPlayback.EventType.MOVE, eventOrder.get(0));
        assertEquals(ReplayPlayback.EventType.SKILL, eventOrder.get(1));
        assertEquals(ReplayPlayback.EventType.COMBAT, eventOrder.get(2));
        assertEquals(ReplayPlayback.EventType.MOVE, eventOrder.get(3));
        assertEquals(ReplayPlayback.EventType.COMBAT, eventOrder.get(4));
        assertEquals(ReplayPlayback.EventType.MOVE, eventOrder.get(5));

        assertEquals(100, timestamps.get(0));
        assertEquals(150, timestamps.get(1));
        assertEquals(200, timestamps.get(2));
        assertEquals(300, timestamps.get(3));
        assertEquals(400, timestamps.get(4));
        assertEquals(500, timestamps.get(5));
    }

    @Test
    void testSetSpeed() {
        ReplayPlayback playback = new ReplayPlayback(replayData);
        playback.setSpeed(8.0f);

        assertEquals(8.0f, playback.getSpeed(), 0.01);
    }

    @Test
    void testSeekToTimestamp() {
        ReplayPlayback playback = new ReplayPlayback(replayData);

        playback.seekTo(250);

        ReplayPlayback.ReplayEvent next = playback.peekNextEvent();
        assertEquals(300, next.getTimestamp());
        assertEquals(ReplayPlayback.EventType.MOVE, next.getType());
    }

    @Test
    void testResetClearsState() {
        ReplayPlayback playback = new ReplayPlayback(replayData);
        playback.seekTo(500);

        assertEquals(1, playback.getRemainingEventCount());

        playback.reset();

        assertEquals(6, playback.getRemainingEventCount());
        assertEquals(0, playback.getCurrentTimestamp());
        assertFalse(playback.isPlaying());
    }

    @Test
    void testPlayPause() {
        ReplayPlayback playback = new ReplayPlayback(replayData);

        assertFalse(playback.isPlaying());

        playback.play();
        assertTrue(playback.isPlaying());

        playback.pause();
        assertFalse(playback.isPlaying());
    }

    @Test
    void testDuration() {
        ReplayPlayback playback = new ReplayPlayback(replayData);
        assertEquals(1000, playback.getDuration());
    }

    @Test
    void testPeekDoesNotRemoveEvent() {
        ReplayPlayback playback = new ReplayPlayback(replayData);

        ReplayPlayback.ReplayEvent first = playback.peekNextEvent();
        ReplayPlayback.ReplayEvent second = playback.peekNextEvent();

        assertSame(first, second);
        assertEquals(6, playback.getRemainingEventCount());
    }
}
