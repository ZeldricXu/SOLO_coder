package com.learningplatform.service;

import com.learningplatform.builder.TestDataBuilder;
import com.learningplatform.entity.Chapter;
import com.learningplatform.entity.Course;
import com.learningplatform.entity.Resource;
import com.learningplatform.exception.BusinessException;
import com.learningplatform.repository.ResourceRepository;
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
@DisplayName("ResourceService 资源管理服务测试")
class ResourceServiceTest {

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private CourseService courseService;

    @Mock
    private ChapterService chapterService;

    @InjectMocks
    private ResourceService resourceService;

    private Resource testResource;
    private Course testCourse;
    private Chapter testChapter;

    @BeforeEach
    void setUp() {
        testResource = TestDataBuilder.createDefaultResource();
        testCourse = TestDataBuilder.createDefaultCourse();
        testChapter = TestDataBuilder.createDefaultChapter();
    }

    @Nested
    @DisplayName("资源创建测试")
    class ResourceCreationTests {

        @Test
        @DisplayName("应该成功创建资源")
        void shouldCreateResourceSuccessfully() {
            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            when(resourceRepository.save(any(Resource.class))).thenReturn(testResource);

            Resource created = resourceService.createResource(testResource);

            assertNotNull(created);
            verify(resourceRepository, times(1)).save(any(Resource.class));
        }

        @Test
        @DisplayName("未指定ID时应该自动生成ID")
        void shouldGenerateIdWhenNotSpecified() {
            Resource resourceWithoutId = TestDataBuilder.createDefaultResource();
            resourceWithoutId.setResourceId(null);
            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> {
                Resource saved = invocation.getArgument(0);
                saved.setResourceId("auto_resource_001");
                return saved;
            });

            Resource created = resourceService.createResource(resourceWithoutId);

            assertNotNull(created.getResourceId());
        }

        @Test
        @DisplayName("未指定状态时应该设置为active")
        void shouldSetActiveStatusWhenNotSpecified() {
            Resource resourceWithoutStatus = TestDataBuilder.createDefaultResource();
            resourceWithoutStatus.setResourceStatus(null);
            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Resource created = resourceService.createResource(resourceWithoutStatus);

            assertEquals("active", created.getResourceStatus());
        }

        @Test
        @DisplayName("课程不存在时创建资源应该抛出异常")
        void shouldThrowExceptionWhenCourseDoesNotExist() {
            when(courseService.getCourseById("nonexistent_course")).thenThrow(new BusinessException(404, "课程不存在"));

            Resource resource = TestDataBuilder.createDefaultResource();
            resource.setCourseId("nonexistent_course");

            assertThrows(BusinessException.class, () -> {
                resourceService.createResource(resource);
            });
        }
    }

    @Nested
    @DisplayName("资源关联管理测试")
    class ResourceAssociationManagementTests {

        @Test
        @DisplayName("资源应该正确关联课程")
        void shouldCorrectlyAssociateWithCourse() {
            Resource resource = TestDataBuilder.createDefaultResource();
            resource.setCourseId(TestDataBuilder.TEST_COURSE_ID);
            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Resource created = resourceService.createResource(resource);

            assertEquals(TestDataBuilder.TEST_COURSE_ID, created.getCourseId());
        }

        @Test
        @DisplayName("资源应该正确关联章节")
        void shouldCorrectlyAssociateWithChapter() {
            Resource resourceWithChapter = TestDataBuilder.createDefaultResource();
            resourceWithChapter.setCourseId(TestDataBuilder.TEST_COURSE_ID);
            resourceWithChapter.setChapterId(TestDataBuilder.TEST_CHAPTER_ID);
            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            doNothing().when(chapterService).validateChapterBelongsToCourse(TestDataBuilder.TEST_CHAPTER_ID, TestDataBuilder.TEST_COURSE_ID);
            when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Resource created = resourceService.createResource(resourceWithChapter);

            assertEquals(TestDataBuilder.TEST_CHAPTER_ID, created.getChapterId());
        }

        @Test
        @DisplayName("章节不属于课程时创建资源应该抛出异常")
        void shouldThrowExceptionWhenChapterNotBelongToCourse() {
            Resource resource = TestDataBuilder.createDefaultResource();
            resource.setCourseId(TestDataBuilder.TEST_COURSE_ID);
            resource.setChapterId("wrong_chapter");
            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            doThrow(new BusinessException(400, "章节不属于该课程"))
                .when(chapterService).validateChapterBelongsToCourse("wrong_chapter", TestDataBuilder.TEST_COURSE_ID);

            assertThrows(BusinessException.class, () -> {
                resourceService.createResource(resource);
            });
        }

        @Test
        @DisplayName("应该支持不关联章节的资源")
        void shouldSupportResourcesWithoutChapter() {
            Resource resourceWithoutChapter = TestDataBuilder.createDefaultResource();
            resourceWithoutChapter.setChapterId(null);
            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Resource created = resourceService.createResource(resourceWithoutChapter);

            assertNull(created.getChapterId());
        }
    }

    @Nested
    @DisplayName("资源查询测试")
    class ResourceQueryTests {

        @Test
        @DisplayName("应该根据ID查询资源")
        void shouldGetResourceById() {
            when(resourceRepository.findById(TestDataBuilder.TEST_RESOURCE_ID)).thenReturn(Optional.of(testResource));

            Resource found = resourceService.getResourceById(TestDataBuilder.TEST_RESOURCE_ID);

            assertNotNull(found);
            assertEquals(testResource.getResourceId(), found.getResourceId());
        }

        @Test
        @DisplayName("查询不存在的资源应该抛出异常")
        void shouldThrowExceptionForNonExistentResource() {
            when(resourceRepository.findById("nonexistent")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () -> {
                resourceService.getResourceById("nonexistent");
            });
        }

        @Test
        @DisplayName("应该根据课程查询所有资源")
        void shouldGetResourcesByCourse() {
            List<Resource> resources = Arrays.asList(
                TestDataBuilder.createDefaultResource(),
                TestDataBuilder.createDefaultResource()
            );
            when(resourceRepository.findByCourseId(TestDataBuilder.TEST_COURSE_ID)).thenReturn(resources);

            List<Resource> result = resourceService.getResourcesByCourse(TestDataBuilder.TEST_COURSE_ID);

            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("应该根据章节查询资源")
        void shouldGetResourcesByChapter() {
            List<Resource> chapterResources = Arrays.asList(
                TestDataBuilder.createDefaultResource(),
                TestDataBuilder.createDefaultResource()
            );
            when(resourceRepository.findByChapterId(TestDataBuilder.TEST_CHAPTER_ID)).thenReturn(chapterResources);

            List<Resource> result = resourceService.getResourcesByChapter(TestDataBuilder.TEST_CHAPTER_ID);

            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("应该只查询活跃状态的资源")
        void shouldGetOnlyActiveResources() {
            List<Resource> activeResources = Arrays.asList(
                TestDataBuilder.createDefaultResource(),
                TestDataBuilder.createDefaultResource()
            );
            when(resourceRepository.findByCourseIdAndResourceStatus(TestDataBuilder.TEST_COURSE_ID, "active")).thenReturn(activeResources);

            List<Resource> result = resourceService.getActiveResourcesByCourse(TestDataBuilder.TEST_COURSE_ID);

            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("应该支持使用findResourceById查询")
        void shouldSupportFindResourceById() {
            when(resourceRepository.findById(TestDataBuilder.TEST_RESOURCE_ID)).thenReturn(Optional.of(testResource));

            Optional<Resource> found = resourceService.findResourceById(TestDataBuilder.TEST_RESOURCE_ID);

            assertTrue(found.isPresent());
            assertEquals(testResource.getResourceId(), found.get().getResourceId());
        }
    }

    @Nested
    @DisplayName("资源更新测试")
    class ResourceUpdateTests {

        @Test
        @DisplayName("应该更新资源名称")
        void shouldUpdateResourceName() {
            Resource existingResource = TestDataBuilder.createDefaultResource();
            existingResource.setResourceName("旧资源名称");
            when(resourceRepository.findById(TestDataBuilder.TEST_RESOURCE_ID)).thenReturn(Optional.of(existingResource));
            when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Resource updateInfo = new Resource();
            updateInfo.setResourceName("新资源名称");

            Resource updated = resourceService.updateResource(TestDataBuilder.TEST_RESOURCE_ID, updateInfo);

            assertEquals("新资源名称", updated.getResourceName());
        }

        @Test
        @DisplayName("应该更新资源类型")
        void shouldUpdateResourceType() {
            Resource existingResource = TestDataBuilder.createDefaultResource();
            existingResource.setResourceType("video");
            when(resourceRepository.findById(TestDataBuilder.TEST_RESOURCE_ID)).thenReturn(Optional.of(existingResource));
            when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Resource updateInfo = new Resource();
            updateInfo.setResourceType("document");

            Resource updated = resourceService.updateResource(TestDataBuilder.TEST_RESOURCE_ID, updateInfo);

            assertEquals("document", updated.getResourceType());
        }

        @Test
        @DisplayName("应该更新资源状态")
        void shouldUpdateResourceStatus() {
            Resource existingResource = TestDataBuilder.createDefaultResource();
            existingResource.setResourceStatus("active");
            when(resourceRepository.findById(TestDataBuilder.TEST_RESOURCE_ID)).thenReturn(Optional.of(existingResource));
            when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Resource updateInfo = new Resource();
            updateInfo.setResourceStatus("inactive");

            Resource updated = resourceService.updateResource(TestDataBuilder.TEST_RESOURCE_ID, updateInfo);

            assertEquals("inactive", updated.getResourceStatus());
        }

        @Test
        @DisplayName("更新不存在的资源应该抛出异常")
        void shouldThrowExceptionWhenUpdatingNonExistentResource() {
            when(resourceRepository.findById("nonexistent")).thenReturn(Optional.empty());

            Resource updateInfo = new Resource();
            updateInfo.setResourceName("新名称");

            assertThrows(BusinessException.class, () -> {
                resourceService.updateResource("nonexistent", updateInfo);
            });
        }
    }

    @Nested
    @DisplayName("资源删除测试")
    class ResourceDeletionTests {

        @Test
        @DisplayName("应该成功删除资源")
        void shouldDeleteResourceSuccessfully() {
            when(resourceRepository.findById(TestDataBuilder.TEST_RESOURCE_ID)).thenReturn(Optional.of(testResource));
            doNothing().when(resourceRepository).delete(any(Resource.class));

            assertDoesNotThrow(() -> {
                resourceService.deleteResource(TestDataBuilder.TEST_RESOURCE_ID);
            });

            verify(resourceRepository, times(1)).delete(any(Resource.class));
        }

        @Test
        @DisplayName("删除不存在的资源应该抛出异常")
        void shouldThrowExceptionWhenDeletingNonExistentResource() {
            when(resourceRepository.findById("nonexistent")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () -> {
                resourceService.deleteResource("nonexistent");
            });
        }
    }

    @Nested
    @DisplayName("资源信息完整性测试")
    class ResourceInfoIntegrityTests {

        @Test
        @DisplayName("创建的资源应该包含完整信息")
        void createdResourceShouldHaveCompleteInfo() {
            Resource resource = TestDataBuilder.createDefaultResource();
            resource.setResourceId("test_resource_complete_001");
            resource.setResourceName("完整信息测试资源");
            resource.setCourseId(TestDataBuilder.TEST_COURSE_ID);
            resource.setChapterId(TestDataBuilder.TEST_CHAPTER_ID);
            resource.setResourceType("video");
            resource.setResourceUrl("/resources/test.mp4");
            resource.setResourceSize(102400L);
            resource.setResourceStatus("active");
            resource.setUploadedBy("teacher_001");

            when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
            doNothing().when(chapterService).validateChapterBelongsToCourse(TestDataBuilder.TEST_CHAPTER_ID, TestDataBuilder.TEST_COURSE_ID);
            when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Resource created = resourceService.createResource(resource);

            assertNotNull(created.getResourceId());
            assertNotNull(created.getResourceName());
            assertNotNull(created.getCourseId());
            assertNotNull(created.getResourceType());
            assertNotNull(created.getResourceUrl());
            assertNotNull(created.getResourceSize());
            assertNotNull(created.getResourceStatus());
            assertNotNull(created.getUploadedBy());
        }

        @Test
        @DisplayName("空资源列表应该返回空列表")
        void shouldReturnEmptyListForNoResources() {
            when(resourceRepository.findByCourseId(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Arrays.asList());

            List<Resource> resources = resourceService.getResourcesByCourse(TestDataBuilder.TEST_COURSE_ID);

            assertNotNull(resources);
            assertTrue(resources.isEmpty());
        }

        @Test
        @DisplayName("空活跃资源列表应该返回空列表")
        void shouldReturnEmptyListForNoActiveResources() {
            when(resourceRepository.findByCourseIdAndResourceStatus(TestDataBuilder.TEST_COURSE_ID, "active")).thenReturn(Arrays.asList());

            List<Resource> activeResources = resourceService.getActiveResourcesByCourse(TestDataBuilder.TEST_COURSE_ID);

            assertNotNull(activeResources);
            assertTrue(activeResources.isEmpty());
        }
    }

    @Nested
    @DisplayName("资源状态管理测试")
    class ResourceStateManagementTests {

        @Test
        @DisplayName("应该将资源设置为非活跃状态")
        void shouldSetResourceToInactiveStatus() {
            Resource activeResource = TestDataBuilder.createDefaultResource();
            activeResource.setResourceStatus("active");
            when(resourceRepository.findById(TestDataBuilder.TEST_RESOURCE_ID)).thenReturn(Optional.of(activeResource));
            when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Resource updateInfo = new Resource();
            updateInfo.setResourceStatus("inactive");

            Resource updated = resourceService.updateResource(TestDataBuilder.TEST_RESOURCE_ID, updateInfo);

            assertEquals("inactive", updated.getResourceStatus());
        }

        @Test
        @DisplayName("应该支持多种资源类型")
        void shouldSupportMultipleResourceTypes() {
            String[] types = {"video", "audio", "document", "image", "other"};
            for (String type : types) {
                Resource resource = TestDataBuilder.createDefaultResource();
                resource.setResourceType(type);
                when(courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(testCourse);
                when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

                Resource created = resourceService.createResource(resource);

                assertEquals(type, created.getResourceType());
            }
        }
    }
}
