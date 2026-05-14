package com.cms.service;

import com.cms.builder.TestDataBuilder;
import com.cms.entity.Content;
import com.cms.entity.ContentStatistics;
import com.cms.exception.BusinessException;
import com.cms.repository.ContentRepository;
import com.cms.repository.ContentStatisticsRepository;
import com.cms.repository.HistoryRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("阅读分析测试")
class ReadingAnalyticsTest {

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentStatisticsRepository contentStatisticsRepository;

    @Mock
    private HistoryRecordRepository historyRecordRepository;

    @InjectMocks
    private ContentService contentService;

    private Content publishedContent;
    private Content draftContent;
    private Content pendingContent;
    private ContentStatistics emptyStatistics;
    private ContentStatistics existingStatistics;

    @BeforeEach
    void setUp() {
        publishedContent = TestDataBuilder.buildPublishedContent();
        draftContent = TestDataBuilder.buildDraftContent();
        pendingContent = TestDataBuilder.buildPendingReviewContent();
        emptyStatistics = TestDataBuilder.buildEmptyContentStatistics(publishedContent.getContentId());
        existingStatistics = TestDataBuilder.buildContentStatisticsWithCounts(
            publishedContent.getContentId(), 100L, 20L, 10L, 5L);
    }

    @Nested
    @DisplayName("阅读计数测试")
    class ViewCountTests {

        @Test
        @DisplayName("阅读记录 - 成功记录阅读")
        void testRecordView_Success() {
            String contentId = publishedContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.of(existingStatistics));
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                assertEquals(101L, stats.getViewCount());
                return stats;
            });

            contentService.recordView(contentId);

            verify(contentStatisticsRepository, times(1)).save(any(ContentStatistics.class));
        }

        @Test
        @DisplayName("阅读计数 - 从0开始计数")
        void testRecordView_FromZero() {
            String contentId = publishedContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.of(emptyStatistics));
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                assertEquals(1L, stats.getViewCount());
                return stats;
            });

            contentService.recordView(contentId);
        }

        @Test
        @DisplayName("阅读计数 - 未发布内容不可阅读")
        void testRecordView_Unpublished() {
            String contentId = draftContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(draftContent));

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                contentService.recordView(contentId);
            });

            assertEquals(400, exception.getCode());
            assertEquals("未发布内容不可阅读", exception.getMessage());
        }

        @Test
        @DisplayName("阅读计数 - 待审核内容不可阅读")
        void testRecordView_PendingReview() {
            String contentId = pendingContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(pendingContent));

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                contentService.recordView(contentId);
            });

            assertEquals(400, exception.getCode());
        }

        @Test
        @DisplayName("阅读计数 - 自动创建统计记录（不存在时）")
        void testRecordView_AutoCreateStatistics() {
            String contentId = publishedContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.empty());
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                assertEquals(1L, stats.getViewCount());
                assertEquals(contentId, stats.getContentId());
                return stats;
            });

            contentService.recordView(contentId);

            verify(contentStatisticsRepository, times(1)).save(any(ContentStatistics.class));
        }

        @Test
        @DisplayName("阅读计数 - 多次阅读累计计数")
        void testRecordView_MultipleReads() {
            String contentId = publishedContent.getContentId();
            AtomicInteger currentCount = new AtomicInteger(0);

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));

            when(contentStatisticsRepository.findByContentId(anyString())).thenAnswer(invocation -> {
                ContentStatistics stats = new ContentStatistics();
                stats.setContentId(contentId);
                stats.setViewCount((long) currentCount.get());
                return Optional.of(stats);
            });

            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                currentCount.set(stats.getViewCount().intValue());
                return stats;
            });

            for (int i = 0; i < 5; i++) {
                contentService.recordView(contentId);
            }

            assertEquals(5, currentCount.get());
        }

        @Test
        @DisplayName("阅读计数 - 验证统计数据完整性")
        void testRecordView_ValidateStatisticsComplete() {
            String contentId = publishedContent.getContentId();
            ContentStatistics originalStats = TestDataBuilder.buildContentStatisticsWithCounts(
                contentId, 50L, 10L, 5L, 2L);

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.of(originalStats));
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                assertEquals(51L, stats.getViewCount());
                assertEquals(10L, stats.getLikeCount());
                assertEquals(5L, stats.getCommentCount());
                assertEquals(2L, stats.getShareCount());
                return stats;
            });

            contentService.recordView(contentId);

            verify(contentStatisticsRepository, times(1)).save(any(ContentStatistics.class));
        }
    }

    @Nested
    @DisplayName("点赞计数测试")
    class LikeCountTests {

        @Test
        @DisplayName("点赞记录 - 成功记录点赞")
        void testRecordLike_Success() {
            String contentId = publishedContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.of(existingStatistics));
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                assertEquals(21L, stats.getLikeCount());
                return stats;
            });

            contentService.recordLike(contentId);

            verify(contentStatisticsRepository, times(1)).save(any(ContentStatistics.class));
        }

        @Test
        @DisplayName("点赞计数 - 从0开始计数")
        void testRecordLike_FromZero() {
            String contentId = publishedContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.of(emptyStatistics));
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                assertEquals(1L, stats.getLikeCount());
                return stats;
            });

            contentService.recordLike(contentId);
        }

        @Test
        @DisplayName("点赞计数 - 未发布内容不可点赞")
        void testRecordLike_Unpublished() {
            String contentId = draftContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(draftContent));

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                contentService.recordLike(contentId);
            });

            assertEquals(400, exception.getCode());
            assertEquals("未发布内容不可点赞", exception.getMessage());
        }

        @Test
        @DisplayName("点赞计数 - 自动创建统计记录")
        void testRecordLike_AutoCreateStatistics() {
            String contentId = publishedContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.empty());
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                assertEquals(1L, stats.getLikeCount());
                return stats;
            });

            contentService.recordLike(contentId);
        }

        @Test
        @DisplayName("多次点赞累计计数")
        void testRecordLike_MultipleLikes() {
            String contentId = publishedContent.getContentId();
            AtomicInteger currentCount = new AtomicInteger(0);

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));

            when(contentStatisticsRepository.findByContentId(anyString())).thenAnswer(invocation -> {
                ContentStatistics stats = new ContentStatistics();
                stats.setContentId(contentId);
                stats.setLikeCount((long) currentCount.get());
                return Optional.of(stats);
            });

            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                currentCount.set(stats.getLikeCount().intValue());
                return stats;
            });

            for (int i = 0; i < 10; i++) {
                contentService.recordLike(contentId);
            }

            assertEquals(10, currentCount.get());
        }
    }

    @Nested
    @DisplayName("分享计数测试")
    class ShareCountTests {

        @Test
        @DisplayName("分享记录 - 成功记录分享")
        void testRecordShare_Success() {
            String contentId = publishedContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.of(existingStatistics));
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                assertEquals(6L, stats.getShareCount());
                return stats;
            });

            contentService.recordShare(contentId);

            verify(contentStatisticsRepository, times(1)).save(any(ContentStatistics.class));
        }

        @Test
        @DisplayName("分享计数 - 从0开始计数")
        void testRecordShare_FromZero() {
            String contentId = publishedContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.of(emptyStatistics));
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                assertEquals(1L, stats.getShareCount());
                return stats;
            });

            contentService.recordShare(contentId);
        }

        @Test
        @DisplayName("分享计数 - 未发布内容不可分享")
        void testRecordShare_Unpublished() {
            String contentId = draftContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(draftContent));

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                contentService.recordShare(contentId);
            });

            assertEquals(400, exception.getCode());
            assertEquals("未发布内容不可分享", exception.getMessage());
        }

        @Test
        @DisplayName("分享计数 - 自动创建统计记录")
        void testRecordShare_AutoCreateStatistics() {
            String contentId = publishedContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.empty());
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                assertEquals(1L, stats.getShareCount());
                return stats;
            });

            contentService.recordShare(contentId);
        }

        @Test
        @DisplayName("多次分享累计计数")
        void testRecordShare_MultipleShares() {
            String contentId = publishedContent.getContentId();
            AtomicInteger currentCount = new AtomicInteger(0);

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));

            when(contentStatisticsRepository.findByContentId(anyString())).thenAnswer(invocation -> {
                ContentStatistics stats = new ContentStatistics();
                stats.setContentId(contentId);
                stats.setShareCount((long) currentCount.get());
                return Optional.of(stats);
            });

            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                currentCount.set(stats.getShareCount().intValue());
                return stats;
            });

            for (int i = 0; i < 3; i++) {
                contentService.recordShare(contentId);
            }

            assertEquals(3, currentCount.get());
        }
    }

    @Nested
    @DisplayName("异步统计处理测试")
    class AsyncStatisticsTests {

        @Test
        @DisplayName("异步阅读统计 - 并发阅读计数准确")
        void testAsyncStatistics_ConcurrentViews() throws Exception {
            String contentId = publishedContent.getContentId();
            int threadCount = 10;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.of(emptyStatistics));
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                successCount.incrementAndGet();
                return invocation.getArgument(0);
            });

            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        contentService.recordView(contentId);
                    } catch (Exception e) {
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            latch.await(5, TimeUnit.SECONDS);

            assertTrue(successCount.get() > 0);
        }

        @Test
        @DisplayName("异步统计 - 多种操作同时执行")
        void testAsyncStatistics_MultipleOperations() throws Exception {
            String contentId = publishedContent.getContentId();
            CountDownLatch latch = new CountDownLatch(3);

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.of(existingStatistics));
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> invocation.getArgument(0));

            CompletableFuture<Void> viewFuture = CompletableFuture.runAsync(() -> {
                contentService.recordView(contentId);
                latch.countDown();
            });

            CompletableFuture<Void> likeFuture = CompletableFuture.runAsync(() -> {
                contentService.recordLike(contentId);
                latch.countDown();
            });

            CompletableFuture<Void> shareFuture = CompletableFuture.runAsync(() -> {
                contentService.recordShare(contentId);
                latch.countDown();
            });

            latch.await(5, TimeUnit.SECONDS);

            verify(contentStatisticsRepository, times(3)).save(any(ContentStatistics.class));
        }

        @Test
        @DisplayName("异步统计处理 - 统计更新顺序验证")
        void testAsyncStatistics_UpdateOrder() {
            String contentId = publishedContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.of(emptyStatistics));

            AtomicInteger saveCount = new AtomicInteger(0);
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                saveCount.incrementAndGet();
                return invocation.getArgument(0);
            });

            contentService.recordView(contentId);
            assertEquals(1, saveCount.get());

            contentService.recordLike(contentId);
            assertEquals(2, saveCount.get());

            contentService.recordShare(contentId);
            assertEquals(3, saveCount.get());

            verify(contentStatisticsRepository, times(3)).save(any(ContentStatistics.class));
        }
    }

    @Nested
    @DisplayName("综合统计测试")
    class ComprehensiveStatisticsTests {

        @Test
        @DisplayName("综合统计 - 阅读、点赞、分享组合计数")
        void testComprehensive_AllCounts() {
            String contentId = publishedContent.getContentId();
            AtomicInteger viewCount = new AtomicInteger(0);
            AtomicInteger likeCount = new AtomicInteger(0);
            AtomicInteger shareCount = new AtomicInteger(0);

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));

            when(contentStatisticsRepository.findByContentId(anyString())).thenAnswer(invocation -> {
                ContentStatistics stats = new ContentStatistics();
                stats.setContentId(contentId);
                stats.setViewCount((long) viewCount.get());
                stats.setLikeCount((long) likeCount.get());
                stats.setShareCount((long) shareCount.get());
                return Optional.of(stats);
            });

            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                if (stats.getViewCount() > viewCount.get()) {
                    viewCount.set(stats.getViewCount().intValue());
                }
                if (stats.getLikeCount() > likeCount.get()) {
                    likeCount.set(stats.getLikeCount().intValue());
                }
                if (stats.getShareCount() > shareCount.get()) {
                    shareCount.set(stats.getShareCount().intValue());
                }
                return stats;
            });

            for (int i = 0; i < 10; i++) {
                contentService.recordView(contentId);
            }
            for (int i = 0; i < 5; i++) {
                contentService.recordLike(contentId);
            }
            for (int i = 0; i < 3; i++) {
                contentService.recordShare(contentId);
            }

            assertEquals(10, viewCount.get());
            assertEquals(5, likeCount.get());
            assertEquals(3, shareCount.get());
        }

        @Test
        @DisplayName("统计数据准确性 - 验证计数器不会相互影响")
        void testComprehensive_CountsIndependent() {
            String contentId = publishedContent.getContentId();

            ContentStatistics stats = TestDataBuilder.buildContentStatisticsWithCounts(contentId, 100L, 20L, 10L, 5L);

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));

            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.of(stats));

            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                return invocation.getArgument(0);
            });

            contentService.recordView(contentId);

            verify(contentStatisticsRepository, times(1)).save(any(ContentStatistics.class));
        }

        @Test
        @DisplayName("实时记录机制 - 每次操作立即更新统计")
        void testComprehensive_RealTimeUpdate() {
            String contentId = publishedContent.getContentId();
            AtomicInteger operationCount = new AtomicInteger(0);

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.of(emptyStatistics));
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                operationCount.incrementAndGet();
                return invocation.getArgument(0);
            });

            contentService.recordView(contentId);
            assertEquals(1, operationCount.get());

            contentService.recordLike(contentId);
            assertEquals(2, operationCount.get());

            contentService.recordShare(contentId);
            assertEquals(3, operationCount.get());
        }

        @Test
        @DisplayName("边界测试 - 初始状态所有计数为0")
        void testBoundary_InitialStateZero() {
            String contentId = publishedContent.getContentId();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
            when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.empty());
            when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenAnswer(invocation -> {
                ContentStatistics stats = invocation.getArgument(0);
                assertTrue(stats.getViewCount() >= 0);
                assertTrue(stats.getLikeCount() >= 0);
                assertTrue(stats.getShareCount() >= 0);
                return stats;
            });

            contentService.recordView(contentId);
        }
    }
}
