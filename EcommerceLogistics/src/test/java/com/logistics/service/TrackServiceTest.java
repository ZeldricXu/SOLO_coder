package com.logistics.service;

import com.logistics.builder.TestDataBuilder;
import com.logistics.constant.LogisticsConstants;
import com.logistics.dto.TrackInfo;
import com.logistics.dto.TrackQueryResponse;
import com.logistics.entity.Logistics;
import com.logistics.entity.LogisticsHistory;
import com.logistics.entity.Track;
import com.logistics.repository.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("轨迹服务完整测试")
class TrackServiceTest {

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private LogisticsService logisticsService;

    @Mock
    private AsyncNotificationService statusService;

    @Mock
    private HistoryService historyService;

    @Mock
    private TrackBatchService trackBatchService;

    @InjectMocks
    private TrackService trackService;

    private Logistics testLogistics;
    private Track testTrack1;
    private Track testTrack2;

    @BeforeEach
    void setUp() {
        testLogistics = TestDataBuilder.buildTestLogistics(
                TestDataBuilder.TEST_ORDER_ID, TestDataBuilder.TEST_STATION_ID);
        testTrack1 = TestDataBuilder.buildTestTrack(testLogistics.getLogisticsId(), 1);
        testTrack2 = TestDataBuilder.buildTestTrack(testLogistics.getLogisticsId(), 2);
    }

    @Test
    @DisplayName("测试轨迹更新实时触发记录")
    void testTrackUpdateTriggersRecord() {
        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(trackBatchService.recordTrack(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(testTrack1);
        when(historyService.recordHistory(any(LogisticsHistory.class))).thenReturn(null);

        Track result = trackService.recordTrack(
                testLogistics.getLogisticsId(),
                LogisticsConstants.STATUS_DELIVERING,
                "测试位置",
                "测试详情");

        assertNotNull(result);
        assertEquals(testTrack1, result);

        verify(trackBatchService, times(1)).recordTrack(
                eq(testLogistics.getLogisticsId()),
                eq(LogisticsConstants.STATUS_DELIVERING),
                eq("测试位置"),
                eq("测试详情"));

        verify(statusService, times(1)).sendNotificationAsync(
                eq(testLogistics.getLogisticsId()),
                eq(LogisticsConstants.NOTIFY_TYPE_TRACK),
                eq(LogisticsConstants.STATUS_DELIVERING));

        verify(historyService, times(1)).recordHistory(any(LogisticsHistory.class));
    }

    @Test
    @DisplayName("测试按物流ID查询轨迹")
    void testGetTracksByLogisticsId() {
        List<Track> tracks = Arrays.asList(testTrack1, testTrack2);

        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(trackRepository.findByLogisticsIdOrderByTrackTimeAsc(anyString())).thenReturn(tracks);

        TrackQueryResponse response = trackService.getTracksByLogisticsId(testLogistics.getLogisticsId());

        assertNotNull(response);
        assertNotNull(response.getTracks());
        assertEquals(2, response.getTracks().size());

        TrackInfo info1 = response.getTracks().get(0);
        assertEquals(testTrack1.getTrackStatus(), info1.getStatus());
        assertEquals(testTrack1.getTrackLocation(), info1.getLocation());
        assertEquals(testTrack1.getTrackTime(), info1.getTime());
        assertEquals(testTrack1.getTrackDetail(), info1.getDetail());

        verify(trackBatchService, times(1)).flushBatch(eq(testLogistics.getLogisticsId()));
    }

    @Test
    @DisplayName("测试按物流编号查询轨迹")
    void testGetTracksByLogisticsNumber() {
        String logisticsNumber = "SF1234567890";
        List<Track> tracks = Arrays.asList(testTrack1, testTrack2);

        when(logisticsService.getLogisticsByNumber(eq(logisticsNumber))).thenReturn(testLogistics);
        when(logisticsService.getLogisticsById(eq(testLogistics.getLogisticsId()))).thenReturn(testLogistics);
        when(trackRepository.findByLogisticsIdOrderByTrackTimeAsc(anyString())).thenReturn(tracks);

        TrackQueryResponse response = trackService.getTracksByLogisticsNumber(logisticsNumber);

        assertNotNull(response);
        assertEquals(2, response.getTracks().size());

        verify(logisticsService, times(1)).getLogisticsByNumber(eq(logisticsNumber));
    }

    @Test
    @DisplayName("测试轨迹数据完整性")
    void testTrackDataCompleteness() {
        Track track = TestDataBuilder.buildTestTrack(testLogistics.getLogisticsId(), 1);

        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(trackBatchService.recordTrack(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(track);
        when(historyService.recordHistory(any(LogisticsHistory.class))).thenReturn(null);

        Track result = trackService.recordTrack(
                testLogistics.getLogisticsId(),
                LogisticsConstants.STATUS_DELIVERING,
                track.getTrackLocation(),
                track.getTrackDetail());

        assertNotNull(result.getTrackId());
        assertNotNull(result.getLogisticsId());
        assertNotNull(result.getTrackStatus());
        assertNotNull(result.getTrackLocation());
        assertNotNull(result.getTrackTime());
        assertNotNull(result.getTrackDetail());
    }

    @Test
    @DisplayName("测试高频轨迹批量处理标识")
    void testHighFrequencyTrackFlag() {
        when(trackBatchService.isHighFrequency(anyString())).thenReturn(true);
        when(trackBatchService.getPendingTrackCount(anyString())).thenReturn(5);

        assertTrue(trackService.isHighFrequency("test_logistics"));
        assertEquals(5, trackService.getPendingTrackCount("test_logistics"));

        verify(trackBatchService, times(1)).isHighFrequency(eq("test_logistics"));
        verify(trackBatchService, times(1)).getPendingTrackCount(eq("test_logistics"));
    }

    @Test
    @DisplayName("测试查询时刷新待处理轨迹")
    void testFlushPendingTracksOnQuery() {
        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(trackRepository.findByLogisticsIdOrderByTrackTimeAsc(anyString())).thenReturn(Arrays.asList(testTrack1));

        trackService.getTracksByLogisticsId(testLogistics.getLogisticsId());

        verify(trackBatchService, times(1)).flushBatch(eq(testLogistics.getLogisticsId()));
    }

    @Test
    @DisplayName("测试轨迹按时间排序")
    void testTracksOrderedByTime() {
        Track track1 = TestDataBuilder.buildTestTrack(testLogistics.getLogisticsId(), 1);
        Track track2 = TestDataBuilder.buildTestTrack(testLogistics.getLogisticsId(), 2);
        Track track3 = TestDataBuilder.buildTestTrack(testLogistics.getLogisticsId(), 3);

        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(trackRepository.findByLogisticsIdOrderByTrackTimeAsc(anyString()))
                .thenReturn(Arrays.asList(track1, track2, track3));

        TrackQueryResponse response = trackService.getTracksByLogisticsId(testLogistics.getLogisticsId());

        assertNotNull(response);
        assertEquals(3, response.getTracks().size());
        assertTrue(response.getTracks().get(0).getTime().isBefore(response.getTracks().get(1).getTime()));
        assertTrue(response.getTracks().get(1).getTime().isBefore(response.getTracks().get(2).getTime()));
    }

    @Test
    @DisplayName("测试不同状态的轨迹记录")
    void testTrackWithDifferentStatuses() {
        verifyTrackStatus(LogisticsConstants.STATUS_SHIPPING, "已发货");
        verifyTrackStatus(LogisticsConstants.STATUS_DELIVERING, "配送中");
        verifyTrackStatus(LogisticsConstants.STATUS_DELIVERED, "已送达");
    }

    private void verifyTrackStatus(String status, String expectedDetail) {
        Track track = new Track();
        track.setTrackStatus(status);

        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(trackBatchService.recordTrack(anyString(), eq(status), anyString(), anyString()))
                .thenReturn(track);
        when(historyService.recordHistory(any(LogisticsHistory.class))).thenReturn(null);

        Track result = trackService.recordTrack(
                testLogistics.getLogisticsId(), status, "测试位置", expectedDetail);

        assertEquals(status, result.getTrackStatus());
    }
}
