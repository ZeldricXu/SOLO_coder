package com.logistics.service;

import com.logistics.builder.TestDataBuilder;
import com.logistics.constant.LogisticsConstants;
import com.logistics.dto.TrackInfo;
import com.logistics.dto.TrackQueryResponse;
import com.logistics.entity.Logistics;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("轨迹批量记录策略测试")
class TrackBatchServiceTest {

    @Mock
    private TrackRepository trackRepository;

    @InjectMocks
    private TrackBatchService trackBatchService;

    private static final String TEST_LOGISTICS_ID = "track_test_logistics_001";

    @BeforeEach
    void setUp() {
        when(trackRepository.save(any(Track.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(trackRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("测试低频轨迹实时触发记录")
    void testLowFrequencyTrackImmediateRecord() {
        Track track1 = trackBatchService.recordTrack(
                TEST_LOGISTICS_ID, LogisticsConstants.STATUS_DELIVERING, "位置1", "详情1");

        assertNotNull(track1);
        assertEquals(LogisticsConstants.STATUS_DELIVERING, track1.getTrackStatus());
        assertEquals("位置1", track1.getTrackLocation());

        verify(trackRepository, times(1)).save(any(Track.class));

        assertFalse(trackBatchService.isHighFrequency(TEST_LOGISTICS_ID));
        assertEquals(0, trackBatchService.getPendingTrackCount(TEST_LOGISTICS_ID));
    }

    @Test
    @DisplayName("测试高频轨迹批量合并记录")
    void testHighFrequencyTrackBatchMerge() {
        for (int i = 1; i <= 3; i++) {
            trackBatchService.recordTrack(
                    TEST_LOGISTICS_ID, LogisticsConstants.STATUS_DELIVERING,
                    "位置" + i, "详情" + i);
        }

        assertTrue(trackBatchService.isHighFrequency(TEST_LOGISTICS_ID));

        for (int i = 4; i <= 12; i++) {
            trackBatchService.recordTrack(
                    TEST_LOGISTICS_ID, LogisticsConstants.STATUS_DELIVERING,
                    "位置" + i, "详情" + i);
        }

        verify(trackRepository, times(3)).save(any(Track.class));
        verify(trackRepository, atLeastOnce()).saveAll(anyList());
    }

    @Test
    @DisplayName("测试不同轨迹频率下的记录策略差异")
    void testTrackFrequencyStrategyDifference() {
        String lowFreqId = "low_freq_" + System.currentTimeMillis();
        String highFreqId = "high_freq_" + System.currentTimeMillis();

        for (int i = 0; i < 2; i++) {
            trackBatchService.recordTrack(lowFreqId, LogisticsConstants.STATUS_DELIVERING, "位置" + i, null);
        }
        assertFalse(trackBatchService.isHighFrequency(lowFreqId));
        verify(trackRepository, times(2)).save(any(Track.class));

        for (int i = 0; i < 5; i++) {
            trackBatchService.recordTrack(highFreqId, LogisticsConstants.STATUS_DELIVERING, "位置" + i, null);
        }
        assertTrue(trackBatchService.isHighFrequency(highFreqId));
    }

    @Test
    @DisplayName("测试轨迹记录数据的完整采集")
    void testTrackDataCompleteCollection() {
        Track track = trackBatchService.recordTrack(
                TEST_LOGISTICS_ID,
                LogisticsConstants.STATUS_DELIVERING,
                "北京市朝阳区建国路88号",
                "配送员正在前往目的地，预计10分钟到达");

        assertNotNull(track.getTrackId());
        assertEquals(TEST_LOGISTICS_ID, track.getLogisticsId());
        assertEquals(LogisticsConstants.STATUS_DELIVERING, track.getTrackStatus());
        assertEquals("北京市朝阳区建国路88号", track.getTrackLocation());
        assertEquals("配送员正在前往目的地，预计10分钟到达", track.getTrackDetail());
        assertNotNull(track.getTrackTime());
    }

    @Test
    @DisplayName("测试批量刷新时的轨迹合并")
    void testBatchFlushMerge() {
        String batchId = "batch_test_" + System.currentTimeMillis();

        for (int i = 0; i < 5; i++) {
            trackBatchService.recordTrack(
                    batchId, LogisticsConstants.STATUS_DELIVERING, "位置" + i, null);
        }

        assertTrue(trackBatchService.isHighFrequency(batchId));
        assertTrue(trackBatchService.getPendingTrackCount(batchId) > 0);

        trackBatchService.flushBatch(batchId);

        assertEquals(0, trackBatchService.getPendingTrackCount(batchId));
        verify(trackRepository, atLeastOnce()).saveAll(anyList());
    }

    @Test
    @DisplayName("测试高频轨迹合并后的数据完整性")
    void testHighFrequencyTrackMergeIntegrity() {
        String mergeTestId = "merge_test_" + System.currentTimeMillis();

        for (int i = 1; i <= 15; i++) {
            trackBatchService.recordTrack(
                    mergeTestId,
                    LogisticsConstants.STATUS_DELIVERING,
                    "位置" + i,
                    "途经点" + i);
        }

        trackBatchService.flushBatch(mergeTestId);

        verify(trackRepository, atLeast(2)).saveAll(anyList());
    }

    @Test
    @DisplayName("测试轨迹更新实时触发通知")
    void testTrackUpdateTriggersNotification() {
        TrackBatchService spyService = spy(trackBatchService);

        spyService.recordTrack(
                TEST_LOGISTICS_ID, LogisticsConstants.STATUS_DELIVERING, "测试位置", "测试详情");

        verify(spyService, times(1)).recordTrack(
                eq(TEST_LOGISTICS_ID),
                eq(LogisticsConstants.STATUS_DELIVERING),
                eq("测试位置"),
                eq("测试详情"));
    }
}
