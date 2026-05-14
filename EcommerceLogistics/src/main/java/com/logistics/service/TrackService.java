package com.logistics.service;

import com.logistics.constant.LogisticsConstants;
import com.logistics.dto.TrackInfo;
import com.logistics.dto.TrackQueryResponse;
import com.logistics.entity.Logistics;
import com.logistics.entity.LogisticsHistory;
import com.logistics.entity.Track;
import com.logistics.exception.LogisticsException;
import com.logistics.repository.TrackRepository;
import com.logistics.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackService {

    private final TrackRepository trackRepository;
    private final LogisticsService logisticsService;
    private final AsyncNotificationService statusService;
    private final HistoryService historyService;
    private final TrackBatchService trackBatchService;

    @Transactional
    public Track recordTrack(String logisticsId, String status, String location, String detail) {
        logisticsService.getLogisticsById(logisticsId);

        Track track = trackBatchService.recordTrack(logisticsId, status, location, detail);

        statusService.sendNotificationAsync(logisticsId, LogisticsConstants.NOTIFY_TYPE_TRACK, status);

        LogisticsHistory history = new LogisticsHistory();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setLogisticsId(logisticsId);
        history.setHistoryType(LogisticsConstants.HISTORY_TYPE_TRACK);
        history.setHistoryStatus(status);
        history.setHistoryDetail("轨迹更新：" + location + " - " + (detail != null ? detail : ""));
        historyService.recordHistory(history);

        return track;
    }

    public TrackQueryResponse getTracksByLogisticsId(String logisticsId) {
        logisticsService.getLogisticsById(logisticsId);

        trackBatchService.flushBatch(logisticsId);

        List<Track> tracks = trackRepository.findByLogisticsIdOrderByTrackTimeAsc(logisticsId);

        List<TrackInfo> trackInfos = tracks.stream()
                .map(track -> TrackInfo.builder()
                        .status(track.getTrackStatus())
                        .location(track.getTrackLocation())
                        .time(track.getTrackTime())
                        .detail(track.getTrackDetail())
                        .build())
                .collect(Collectors.toList());

        return TrackQueryResponse.builder()
                .tracks(trackInfos)
                .build();
    }

    public TrackQueryResponse getTracksByLogisticsNumber(String logisticsNumber) {
        Logistics logistics = logisticsService.getLogisticsByNumber(logisticsNumber);
        return getTracksByLogisticsId(logistics.getLogisticsId());
    }

    public List<Track> getRawTracksByLogisticsId(String logisticsId) {
        trackBatchService.flushBatch(logisticsId);
        return trackRepository.findByLogisticsIdOrderByTrackTimeAsc(logisticsId);
    }

    public int getPendingTrackCount(String logisticsId) {
        return trackBatchService.getPendingTrackCount(logisticsId);
    }

    public boolean isHighFrequency(String logisticsId) {
        return trackBatchService.isHighFrequency(logisticsId);
    }
}
