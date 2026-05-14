package com.logistics.service;

import com.logistics.config.TrackConfig;
import com.logistics.entity.Track;
import com.logistics.repository.TrackRepository;
import com.logistics.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackBatchService {

    private final TrackRepository trackRepository;
    private final TrackConfig trackConfig;

    private final Map<String, List<PendingTrack>> pendingTracks = new ConcurrentHashMap<>();
    private final Map<String, TrackFrequency> trackFrequencies = new ConcurrentHashMap<>();

    public Track recordTrack(String logisticsId, String status, String location, String detail) {
        TrackFrequency frequency = updateFrequency(logisticsId);
        
        if (isHighFrequency(frequency)) {
            return batchRecordTrack(logisticsId, status, location, detail);
        } else {
            return immediateRecordTrack(logisticsId, status, location, detail);
        }
    }

    private Track immediateRecordTrack(String logisticsId, String status, String location, String detail) {
        Track track = new Track();
        track.setTrackId(IdGenerator.generateTrackId());
        track.setLogisticsId(logisticsId);
        track.setTrackStatus(status);
        track.setTrackLocation(location);
        track.setTrackTime(LocalDateTime.now());
        track.setTrackDetail(detail);
        
        Track saved = trackRepository.save(track);
        log.debug("实时轨迹记录 - logisticsId: {}, location: {}", logisticsId, location);
        return saved;
    }

    private Track batchRecordTrack(String logisticsId, String status, String location, String detail) {
        List<PendingTrack> tracks = pendingTracks.computeIfAbsent(logisticsId, k -> new ArrayList<>());
        
        PendingTrack pending = new PendingTrack();
        pending.setStatus(status);
        pending.setLocation(location);
        pending.setTime(LocalDateTime.now());
        pending.setDetail(detail);
        tracks.add(pending);
        
        log.debug("批量轨迹入队 - logisticsId: {}, 队列大小: {}", logisticsId, tracks.size());
        
        if (tracks.size() >= trackConfig.getBatchFlushThreshold()) {
            flushBatch(logisticsId);
        }
        
        Track virtualTrack = new Track();
        virtualTrack.setTrackId("pending_" + System.currentTimeMillis());
        virtualTrack.setLogisticsId(logisticsId);
        virtualTrack.setTrackStatus(status);
        virtualTrack.setTrackLocation(location);
        virtualTrack.setTrackTime(LocalDateTime.now());
        virtualTrack.setTrackDetail(detail);
        return virtualTrack;
    }

    public void flushBatch(String logisticsId) {
        List<PendingTrack> tracks = pendingTracks.remove(logisticsId);
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        
        List<Track> mergedTracks = mergePendingTracks(logisticsId, tracks);
        
        List<Track> saved = trackRepository.saveAll(mergedTracks);
        log.info("批量轨迹刷新 - logisticsId: {}, 合并后记录数: {}", logisticsId, saved.size());
    }

    private List<Track> mergePendingTracks(String logisticsId, List<PendingTrack> tracks) {
        List<Track> result = new ArrayList<>();
        
        if (tracks.isEmpty()) {
            return result;
        }
        
        PendingTrack first = tracks.get(0);
        PendingTrack last = tracks.get(tracks.size() - 1);
        
        Track startTrack = createTrack(logisticsId, first.getStatus(), first.getLocation(), 
                "起始位置 - " + (first.getDetail() != null ? first.getDetail() : ""), first.getTime());
        result.add(startTrack);
        
        if (tracks.size() > 2) {
            Track summaryTrack = createTrack(logisticsId, last.getStatus(), 
                    "途径 " + (tracks.size() - 2) + " 个位置", 
                    "高频轨迹合并记录，共 " + tracks.size() + " 条轨迹", 
                    first.getTime().plusSeconds((long) tracks.size() / 2));
            result.add(summaryTrack);
        }
        
        Track endTrack = createTrack(logisticsId, last.getStatus(), last.getLocation(), 
                "当前位置 - " + (last.getDetail() != null ? last.getDetail() : ""), last.getTime());
        result.add(endTrack);
        
        return result;
    }

    private Track createTrack(String logisticsId, String status, String location, String detail, LocalDateTime time) {
        Track track = new Track();
        track.setTrackId(IdGenerator.generateTrackId());
        track.setLogisticsId(logisticsId);
        track.setTrackStatus(status);
        track.setTrackLocation(location);
        track.setTrackTime(time);
        track.setTrackDetail(detail);
        return track;
    }

    private TrackFrequency updateFrequency(String logisticsId) {
        TrackFrequency frequency = trackFrequencies.computeIfAbsent(logisticsId, 
                k -> new TrackFrequency());
        frequency.record();
        return frequency;
    }

    private boolean isHighFrequency(TrackFrequency frequency) {
        return frequency.getCountInWindow(trackConfig.getHighFrequencyWindowSeconds()) 
                >= trackConfig.getHighFrequencyThreshold();
    }

    public int getPendingTrackCount(String logisticsId) {
        List<PendingTrack> tracks = pendingTracks.get(logisticsId);
        return tracks != null ? tracks.size() : 0;
    }

    public boolean isHighFrequency(String logisticsId) {
        TrackFrequency frequency = trackFrequencies.get(logisticsId);
        return frequency != null && isHighFrequency(frequency);
    }

    public int getHighFrequencyThreshold() {
        return trackConfig.getHighFrequencyThreshold();
    }

    public long getHighFrequencyWindowSeconds() {
        return trackConfig.getHighFrequencyWindowSeconds();
    }

    public int getBatchFlushThreshold() {
        return trackConfig.getBatchFlushThreshold();
    }

    public long getBatchFlushIntervalMs() {
        return trackConfig.getBatchFlushIntervalMs();
    }

    @Scheduled(fixedRateString = "${logistics.track.batch-flush-interval-ms:2000}")
    public void scheduledFlush() {
        List<String> logisticsIds = new ArrayList<>(pendingTracks.keySet());
        for (String logisticsId : logisticsIds) {
            flushBatch(logisticsId);
        }
    }

    public static class TrackFrequency {
        private final List<LocalDateTime> timestamps = new ArrayList<>();
        private final AtomicInteger totalCount = new AtomicInteger(0);

        public void record() {
            timestamps.add(LocalDateTime.now());
            totalCount.incrementAndGet();
            cleanupOld();
        }

        public int getCountInWindow(long seconds) {
            LocalDateTime threshold = LocalDateTime.now().minusSeconds(seconds);
            return (int) timestamps.stream()
                    .filter(ts -> ts.isAfter(threshold))
                    .count();
        }

        public int getTotalCount() {
            return totalCount.get();
        }

        private void cleanupOld() {
            LocalDateTime threshold = LocalDateTime.now().minusMinutes(1);
            timestamps.removeIf(ts -> ts.isBefore(threshold));
        }
    }

    public static class PendingTrack {
        private String status;
        private String location;
        private LocalDateTime time;
        private String detail;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public LocalDateTime getTime() {
            return time;
        }

        public void setTime(LocalDateTime time) {
            this.time = time;
        }

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }
    }
}
