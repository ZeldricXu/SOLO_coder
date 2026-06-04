package com.battle.platform.replay;

import com.battle.platform.battlefield.PlayerPosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Component
public class ReplayRecorder {

    @Value("${battle.replay.storage-path:./replays}")
    private String storagePath;

    @Value("${battle.replay.event-buffer-size:65536}")
    private int eventBufferSize;

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<ReplayEvent>> battleEventBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Long>> battleTimeIndex = new ConcurrentHashMap<>();

    public void recordMoveEvent(String battleId, Long playerId, PlayerPosition pos) {
        byte[] payload = buildMovePayload(pos);
        recordEvent(battleId, ReplayEvent.builder()
                .timestamp(System.currentTimeMillis())
                .eventType(ReplayEvent.TYPE_MOVE)
                .playerId(playerId)
                .payload(payload)
                .build());
    }

    public void recordSkillEvent(String battleId, Long playerId, int skillId, double targetX, double targetZ) {
        byte[] payload = ByteBuffer.allocate(20)
                .putInt(skillId)
                .putDouble(targetX)
                .putDouble(targetZ)
                .array();
        recordEvent(battleId, ReplayEvent.builder()
                .timestamp(System.currentTimeMillis())
                .eventType(ReplayEvent.TYPE_SKILL)
                .playerId(playerId)
                .payload(payload)
                .build());
    }

    public void recordDeathEvent(String battleId, Long killerId, Long victimId, int skillId) {
        byte[] payload = ByteBuffer.allocate(20)
                .putLong(killerId)
                .putLong(victimId)
                .putInt(skillId)
                .array();
        recordEvent(battleId, ReplayEvent.builder()
                .timestamp(System.currentTimeMillis())
                .eventType(ReplayEvent.TYPE_DEATH)
                .playerId(victimId)
                .payload(payload)
                .build());
    }

    public void recordRespawnEvent(String battleId, Long playerId) {
        recordEvent(battleId, ReplayEvent.builder()
                .timestamp(System.currentTimeMillis())
                .eventType(ReplayEvent.TYPE_RESPAWN)
                .playerId(playerId)
                .payload(new byte[0])
                .build());
    }

    public void recordCaptureEvent(String battleId, Long playerId, int pointId) {
        byte[] payload = ByteBuffer.allocate(4).putInt(pointId).array();
        recordEvent(battleId, ReplayEvent.builder()
                .timestamp(System.currentTimeMillis())
                .eventType(ReplayEvent.TYPE_CAPTURE)
                .playerId(playerId)
                .payload(payload)
                .build());
    }

    public void flushBattleReplay(String battleId) {
        ConcurrentLinkedQueue<ReplayEvent> events = battleEventBuffers.remove(battleId);
        battleTimeIndex.remove(battleId);

        if (events == null || events.isEmpty()) {
            log.warn("No events to flush for battle {}", battleId);
            return;
        }

        try {
            Path dir = Paths.get(storagePath);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            String filename = battleId + ".replay";
            Path filePath = dir.resolve(filename);

            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(filePath)))) {

                dos.writeBytes("RPLY");
                dos.writeInt(1);
                dos.writeLong(System.currentTimeMillis());
                dos.writeInt(events.size());

                for (ReplayEvent event : events) {
                    writeEvent(dos, event);
                }
            }

            log.info("Flushed replay for battle {}: {} events, file={}", battleId, events.size(), filePath);

        } catch (IOException e) {
            log.error("Failed to flush replay for battle {}", battleId, e);
        }
    }

    public ReplayPlayback loadPlayback(String battleId) {
        Path filePath = Paths.get(storagePath, battleId + ".replay");
        if (!Files.exists(filePath)) {
            return null;
        }

        List<ReplayEvent> events = new ArrayList<>();
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(filePath)))) {

            byte[] magic = new byte[4];
            dis.readFully(magic);
            int version = dis.readInt();
            long baseTimestamp = dis.readLong();
            int eventCount = dis.readInt();

            for (int i = 0; i < eventCount; i++) {
                events.add(readEvent(dis));
            }

        } catch (IOException e) {
            log.error("Failed to load replay for battle {}", battleId, e);
            return null;
        }

        return new ReplayPlayback(events);
    }

    private void recordEvent(String battleId, ReplayEvent event) {
        ConcurrentLinkedQueue<ReplayEvent> buffer =
                battleEventBuffers.computeIfAbsent(battleId, k -> new ConcurrentLinkedQueue<>());

        buffer.add(event);

        battleTimeIndex.computeIfAbsent(battleId, k -> new ArrayList<>())
                .add(event.getTimestamp());
    }

    private void writeEvent(DataOutputStream dos, ReplayEvent event) throws IOException {
        dos.writeLong(event.getTimestamp());
        dos.writeInt(event.getEventType());
        dos.writeLong(event.getPlayerId());
        if (event.getPayload() != null && event.getPayload().length > 0) {
            dos.writeInt(event.getPayload().length);
            dos.write(event.getPayload());
        } else {
            dos.writeInt(0);
        }
    }

    private ReplayEvent readEvent(DataInputStream dis) throws IOException {
        long timestamp = dis.readLong();
        int eventType = dis.readInt();
        long playerId = dis.readLong();
        int payloadLen = dis.readInt();
        byte[] payload = payloadLen > 0 ? new byte[payloadLen] : null;
        if (payloadLen > 0) {
            dis.readFully(payload);
        }
        return ReplayEvent.builder()
                .timestamp(timestamp)
                .eventType(eventType)
                .playerId(playerId)
                .payload(payload)
                .build();
    }

    private byte[] buildMovePayload(PlayerPosition pos) {
        return ByteBuffer.allocate(28)
                .putDouble(pos.getX())
                .putDouble(pos.getY())
                .putDouble(pos.getZ())
                .putFloat(pos.getRotation())
                .array();
    }
}
