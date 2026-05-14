package com.adplatform.service;

import com.adplatform.dto.EffectEvent;
import com.adplatform.dto.EffectQueryRequest;
import com.adplatform.dto.EffectQueryResponse;
import com.adplatform.entity.AdEffect;
import com.adplatform.exception.BusinessException;
import com.adplatform.repository.AdEffectRepository;
import com.adplatform.repository.AdInfoRepository;
import com.adplatform.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("统计模块单元测试 - 效果收集异步化")
class StatisticsServiceTest {

    @Mock
    private AdEffectRepository adEffectRepository;

    @Mock
    private AdInfoRepository adInfoRepository;

    @InjectMocks
    private StatisticsService statisticsService;

    private String testAdId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        testAdId = "ad_stat_test_" + System.currentTimeMillis();
        today = LocalDate.now();
    }

    @Test
    @DisplayName("测试记录曝光 - 成功场景")
    void testRecordExposure_Success() {
        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.findByAdIdAndStatDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(adEffectRepository.save(any(AdEffect.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdEffect result = statisticsService.recordExposure(testAdId, "home_banner");

        assertNotNull(result);
        assertEquals(1L, result.getExposureCount());
        assertEquals(0L, result.getClickCount());
        assertEquals(BigDecimal.ZERO, result.getClickRate());

        ArgumentCaptor<AdEffect> effectCaptor = ArgumentCaptor.forClass(AdEffect.class);
        verify(adEffectRepository, times(1)).save(effectCaptor.capture());
        AdEffect saved = effectCaptor.getValue();
        assertEquals(testAdId, saved.getAdId());
        assertEquals(today, saved.getStatDate());
    }

    @Test
    @DisplayName("测试记录曝光 - 更新已有记录")
    void testRecordExposure_UpdateExisting() {
        AdEffect existing = TestDataBuilder.buildAdEffect(testAdId, today, 100L, 5L, 1L);
        
        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.findByAdIdAndStatDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of(existing));
        when(adEffectRepository.save(any(AdEffect.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdEffect result = statisticsService.recordExposure(testAdId, "home_banner");

        assertNotNull(result);
        assertEquals(101L, result.getExposureCount());
        assertEquals(5L, result.getClickCount());
    }

    @Test
    @DisplayName("测试记录曝光 - 广告不存在")
    void testRecordExposure_AdNotFound() {
        when(adInfoRepository.existsById(anyString())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, 
                () -> statisticsService.recordExposure("non_existent_ad", "home_banner"));

        assertEquals(404, exception.getCode());
        assertEquals("广告不存在", exception.getMessage());
        verify(adEffectRepository, never()).save(any());
    }

    @Test
    @DisplayName("测试记录点击 - 成功场景")
    void testRecordClick_Success() {
        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.findByAdIdAndStatDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(adEffectRepository.save(any(AdEffect.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdEffect result = statisticsService.recordClick(testAdId, "user_123");

        assertNotNull(result);
        assertEquals(0L, result.getExposureCount());
        assertEquals(1L, result.getClickCount());
    }

    @Test
    @DisplayName("测试记录点击 - 点击率计算")
    void testRecordClick_ClickRateCalculation() {
        AdEffect existing = TestDataBuilder.buildAdEffect(testAdId, today, 100L, 4L, 0L);
        
        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.findByAdIdAndStatDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of(existing));
        when(adEffectRepository.save(any(AdEffect.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdEffect result = statisticsService.recordClick(testAdId, "user_123");

        assertEquals(100L, result.getExposureCount());
        assertEquals(5L, result.getClickCount());
        BigDecimal expectedRate = new BigDecimal("5").divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        assertEquals(expectedRate, result.getClickRate());
    }

    @Test
    @DisplayName("测试记录转化 - 成功场景")
    void testRecordConversion_Success() {
        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.findByAdIdAndStatDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(adEffectRepository.save(any(AdEffect.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdEffect result = statisticsService.recordConversion(testAdId);

        assertNotNull(result);
        assertEquals(1L, result.getConversionCount());
    }

    @Test
    @DisplayName("测试记录转化 - 转化率计算")
    void testRecordConversion_ConversionRateCalculation() {
        AdEffect existing = TestDataBuilder.buildAdEffect(testAdId, today, 100L, 10L, 0L);
        
        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.findByAdIdAndStatDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of(existing));
        when(adEffectRepository.save(any(AdEffect.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdEffect result = statisticsService.recordConversion(testAdId);

        assertEquals(10L, result.getClickCount());
        assertEquals(1L, result.getConversionCount());
        BigDecimal expectedRate = new BigDecimal("1").divide(new BigDecimal("10"), 4, RoundingMode.HALF_UP);
        assertEquals(expectedRate, result.getConversionRate());
    }

    @Test
    @DisplayName("测试处理效果事件 - 曝光事件")
    void testProcessEffectEvent_Exposure() {
        EffectEvent event = TestDataBuilder.buildExposureEvent(testAdId);
        
        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.findByAdIdAndStatDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(adEffectRepository.save(any(AdEffect.class))).thenAnswer(invocation -> invocation.getArgument(0));

        statisticsService.processEffectEvent(event);

        ArgumentCaptor<AdEffect> effectCaptor = ArgumentCaptor.forClass(AdEffect.class);
        verify(adEffectRepository, times(1)).save(effectCaptor.capture());
        AdEffect saved = effectCaptor.getValue();
        assertEquals(1L, saved.getExposureCount());
    }

    @Test
    @DisplayName("测试处理效果事件 - 点击事件")
    void testProcessEffectEvent_Click() {
        EffectEvent event = TestDataBuilder.buildClickEvent(testAdId);
        
        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.findByAdIdAndStatDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(adEffectRepository.save(any(AdEffect.class))).thenAnswer(invocation -> invocation.getArgument(0));

        statisticsService.processEffectEvent(event);

        ArgumentCaptor<AdEffect> effectCaptor = ArgumentCaptor.forClass(AdEffect.class);
        verify(adEffectRepository, times(1)).save(effectCaptor.capture());
        AdEffect saved = effectCaptor.getValue();
        assertEquals(1L, saved.getClickCount());
    }

    @Test
    @DisplayName("测试处理效果事件 - 转化事件")
    void testProcessEffectEvent_Conversion() {
        EffectEvent event = TestDataBuilder.buildConversionEvent(testAdId);
        
        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.findByAdIdAndStatDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(adEffectRepository.save(any(AdEffect.class))).thenAnswer(invocation -> invocation.getArgument(0));

        statisticsService.processEffectEvent(event);

        ArgumentCaptor<AdEffect> effectCaptor = ArgumentCaptor.forClass(AdEffect.class);
        verify(adEffectRepository, times(1)).save(effectCaptor.capture());
        AdEffect saved = effectCaptor.getValue();
        assertEquals(1L, saved.getConversionCount());
    }

    @Test
    @DisplayName("测试查询效果数据 - 成功场景")
    void testQueryEffects_Success() {
        LocalDate startDate = today.minusDays(7);
        LocalDate endDate = today;

        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.sumExposureCountByAdIdAndDateRange(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(10000L);
        when(adEffectRepository.sumClickCountByAdIdAndDateRange(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(500L);
        when(adEffectRepository.sumConversionCountByAdIdAndDateRange(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(50L);

        EffectQueryRequest request = EffectQueryRequest.builder()
                .adId(testAdId)
                .startDate(startDate)
                .endDate(endDate)
                .build();

        EffectQueryResponse response = statisticsService.queryEffects(request);

        assertNotNull(response);
        assertEquals(10000L, response.getExposureCount());
        assertEquals(500L, response.getClickCount());
        assertEquals(50L, response.getConversionCount());
        assertEquals(new BigDecimal("0.0500"), response.getClickRate());
        assertEquals(new BigDecimal("0.1000"), response.getConversionRate());
    }

    @Test
    @DisplayName("测试查询效果数据 - 无数据场景")
    void testQueryEffects_NoData() {
        LocalDate startDate = today.minusDays(7);
        LocalDate endDate = today;

        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.sumExposureCountByAdIdAndDateRange(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(null);
        when(adEffectRepository.sumClickCountByAdIdAndDateRange(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(null);
        when(adEffectRepository.sumConversionCountByAdIdAndDateRange(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(null);

        EffectQueryRequest request = EffectQueryRequest.builder()
                .adId(testAdId)
                .startDate(startDate)
                .endDate(endDate)
                .build();

        EffectQueryResponse response = statisticsService.queryEffects(request);

        assertNotNull(response);
        assertEquals(0L, response.getExposureCount());
        assertEquals(0L, response.getClickCount());
        assertEquals(0L, response.getConversionCount());
        assertEquals(BigDecimal.ZERO, response.getClickRate());
        assertEquals(BigDecimal.ZERO, response.getConversionRate());
    }

    @Test
    @DisplayName("测试查询效果数据 - 广告不存在")
    void testQueryEffects_AdNotFound() {
        when(adInfoRepository.existsById(anyString())).thenReturn(false);

        EffectQueryRequest request = EffectQueryRequest.builder()
                .adId("non_existent_ad")
                .startDate(today.minusDays(7))
                .endDate(today)
                .build();

        BusinessException exception = assertThrows(BusinessException.class, 
                () -> statisticsService.queryEffects(request));

        assertEquals(404, exception.getCode());
        assertEquals("广告不存在", exception.getMessage());
    }

    @Test
    @DisplayName("测试并发效果收集 - 验证线程安全")
    void testConcurrentEffectCollection_ThreadSafety() throws Exception {
        int threadCount = 50;
        int eventsPerThread = 20;
        AtomicLong totalExposures = new AtomicLong(0);
        AtomicLong totalClicks = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.findByAdIdAndStatDate(anyString(), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    long exposures = totalExposures.get();
                    long clicks = totalClicks.get();
                    if (exposures == 0 && clicks == 0) {
                        return Optional.empty();
                    }
                    return Optional.of(TestDataBuilder.buildAdEffect(testAdId, today, exposures, clicks, 0L));
                });
        when(adEffectRepository.save(any(AdEffect.class))).thenAnswer(invocation -> {
            AdEffect effect = invocation.getArgument(0);
            totalExposures.set(effect.getExposureCount());
            totalClicks.set(effect.getClickCount());
            return effect;
        });

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < eventsPerThread; j++) {
                        if (j % 2 == 0) {
                            statisticsService.recordExposure(testAdId, "home_banner");
                        } else {
                            statisticsService.recordClick(testAdId, "user_" + threadNum + "_" + j);
                        }
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threadCount, successCount.get(), "所有线程应该成功执行");
        assertEquals(0, failCount.get(), "不应该有失败的线程");
    }

    @Test
    @DisplayName("测试高并发效果事件 - 队列处理能力")
    void testHighConcurrencyEffectEvents_QueueCapacity() throws Exception {
        int eventCount = 1000;
        AtomicInteger processedCount = new AtomicInteger(0);
        BlockingQueue<EffectEvent> testQueue = new LinkedBlockingQueue<>(2000);

        for (int i = 0; i < eventCount; i++) {
            EffectEvent event;
            if (i % 3 == 0) {
                event = TestDataBuilder.buildExposureEvent(testAdId);
            } else if (i % 3 == 1) {
                event = TestDataBuilder.buildClickEvent(testAdId);
            } else {
                event = TestDataBuilder.buildConversionEvent(testAdId);
            }
            testQueue.offer(event);
        }

        assertEquals(eventCount, testQueue.size(), "队列应该能够容纳所有事件");

        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.findByAdIdAndStatDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(adEffectRepository.save(any(AdEffect.class))).thenAnswer(invocation -> {
            processedCount.incrementAndGet();
            return invocation.getArgument(0);
        });

        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(eventCount);

        for (int i = 0; i < eventCount; i++) {
            final EffectEvent event = testQueue.poll();
            if (event != null) {
                executor.submit(() -> {
                    try {
                        statisticsService.processEffectEvent(event);
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(eventCount, processedCount.get(), "所有事件应该被处理");
    }

    @Test
    @DisplayName("测试获取效果详情 - 按时间范围查询")
    void testGetEffectDetails_DateRange() {
        LocalDate startDate = today.minusDays(7);
        LocalDate endDate = today;
        AdEffect effect1 = TestDataBuilder.buildAdEffect(testAdId, startDate, 1000L, 50L, 5L);
        AdEffect effect2 = TestDataBuilder.buildAdEffect(testAdId, endDate, 2000L, 100L, 10L);

        when(adEffectRepository.findByAdIdAndStatDateBetween(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(java.util.Arrays.asList(effect1, effect2));

        java.util.List<AdEffect> effects = statisticsService.getEffectDetails(testAdId, startDate, endDate);

        assertNotNull(effects);
        assertEquals(2, effects.size());
        verify(adEffectRepository, times(1))
                .findByAdIdAndStatDateBetween(eq(testAdId), eq(startDate), eq(endDate));
    }

    @Test
    @DisplayName("测试点击率计算 - 零曝光时处理")
    void testClickRateCalculation_ZeroExposure() {
        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.findByAdIdAndStatDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(adEffectRepository.save(any(AdEffect.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdEffect result = statisticsService.recordClick(testAdId, "user_123");

        assertEquals(BigDecimal.ZERO, result.getClickRate(), "零曝光时点击率应该为0");
    }

    @Test
    @DisplayName("测试转化率计算 - 零点击时处理")
    void testConversionRateCalculation_ZeroClick() {
        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adEffectRepository.findByAdIdAndStatDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(adEffectRepository.save(any(AdEffect.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdEffect result = statisticsService.recordConversion(testAdId);

        assertEquals(BigDecimal.ZERO, result.getConversionRate(), "零点击时转化率应该为0");
    }
}
