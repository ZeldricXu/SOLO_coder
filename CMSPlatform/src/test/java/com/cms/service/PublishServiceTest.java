package com.cms.service;

import com.cms.builder.TestDataBuilder;
import com.cms.dto.PublishExecuteRequest;
import com.cms.entity.Content;
import com.cms.entity.PublishRecord;
import com.cms.exception.BusinessException;
import com.cms.repository.HistoryRecordRepository;
import com.cms.repository.PublishRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublishServiceTest {

    @Mock
    private PublishRecordRepository publishRecordRepository;

    @Mock
    private ContentService contentService;

    @Mock
    private HistoryRecordRepository historyRecordRepository;

    @InjectMocks
    private PublishService publishService;

    private Content approvedContent;
    private Content publishedContent;
    private Content pendingContent;
    private PublishExecuteRequest publishRequest;
    private PublishRecord mockPublishRecord;

    @BeforeEach
    void setUp() {
        approvedContent = TestDataBuilder.buildContent();
        approvedContent.setContentStatus("approved");

        publishedContent = TestDataBuilder.buildContent();
        publishedContent.setContentStatus("published");

        pendingContent = TestDataBuilder.buildContent();
        pendingContent.setContentStatus("pending_review");

        publishRequest = TestDataBuilder.buildPublishExecuteRequest(approvedContent.getContentId());
        mockPublishRecord = TestDataBuilder.buildPublishRecord(approvedContent.getContentId());
    }

    @Test
    void testExecutePublish_Success() {
        when(contentService.getContentById(anyString())).thenReturn(approvedContent);
        when(publishRecordRepository.save(any(PublishRecord.class))).thenReturn(mockPublishRecord);
        when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedContent);

        PublishRecord result = publishService.executePublish(publishRequest);

        assertNotNull(result);
        assertEquals(mockPublishRecord.getPublishStatus(), result.getPublishStatus());

        verify(publishRecordRepository, times(1)).save(any(PublishRecord.class));
        verify(contentService, times(1)).updateStatus(anyString(), eq("published"), anyString());
        verify(historyRecordRepository, times(1)).save(any());
    }

    @Test
    void testExecutePublish_AlreadyPublished() {
        when(contentService.getContentById(anyString())).thenReturn(publishedContent);

        assertThrows(BusinessException.class, () -> {
            publishService.executePublish(publishRequest);
        });
    }

    @Test
    void testExecutePublish_NotApproved() {
        when(contentService.getContentById(anyString())).thenReturn(pendingContent);

        assertThrows(BusinessException.class, () -> {
            publishService.executePublish(publishRequest);
        });
    }

    @Test
    void testUnpublishContent_Success() {
        when(contentService.getContentById(anyString())).thenReturn(publishedContent);
        when(publishRecordRepository.save(any(PublishRecord.class))).thenReturn(mockPublishRecord);
        when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(publishedContent);

        PublishRecord result = publishService.unpublishContent(publishedContent.getContentId(), "operator_001", "操作员");

        assertNotNull(result);
        verify(publishRecordRepository, times(1)).save(any(PublishRecord.class));
        verify(contentService, times(1)).updateStatus(anyString(), eq("unpublished"), anyString());
    }

    @Test
    void testUnpublishContent_NotPublished() {
        when(contentService.getContentById(anyString())).thenReturn(approvedContent);

        assertThrows(BusinessException.class, () -> {
            publishService.unpublishContent(approvedContent.getContentId(), "operator_001", "操作员");
        });
    }

    @Test
    void testGetPublishesByContentId() {
        when(publishRecordRepository.findByContentId(anyString())).thenReturn(java.util.Arrays.asList(mockPublishRecord));

        var result = publishService.getPublishesByContentId(approvedContent.getContentId());

        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void testGetPublishesByStatus() {
        when(publishRecordRepository.findByPublishStatus("published")).thenReturn(java.util.Arrays.asList(mockPublishRecord));

        var result = publishService.getPublishesByStatus("published");

        assertNotNull(result);
        assertFalse(result.isEmpty());
    }
}
