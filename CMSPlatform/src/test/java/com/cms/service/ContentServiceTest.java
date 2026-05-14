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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContentServiceTest {

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

    @Test
    void testCreateContent_Success() {
        when(contentRepository.save(any(Content.class))).thenReturn(mockContent);
        when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenReturn(mockStatistics);

        Content result = contentService.createContent(createRequest);

        assertNotNull(result);
        assertEquals(mockContent.getContentTitle(), result.getContentTitle());
        assertEquals("pending_review", result.getContentStatus());

        verify(contentRepository, times(1)).save(any(Content.class));
        verify(contentStatisticsRepository, times(1)).save(any(ContentStatistics.class));
        verify(historyRecordRepository, times(1)).save(any());
    }

    @Test
    void testGetContentById_Success() {
        when(contentRepository.findById(anyString())).thenReturn(Optional.of(mockContent));

        Content result = contentService.getContentById(mockContent.getContentId());

        assertNotNull(result);
        assertEquals(mockContent.getContentId(), result.getContentId());
    }

    @Test
    void testGetContentById_NotFound() {
        when(contentRepository.findById(anyString())).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> {
            contentService.getContentById("nonexistent");
        });
    }

    @Test
    void testUpdateContent_Success() {
        Content existingContent = TestDataBuilder.buildContent();
        existingContent.setContentStatus("draft");

        when(contentRepository.findById(anyString())).thenReturn(Optional.of(existingContent));
        when(contentRepository.save(any(Content.class))).thenReturn(existingContent);

        ContentCreateRequest updateRequest = TestDataBuilder.buildContentCreateRequest();
        updateRequest.setContentTitle("更新后的标题");

        Content result = contentService.updateContent(existingContent.getContentId(), updateRequest);

        assertNotNull(result);
        verify(contentRepository, times(1)).save(any(Content.class));
    }

    @Test
    void testUpdateContent_PublishedContent() {
        Content publishedContent = TestDataBuilder.buildContent();
        publishedContent.setContentStatus("published");

        when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));

        assertThrows(BusinessException.class, () -> {
            contentService.updateContent(publishedContent.getContentId(), createRequest);
        });
    }

    @Test
    void testRecordView_Success() {
        Content publishedContent = TestDataBuilder.buildContent();
        publishedContent.setContentStatus("published");

        when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));
        when(contentStatisticsRepository.findByContentId(anyString())).thenReturn(Optional.of(mockStatistics));
        when(contentStatisticsRepository.save(any(ContentStatistics.class))).thenReturn(mockStatistics);

        contentService.recordView(publishedContent.getContentId());

        verify(contentStatisticsRepository, times(1)).save(any(ContentStatistics.class));
    }

    @Test
    void testRecordView_UnpublishedContent() {
        when(contentRepository.findById(anyString())).thenReturn(Optional.of(mockContent));

        assertThrows(BusinessException.class, () -> {
            contentService.recordView(mockContent.getContentId());
        });
    }

    @Test
    void testDeleteContent_Success() {
        when(contentRepository.findById(anyString())).thenReturn(Optional.of(mockContent));
        doNothing().when(contentRepository).delete(any(Content.class));

        contentService.deleteContent(mockContent.getContentId());

        verify(contentRepository, times(1)).delete(any(Content.class));
    }

    @Test
    void testDeleteContent_PublishedContent() {
        Content publishedContent = TestDataBuilder.buildContent();
        publishedContent.setContentStatus("published");

        when(contentRepository.findById(anyString())).thenReturn(Optional.of(publishedContent));

        assertThrows(BusinessException.class, () -> {
            contentService.deleteContent(publishedContent.getContentId());
        });
    }

    @Test
    void testGetAllContents() {
        when(contentRepository.findAll()).thenReturn(Arrays.asList(mockContent));

        var result = contentService.getAllContents();

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(1, result.size());
    }

    @Test
    void testGetContentsByStatus() {
        when(contentRepository.findByContentStatus("pending_review")).thenReturn(Arrays.asList(mockContent));

        var result = contentService.getContentsByStatus("pending_review");

        assertNotNull(result);
        assertFalse(result.isEmpty());
    }
}
