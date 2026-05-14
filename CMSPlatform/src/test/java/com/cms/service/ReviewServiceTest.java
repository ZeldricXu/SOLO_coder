package com.cms.service;

import com.cms.builder.TestDataBuilder;
import com.cms.dto.ReviewProcessRequest;
import com.cms.entity.Content;
import com.cms.entity.ReviewRecord;
import com.cms.exception.BusinessException;
import com.cms.repository.HistoryRecordRepository;
import com.cms.repository.ReviewRecordRepository;
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
class ReviewServiceTest {

    @Mock
    private ReviewRecordRepository reviewRecordRepository;

    @Mock
    private ContentService contentService;

    @Mock
    private HistoryRecordRepository historyRecordRepository;

    @InjectMocks
    private ReviewService reviewService;

    private Content pendingContent;
    private Content approvedContent;
    private Content publishedContent;
    private ReviewProcessRequest approveRequest;
    private ReviewProcessRequest rejectRequest;
    private ReviewRecord mockReviewRecord;

    @BeforeEach
    void setUp() {
        pendingContent = TestDataBuilder.buildContent();
        pendingContent.setContentStatus("pending_review");

        approvedContent = TestDataBuilder.buildContent();
        approvedContent.setContentStatus("approved");

        publishedContent = TestDataBuilder.buildContent();
        publishedContent.setContentStatus("published");

        approveRequest = TestDataBuilder.buildReviewProcessRequest(pendingContent.getContentId());
        rejectRequest = TestDataBuilder.buildRejectReviewRequest(pendingContent.getContentId());
        mockReviewRecord = TestDataBuilder.buildReviewRecord(pendingContent.getContentId());
    }

    @Test
    void testProcessReview_Approved_Success() {
        when(contentService.getContentById(anyString())).thenReturn(pendingContent);
        when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(mockReviewRecord);
        when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

        ReviewRecord result = reviewService.processReview(approveRequest);

        assertNotNull(result);
        assertEquals(mockReviewRecord.getReviewStatus(), result.getReviewStatus());

        verify(reviewRecordRepository, times(1)).save(any(ReviewRecord.class));
        verify(contentService, times(1)).updateStatus(anyString(), eq("approved"), anyString());
        verify(historyRecordRepository, times(1)).save(any());
    }

    @Test
    void testProcessReview_Rejected_Success() {
        mockReviewRecord.setReviewStatus("rejected");
        when(contentService.getContentById(anyString())).thenReturn(pendingContent);
        when(reviewRecordRepository.save(any(ReviewRecord.class))).thenReturn(mockReviewRecord);
        when(contentService.updateStatus(anyString(), anyString(), anyString())).thenReturn(pendingContent);

        ReviewRecord result = reviewService.processReview(rejectRequest);

        assertNotNull(result);
        verify(reviewRecordRepository, times(1)).save(any(ReviewRecord.class));
        verify(contentService, times(1)).updateStatus(anyString(), eq("rejected"), anyString());
    }

    @Test
    void testProcessReview_AlreadyApproved() {
        when(contentService.getContentById(anyString())).thenReturn(approvedContent);

        assertThrows(BusinessException.class, () -> {
            reviewService.processReview(approveRequest);
        });
    }

    @Test
    void testProcessReview_AlreadyPublished() {
        when(contentService.getContentById(anyString())).thenReturn(publishedContent);

        assertThrows(BusinessException.class, () -> {
            reviewService.processReview(approveRequest);
        });
    }

    @Test
    void testProcessReview_InvalidStatus() {
        ReviewProcessRequest invalidRequest = TestDataBuilder.buildReviewProcessRequest(pendingContent.getContentId());
        invalidRequest.setReviewStatus("invalid_status");

        when(contentService.getContentById(anyString())).thenReturn(pendingContent);

        assertThrows(BusinessException.class, () -> {
            reviewService.processReview(invalidRequest);
        });
    }

    @Test
    void testGetReviewsByContentId() {
        when(reviewRecordRepository.findByContentId(anyString())).thenReturn(java.util.Arrays.asList(mockReviewRecord));

        var result = reviewService.getReviewsByContentId(pendingContent.getContentId());

        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void testGetReviewsByReviewerId() {
        when(reviewRecordRepository.findByReviewerId(anyString())).thenReturn(java.util.Arrays.asList(mockReviewRecord));

        var result = reviewService.getReviewsByReviewerId("reviewer_001");

        assertNotNull(result);
        assertFalse(result.isEmpty());
    }
}
