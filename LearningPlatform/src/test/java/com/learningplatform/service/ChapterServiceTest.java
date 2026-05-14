package com.learningplatform.service;

import com.learningplatform.builder.TestDataBuilder;
import com.learningplatform.entity.Chapter;
import com.learningplatform.entity.Course;
import com.learningplatform.exception.BusinessException;
import com.learningplatform.repository.ChapterRepository;
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
@DisplayName("ChapterService 章节管理服务测试")
class ChapterServiceTest {

    @Mock
    private ChapterRepository chapterRepository;

    @Mock
    private CourseService courseService;

    @InjectMocks
    private ChapterService chapterService;

    private Chapter testChapter;
    private Course testCourse;

    @BeforeEach
    void setUp() {
        testChapter = TestDataBuilder.createDefaultChapter();
        testCourse = TestDataBuilder.createDefaultCourse();
    }

    @Nested
    @DisplayName("章节创建测试")
    class ChapterCreationTests {

        @Test
        @DisplayName("应该成功创建章节")
        void shouldCreateChapterSuccessfully() {
            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            when(chapterRepository.countByCourseId(TestDataBuilder.TEST_COURSE_ID)).thenReturn(0L);
            when(chapterRepository.save(any(Chapter.class))).thenReturn(testChapter);

            Chapter created = chapterService.createChapter(testChapter);

            assertNotNull(created);
            verify(chapterRepository, times(1)).save(any(Chapter.class));
        }

        @Test
        @DisplayName("未指定ID时应该自动生成ID")
        void shouldGenerateIdWhenNotSpecified() {
            Chapter chapterWithoutId = TestDataBuilder.createDefaultChapter();
            chapterWithoutId.setChapterId(null);
            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            when(chapterRepository.countByCourseId(TestDataBuilder.TEST_COURSE_ID)).thenReturn(0L);
            when(chapterRepository.save(any(Chapter.class))).thenAnswer(invocation -> {
                Chapter saved = invocation.getArgument(0);
                saved.setChapterId("auto_chapter_001");
                return saved;
            });

            Chapter created = chapterService.createChapter(chapterWithoutId);

            assertNotNull(created.getChapterId());
        }

        @Test
        @DisplayName("未指定状态时应该设置为draft")
        void shouldSetDraftStatusWhenNotSpecified() {
            Chapter chapterWithoutStatus = TestDataBuilder.createDefaultChapter();
            chapterWithoutStatus.setChapterStatus(null);
            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            when(chapterRepository.countByCourseId(TestDataBuilder.TEST_COURSE_ID)).thenReturn(0L);
            when(chapterRepository.save(any(Chapter.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Chapter created = chapterService.createChapter(chapterWithoutStatus);

            assertEquals("draft", created.getChapterStatus());
        }

        @Test
        @DisplayName("未指定顺序时应该自动设置顺序")
        void shouldSetOrderWhenNotSpecified() {
            Chapter chapterWithoutOrder = TestDataBuilder.createDefaultChapter();
            chapterWithoutOrder.setChapterOrder(null);
            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            when(chapterRepository.countByCourseId(TestDataBuilder.TEST_COURSE_ID)).thenReturn(2L);
            when(chapterRepository.save(any(Chapter.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Chapter created = chapterService.createChapter(chapterWithoutOrder);

            assertEquals(3, created.getChapterOrder());
        }

        @Test
        @DisplayName("课程不存在时创建章节应该抛出异常")
        void shouldThrowExceptionWhenCourseDoesNotExist() {
            when(courseService.getCourseById("nonexistent_course")).thenThrow(new BusinessException(404, "课程不存在"));

            Chapter chapter = TestDataBuilder.createDefaultChapter();
            chapter.setCourseId("nonexistent_course");

            assertThrows(BusinessException.class, () -> {
                chapterService.createChapter(chapter);
            });
        }
    }

    @Nested
    @DisplayName("章节配置正确性测试")
    class ChapterConfigurationCorrectnessTests {

        @Test
        @DisplayName("章节应该正确关联课程")
        void shouldCorrectlyAssociateWithCourse() {
            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            when(chapterRepository.countByCourseId(TestDataBuilder.TEST_COURSE_ID)).thenReturn(0L);
            when(chapterRepository.save(any(Chapter.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Chapter created = chapterService.createChapter(testChapter);

            assertEquals(TestDataBuilder.TEST_COURSE_ID, created.getCourseId());
        }

        @Test
        @DisplayName("应该验证章节属于课程")
        void shouldValidateChapterBelongsToCourse() {
            Chapter chapter = TestDataBuilder.createDefaultChapter();
            chapter.setCourseId(TestDataBuilder.TEST_COURSE_ID);
            when(chapterRepository.findById(TestDataBuilder.TEST_CHAPTER_ID)).thenReturn(Optional.of(chapter));

            assertDoesNotThrow(() -> {
                chapterService.validateChapterBelongsToCourse(TestDataBuilder.TEST_CHAPTER_ID, TestDataBuilder.TEST_COURSE_ID);
            });
        }

        @Test
        @DisplayName("章节不属于课程时应该抛出异常")
        void shouldThrowExceptionWhenChapterNotBelongToCourse() {
            Chapter chapter = TestDataBuilder.createDefaultChapter();
            chapter.setCourseId("different_course");
            when(chapterRepository.findById(TestDataBuilder.TEST_CHAPTER_ID)).thenReturn(Optional.of(chapter));

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                chapterService.validateChapterBelongsToCourse(TestDataBuilder.TEST_CHAPTER_ID, TestDataBuilder.TEST_COURSE_ID);
            });
            assertTrue(exception.getMessage().contains("不属于"));
        }

        @Test
        @DisplayName("应该按顺序查询课程章节")
        void shouldGetChaptersOrderedBySequence() {
            List<Chapter> chapters = Arrays.asList(
                TestDataBuilder.createDefaultChapter(),
                TestDataBuilder.createDefaultChapter()
            );
            when(chapterRepository.findByCourseIdOrderByChapterOrderAsc(TestDataBuilder.TEST_COURSE_ID)).thenReturn(chapters);

            List<Chapter> result = chapterService.getChaptersByCourse(TestDataBuilder.TEST_COURSE_ID);

            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("应该只查询已发布的章节")
        void shouldGetOnlyPublishedChapters() {
            List<Chapter> publishedChapters = Arrays.asList(
                TestDataBuilder.createDefaultChapter(),
                TestDataBuilder.createDefaultChapter()
            );
            when(chapterRepository.findByCourseIdAndChapterStatus(TestDataBuilder.TEST_COURSE_ID, "published")).thenReturn(publishedChapters);

            List<Chapter> result = chapterService.getPublishedChaptersByCourse(TestDataBuilder.TEST_COURSE_ID);

            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("应该正确统计章节数量")
        void shouldCountChaptersCorrectly() {
            when(chapterRepository.countByCourseId(TestDataBuilder.TEST_COURSE_ID)).thenReturn(5L);

            int count = chapterService.getTotalChaptersCount(TestDataBuilder.TEST_COURSE_ID);

            assertEquals(5, count);
        }
    }

    @Nested
    @DisplayName("章节更新测试")
    class ChapterUpdateTests {

        @Test
        @DisplayName("应该更新章节名称")
        void shouldUpdateChapterName() {
            Chapter existingChapter = TestDataBuilder.createDefaultChapter();
            existingChapter.setChapterName("旧章节名称");
            when(chapterRepository.findById(TestDataBuilder.TEST_CHAPTER_ID)).thenReturn(Optional.of(existingChapter));
            when(chapterRepository.save(any(Chapter.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Chapter updateInfo = new Chapter();
            updateInfo.setChapterName("新章节名称");

            Chapter updated = chapterService.updateChapter(TestDataBuilder.TEST_CHAPTER_ID, updateInfo);

            assertEquals("新章节名称", updated.getChapterName());
        }

        @Test
        @DisplayName("应该更新章节顺序")
        void shouldUpdateChapterOrder() {
            Chapter existingChapter = TestDataBuilder.createDefaultChapter();
            existingChapter.setChapterOrder(1);
            when(chapterRepository.findById(TestDataBuilder.TEST_CHAPTER_ID)).thenReturn(Optional.of(existingChapter));
            when(chapterRepository.save(any(Chapter.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Chapter updateInfo = new Chapter();
            updateInfo.setChapterOrder(3);

            Chapter updated = chapterService.updateChapter(TestDataBuilder.TEST_CHAPTER_ID, updateInfo);

            assertEquals(3, updated.getChapterOrder());
        }

        @Test
        @DisplayName("应该更新章节时长")
        void shouldUpdateChapterDuration() {
            Chapter existingChapter = TestDataBuilder.createDefaultChapter();
            existingChapter.setChapterDuration(30L);
            when(chapterRepository.findById(TestDataBuilder.TEST_CHAPTER_ID)).thenReturn(Optional.of(existingChapter));
            when(chapterRepository.save(any(Chapter.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Chapter updateInfo = new Chapter();
            updateInfo.setChapterDuration(60L);

            Chapter updated = chapterService.updateChapter(TestDataBuilder.TEST_CHAPTER_ID, updateInfo);

            assertEquals(60L, updated.getChapterDuration());
        }

        @Test
        @DisplayName("应该更新章节描述")
        void shouldUpdateChapterDescription() {
            Chapter existingChapter = TestDataBuilder.createDefaultChapter();
            when(chapterRepository.findById(TestDataBuilder.TEST_CHAPTER_ID)).thenReturn(Optional.of(existingChapter));
            when(chapterRepository.save(any(Chapter.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Chapter updateInfo = new Chapter();
            updateInfo.setChapterDescription("更新后的章节描述");

            Chapter updated = chapterService.updateChapter(TestDataBuilder.TEST_CHAPTER_ID, updateInfo);

            assertEquals("更新后的章节描述", updated.getChapterDescription());
        }

        @Test
        @DisplayName("更新不存在的章节应该抛出异常")
        void shouldThrowExceptionWhenUpdatingNonExistentChapter() {
            when(chapterRepository.findById("nonexistent")).thenReturn(Optional.empty());

            Chapter updateInfo = new Chapter();
            updateInfo.setChapterName("新名称");

            assertThrows(BusinessException.class, () -> {
                chapterService.updateChapter("nonexistent", updateInfo);
            });
        }
    }

    @Nested
    @DisplayName("章节状态流转测试")
    class ChapterStateTransitionTests {

        @Test
        @DisplayName("应该发布草稿章节")
        void shouldPublishDraftChapter() {
            Chapter draftChapter = TestDataBuilder.createDefaultChapter();
            draftChapter.setChapterStatus("draft");
            when(chapterRepository.findById(TestDataBuilder.TEST_CHAPTER_ID)).thenReturn(Optional.of(draftChapter));
            when(chapterRepository.save(any(Chapter.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Chapter published = chapterService.publishChapter(TestDataBuilder.TEST_CHAPTER_ID);

            assertNotNull(published);
            assertEquals("published", published.getChapterStatus());
        }

        @Test
        @DisplayName("发布不存在的章节应该抛出异常")
        void shouldThrowExceptionWhenPublishingNonExistentChapter() {
            when(chapterRepository.findById("nonexistent")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () -> {
                chapterService.publishChapter("nonexistent");
            });
        }
    }

    @Nested
    @DisplayName("章节查询测试")
    class ChapterQueryTests {

        @Test
        @DisplayName("应该根据ID查询章节")
        void shouldGetChapterById() {
            when(chapterRepository.findById(TestDataBuilder.TEST_CHAPTER_ID)).thenReturn(Optional.of(testChapter));

            Chapter found = chapterService.getChapterById(TestDataBuilder.TEST_CHAPTER_ID);

            assertNotNull(found);
            assertEquals(testChapter.getChapterId(), found.getChapterId());
        }

        @Test
        @DisplayName("查询不存在的章节应该抛出异常")
        void shouldThrowExceptionForNonExistentChapter() {
            when(chapterRepository.findById("nonexistent")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () -> {
                chapterService.getChapterById("nonexistent");
            });
        }

        @Test
        @DisplayName("应该支持使用findChapterById查询")
        void shouldSupportFindChapterById() {
            when(chapterRepository.findById(TestDataBuilder.TEST_CHAPTER_ID)).thenReturn(Optional.of(testChapter));

            Optional<Chapter> found = chapterService.findChapterById(TestDataBuilder.TEST_CHAPTER_ID);

            assertTrue(found.isPresent());
            assertEquals(testChapter.getChapterId(), found.get().getChapterId());
        }
    }

    @Nested
    @DisplayName("章节删除测试")
    class ChapterDeletionTests {

        @Test
        @DisplayName("应该成功删除章节")
        void shouldDeleteChapterSuccessfully() {
            when(chapterRepository.findById(TestDataBuilder.TEST_CHAPTER_ID)).thenReturn(Optional.of(testChapter));
            doNothing().when(chapterRepository).delete(any(Chapter.class));

            assertDoesNotThrow(() -> {
                chapterService.deleteChapter(TestDataBuilder.TEST_CHAPTER_ID);
            });

            verify(chapterRepository, times(1)).delete(any(Chapter.class));
        }

        @Test
        @DisplayName("删除不存在的章节应该抛出异常")
        void shouldThrowExceptionWhenDeletingNonExistentChapter() {
            when(chapterRepository.findById("nonexistent")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () -> {
                chapterService.deleteChapter("nonexistent");
            });
        }
    }

    @Nested
    @DisplayName("章节信息完整性测试")
    class ChapterInfoIntegrityTests {

        @Test
        @DisplayName("创建的章节应该包含完整信息")
        void createdChapterShouldHaveCompleteInfo() {
            Chapter chapter = TestDataBuilder.createDefaultChapter();
            chapter.setChapterId("test_chapter_complete_001");
            chapter.setChapterName("完整信息测试章节");
            chapter.setCourseId(TestDataBuilder.TEST_COURSE_ID);
            chapter.setChapterOrder(1);
            chapter.setChapterDuration(45L);
            chapter.setChapterDescription("这是一个完整的测试章节");
            chapter.setChapterStatus("published");

            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            when(chapterRepository.save(any(Chapter.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Chapter created = chapterService.createChapter(chapter);

            assertNotNull(created.getChapterId());
            assertNotNull(created.getChapterName());
            assertNotNull(created.getCourseId());
            assertNotNull(created.getChapterOrder());
            assertNotNull(created.getChapterDuration());
            assertNotNull(created.getChapterDescription());
            assertNotNull(created.getChapterStatus());
        }

        @Test
        @DisplayName("空章节列表应该返回空列表")
        void shouldReturnEmptyListForNoChapters() {
            when(chapterRepository.findByCourseIdOrderByChapterOrderAsc(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Arrays.asList());

            List<Chapter> chapters = chapterService.getChaptersByCourse(TestDataBuilder.TEST_COURSE_ID);

            assertNotNull(chapters);
            assertTrue(chapters.isEmpty());
        }

        @Test
        @DisplayName("章节数量为0时应该正确统计")
        void shouldCountZeroForNoChapters() {
            when(chapterRepository.countByCourseId(TestDataBuilder.TEST_COURSE_ID)).thenReturn(0L);

            int count = chapterService.getTotalChaptersCount(TestDataBuilder.TEST_COURSE_ID);

            assertEquals(0, count);
        }
    }
}
