package com.cms.service;

import com.cms.builder.TestDataBuilder;
import com.cms.dto.ContentCreateRequest;
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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("内容管理模块测试")
class ContentManagementTest {

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentStatisticsRepository contentStatisticsRepository;

    @Mock
    private HistoryRecordRepository historyRecordRepository;

    @InjectMocks
    private ContentService contentService;

    private ContentCreateRequest createRequest;
    private Content mockContent;
    private ContentStatistics mockStatistics;

    @BeforeEach
    void setUp() {
        createRequest = TestDataBuilder.buildContentCreateRequest();
        mockContent = TestDataBuilder.buildContent();
        mockStatistics = TestDataBuilder.buildContentStatistics(mockContent.getContentId());
    }

    @Nested
    @DisplayName("内容创建测试")
    class ContentCreationTests {

        @Test
        @DisplayName("创建内容 - 成功创建内容")
        void testCreateContent_Success() {
            when(contentRepository.save(any(Content.class))).thenReturn(mockContent);
            when(contentStatisticsRepository.save(any(ContentStatistics.class))
                .thenReturn(mockStatistics);

            Content result = contentService.createContent(createRequest);

            assertNotNull(result);
            assertEquals(mockContent.getContentTitle(), result.getContentTitle());
            assertEquals("pending_review", result.getContentStatus());
            assertEquals(mockContent.getContentBody(), result.getContentBody());

            verify(contentRepository, times(1)).save(any(Content.class));
            verify(contentStatisticsRepository, times(1)).save(any(ContentStatistics.class));
            verify(historyRecordRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("创建内容 - 验证内容信息正确性")
        void testCreateContent_ValidateInformation() {
            ContentCreateRequest request = TestDataBuilder.buildContentCreateRequest(
                "技术文章", "article", "tech");
            request.setContentAuthor("张三");
            request.setContentTags(Arrays.asList("Java", "Spring"));

            when(contentRepository.save(any(Content.class))).thenAnswer(invocation -> {
                Content content = invocation.getArgument(0);
                assertEquals("技术文章", content.getContentTitle());
                assertEquals("article", content.getContentType());
                assertEquals("tech", content.getContentCategory());
                assertEquals("张三", content.getContentAuthor());
                assertEquals(2, content.getContentTags().size());
                return content;
            });

            Content result = contentService.createContent(request);

            assertNotNull(result);
        }

        @Test
        @DisplayName("创建内容 - 验证内容配置准确性")
        void testCreateContent_ValidateConfiguration() {
            ContentCreateRequest request = TestDataBuilder.buildContentCreateRequest();
            request.setTemplateId("template_001");
            request.setContentCategory("news");
            request.setContentTags(Arrays.asList("热点", "时事"));

            when(contentRepository.save(any(Content.class))).thenAnswer(invocation -> {
                Content content = invocation.getArgument(0);
                assertEquals("template_001", content.getTemplateId());
                assertEquals("news", content.getContentCategory());
                assertTrue(content.getContentTags().contains("热点"));
                assertTrue(content.getContentTags().contains("时事"));
                return content;
            });

            contentService.createContent(request);

            verify(contentRepository, times(1)).save(any(Content.class));
        }

        @Test
        @DisplayName("创建内容 - 验证初始状态为待审核")
        void testCreateContent_InitialStatus() {
            when(contentRepository.save(any(Content.class))).thenAnswer(invocation -> {
                Content content = invocation.getArgument(0);
                assertEquals("pending_review", content.getContentStatus());
                return content;
            });

            contentService.createContent(createRequest);
        }

        @Test
        @DisplayName("创建内容 - 验证自动创建统计记录")
        void testCreateContent_CreateStatistics() {
            when(contentRepository.save(any(Content.class))).thenReturn(mockContent);
            when(contentStatisticsRepository.save(any(ContentStatistics.class)))
                .thenReturn(mockStatistics);

            contentService.createContent(createRequest);

            verify(contentStatisticsRepository, times(1)).save(any(ContentStatistics.class));
        }
    }

    @Nested
    @DisplayName("内容信息管理测试")
    class ContentInformationManagementTests {

        @Test
        @DisplayName("获取内容 - 成功获取内容")
        void testGetContentById_Success() {
            when(contentRepository.findById(anyString())).thenReturn(Optional.of(mockContent));

            Content result = contentService.getContentById(mockContent.getContentId());

            assertNotNull(result);
            assertEquals(mockContent.getContentId(), result.getContentId());
            assertEquals(mockContent.getContentTitle(), result.getContentTitle());
        }

        @Test
        @DisplayName("获取内容 - 内容不存在时抛出异常")
        void testGetContentById_NotFound() {
            when(contentRepository.findById(anyString())).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                contentService.getContentById("nonexistent");
            });

            assertEquals(404, exception.getCode());
            assertEquals("内容不存在", exception.getMessage());
        }

        @Test
        @DisplayName("更新内容 - 成功更新内容")
        void testUpdateContent_Success() {
            Content existingContent = TestDataBuilder.buildContent();
            existingContent.setContentStatus("draft");

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(existingContent));
            when(contentRepository.save(any(Content.class))).thenReturn(existingContent);

            ContentCreateRequest updateRequest = TestDataBuilder.buildContentCreateRequest();
            updateRequest.setContentTitle("更新后的标题");
            updateRequest.setContentBody("更新后的正文内容");

            Content result = contentService.updateContent(existingContent.getContentId(), updateRequest);

            assertNotNull(result);
            verify(contentRepository, times(1)).save(any(Content.class));
            verify(historyRecordRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("更新内容 - 已发布内容不可更新")
        void testUpdateContent_PublishedContent() {
            Content publishedContent = TestDataBuilder.buildPublishedContent();

            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                contentService.updateContent(publishedContent.getContentId(), createRequest);
            });

            assertEquals(400, exception.getCode());
            assertEquals("已发布内容不可编辑", exception.getMessage());
        }

        @Test
        @DisplayName("获取所有内容")
        void testGetAllContents() {
            List<Content> mockContents = TestDataBuilder.buildContentList(5);
            when(contentRepository.findAll()).thenReturn(mockContents);

            List<Content> result = contentService.getAllContents();

            assertNotNull(result);
            assertEquals(5, result.size());
        }

        @Test
        @DisplayName("按状态获取内容")
        void testGetContentsByStatus() {
            List<Content> mockContents = TestDataBuilder.buildContentList(3);
            when(contentRepository.findByContentStatus("pending_review")).thenReturn(mockContents);

            List<Content> result = contentService.getContentsByStatus("pending_review");

            assertNotNull(result);
            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("按分类获取内容")
        void testGetContentsByCategory() {
            List<Content> mockContents = TestDataBuilder.buildContentList(2);
            when(contentRepository.findByContentCategory("tech")).thenReturn(mockContents);

            List<Content> result = contentService.getContentsByCategory("tech");

            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("按作者获取内容")
        void testGetContentsByAuthor() {
            List<Content> mockContents = TestDataBuilder.buildContentList(4);
            when(contentRepository.findByContentAuthor("测试编辑")).thenReturn(mockContents);

            List<Content> result = contentService.getContentsByAuthor("测试编辑");

            assertNotNull(result);
            assertEquals(4, result.size());
        }
    }

    @Nested
    @DisplayName("内容配置管理测试")
    class ContentConfigurationTests {

        @Test
        @DisplayName("删除内容 - 成功删除草稿内容")
        void testDeleteContent_Success() {
            Content draftContent = TestDataBuilder.buildDraftContent();
            when(contentRepository.findById(anyString())).thenReturn(Optional.of(draftContent));
            doNothing().when(contentRepository).delete(any(Content.class));

            contentService.deleteContent(draftContent.getContentId());

            verify(contentRepository, times(1)).delete(any(Content.class));
        }

        @Test
        @DisplayName("删除内容 - 已发布内容不可删除")
        void testDeleteContent_PublishedContent() {
            Content publishedContent = TestDataBuilder.buildPublishedContent();
            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                contentService.deleteContent(publishedContent.getContentId());
            });

            assertEquals(400, exception.getCode());
            assertEquals("已发布内容不可删除", exception.getMessage());
        }

        @Test
        @DisplayName("提交审核 - 从草稿到待审核")
        void testSubmitForReview_DraftToPending() {
            Content draftContent = TestDataBuilder.buildDraftContent();
            when(contentRepository.findById(anyString())).thenReturn(Optional.of(draftContent));
            when(contentRepository.save(any(Content.class))).thenReturn(draftContent);

            Content result = contentService.submitForReview(draftContent.getContentId());

            assertEquals("pending_review", result.getContentStatus());
            verify(historyRecordRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("提交审核 - 已待审核内容不可重复提交")
        void testSubmitForReview_AlreadyPending() {
            Content pendingContent = TestDataBuilder.buildPendingReviewContent();
            when(contentRepository.findById(anyString())).thenReturn(Optional.of(pendingContent));

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                contentService.submitForReview(pendingContent.getContentId());
            });

            assertEquals(400, exception.getCode());
        }

        @Test
        @DisplayName("提交审核 - 已发布内容不可提交")
        void testSubmitForReview_PublishedContent() {
            Content publishedContent = TestDataBuilder.buildPublishedContent();
            when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));

            assertThrows(BusinessException.class, () -> {
                contentService.submitForReview(publishedContent.getContentId());
            });
        }
    }

    @Nested
    @DisplayName("内容状态生命周期测试")
    class ContentLifecycleTests {

        @Test
        @DisplayName("完整生命周期 - 草稿 -> 待审核 -> 已通过 -> 已发布 -> 已下架")
        void testCompleteContentLifecycle() {
            String contentId = "content_test_001";

            Content draftContent = TestDataBuilder.buildDraftContent();
            draftContent.setContentId(contentId);

            when(contentRepository.findById(contentId)).thenReturn(Optional.of(draftContent));
            when(contentRepository.save(any(Content.class))).thenReturn(draftContent);

            assertEquals("draft", draftContent.getContentStatus());

            contentService.submitForReview(contentId);
            assertEquals("pending_review", draftContent.getContentStatus());

            contentService.updateStatus(contentId, "approved", "reviewer_001");
            assertEquals("approved", draftContent.getContentStatus());

            contentService.updateStatus(contentId, "published", "publisher_001");
            assertEquals("published", draftContent.getContentStatus());

            contentService.updateStatus(contentId, "unpublished", "operator_001");
            assertEquals("unpublished", draftContent.getContentStatus());

            verify(contentRepository, times(5)).save(any(Content.class));
        }

        @Test
        @DisplayName("生命周期 - 审核拒绝路径")
        void testLifecycle_RejectedPath() {
            String contentId = "content_rejected_001";

            Content content = TestDataBuilder.buildPendingReviewContent();
            content.setContentId(contentId);

            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenReturn(content);

            assertEquals("pending_review", content.getContentStatus());

            contentService.updateStatus(contentId, "rejected", "reviewer_001");
            assertEquals("rejected", content.getContentStatus());

            verify(contentRepository, times(2)).save(any(Content.class));
        }

        @Test
        @DisplayName("生命周期 - 状态更新时记录历史")
        void testLifecycle_RecordHistory() {
            String contentId = "content_history_001";

            Content content = TestDataBuilder.buildPendingReviewContent();
            content.setContentId(contentId);

            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenReturn(content);

            contentService.updateStatus(contentId, "approved", "reviewer_001");

            verify(historyRecordRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("生命周期 - 验证状态流转规则")
        void testLifecycle_StateTransitionRules() {
            String contentId = "content_transition_001";

            Content content = TestDataBuilder.buildDraftContent();
            content.setContentId(contentId);

            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenReturn(content);

            assertEquals("draft", content.getContentStatus());

            contentService.submitForReview(contentId);
            assertEquals("pending_review", content.getContentStatus());

            contentService.updateStatus(contentId, "approved", "reviewer_001");
            assertEquals("approved", content.getContentStatus());

            contentService.updateStatus(contentId, "published", "publisher_001");
            assertEquals("published", content.getContentStatus());

            contentService.updateStatus(contentId, "unpublished", "operator_001");
            assertEquals("unpublished", content.getContentStatus());
        }
    }

    @Nested
    @DisplayName("内容配置验证测试")
    class ContentConfigurationValidationTests {

        @Test
        @DisplayName("验证内容配置 - 模板配置")
        void testValidateTemplateConfiguration() {
            Content content = TestDataBuilder.buildContentWithConfig(
                "template_article",
                "tech",
                Arrays.asList("Java", "Spring")
            );

            assertEquals("template_article", content.getTemplateId());
            assertEquals("tech", content.getContentCategory());
            assertTrue(content.getContentTags().contains("Java"));
            assertTrue(content.getContentTags().contains("Spring"));
        }

        @Test
        @DisplayName("验证内容配置 - 分类配置")
        void testValidateCategoryConfiguration() {
            Content content = TestDataBuilder.buildContent();
            content.setContentCategory("news");
            content.setContentTags(Arrays.asList("科技", "互联网"));

            assertEquals("news", content.getContentCategory());
            assertEquals(2, content.getContentTags().size());
        }

        @Test
        @DisplayName("验证内容配置 - 多标签配置")
        void testValidateMultiTagConfiguration() {
            Content content = TestDataBuilder.buildContent();
            content.setContentTags(Arrays.asList("标签1", "标签2", "标签3", "标签4", "标签5"));

            assertEquals(5, content.getContentTags().size());
            assertTrue(content.getContentTags().contains("标签3"));
        }
    }
}
