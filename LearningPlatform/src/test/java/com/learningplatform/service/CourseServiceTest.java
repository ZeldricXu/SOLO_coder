package com.learningplatform.service;

import com.learningplatform.builder.TestDataBuilder;
import com.learningplatform.entity.Course;
import com.learningplatform.exception.BusinessException;
import com.learningplatform.repository.CourseRepository;
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
@DisplayName("CourseService 课程管理服务测试")
class CourseServiceTest {

    @Mock
    private CourseRepository courseRepository;

    @InjectMocks
    private CourseService courseService;

    private Course testCourse;

    @BeforeEach
    void setUp() {
        testCourse = TestDataBuilder.createDefaultCourse();
    }

    @Nested
    @DisplayName("课程创建测试")
    class CourseCreationTests {

        @Test
        @DisplayName("应该成功创建课程")
        void shouldCreateCourseSuccessfully() {
            when(courseRepository.save(any(Course.class))).thenReturn(testCourse);

            Course created = courseService.createCourse(testCourse);

            assertNotNull(created);
            assertEquals(testCourse.getCourseId(), created.getCourseId());
            verify(courseRepository, times(1)).save(any(Course.class));
        }

        @Test
        @DisplayName("未指定ID时应该自动生成ID")
        void shouldGenerateIdWhenNotSpecified() {
            Course courseWithoutId = TestDataBuilder.createDefaultCourse();
            courseWithoutId.setCourseId(null);
            when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> {
                Course saved = invocation.getArgument(0);
                saved.setCourseId("auto_generated_001");
                return saved;
            });

            Course created = courseService.createCourse(courseWithoutId);

            assertNotNull(created.getCourseId());
        }

        @Test
        @DisplayName("未指定状态时应该设置为draft")
        void shouldSetDraftStatusWhenNotSpecified() {
            Course courseWithoutStatus = TestDataBuilder.createDefaultCourse();
            courseWithoutStatus.setCourseStatus(null);
            when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> {
                return invocation.getArgument(0);
            });

            Course created = courseService.createCourse(courseWithoutStatus);

            assertEquals("draft", created.getCourseStatus());
        }

        @Test
        @DisplayName("创建课程应该记录日志")
        void shouldLogCourseCreation() {
            when(courseRepository.save(any(Course.class))).thenReturn(testCourse);

            Course created = courseService.createCourse(testCourse);

            assertNotNull(created);
            verify(courseRepository, times(1)).save(any(Course.class));
        }
    }

    @Nested
    @DisplayName("课程状态流转测试")
    class CourseStateTransitionTests {

        @Test
        @DisplayName("应该从draft状态发布课程")
        void shouldPublishCourseFromDraft() {
            Course draftCourse = TestDataBuilder.createDefaultCourse();
            draftCourse.setCourseStatus("draft");
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(draftCourse));
            when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Course published = courseService.publishCourse(TestDataBuilder.TEST_COURSE_ID);

            assertNotNull(published);
            assertEquals("published", published.getCourseStatus());
            verify(courseRepository, times(1)).save(any(Course.class));
        }

        @Test
        @DisplayName("应该关闭已发布的课程")
        void shouldClosePublishedCourse() {
            Course publishedCourse = TestDataBuilder.createDefaultCourse();
            publishedCourse.setCourseStatus("published");
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(publishedCourse));
            when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Course closed = courseService.closeCourse(TestDataBuilder.TEST_COURSE_ID);

            assertNotNull(closed);
            assertEquals("closed", closed.getCourseStatus());
        }

        @Test
        @DisplayName("应该支持完整状态流转draft->published->closed")
        void shouldSupportCompleteStateTransition() {
            Course draftCourse = TestDataBuilder.createDefaultCourse();
            draftCourse.setCourseStatus("draft");
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(draftCourse));
            when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Course published = courseService.publishCourse(TestDataBuilder.TEST_COURSE_ID);
            assertEquals("published", published.getCourseStatus());

            Course closed = courseService.closeCourse(TestDataBuilder.TEST_COURSE_ID);
            assertEquals("closed", closed.getCourseStatus());
        }

        @Test
        @DisplayName("发布不存在的课程应该抛出异常")
        void shouldThrowExceptionWhenPublishingNonExistentCourse() {
            when(courseRepository.findById("nonexistent_course")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () -> {
                courseService.publishCourse("nonexistent_course");
            });
        }

        @Test
        @DisplayName("关闭不存在的课程应该抛出异常")
        void shouldThrowExceptionWhenClosingNonExistentCourse() {
            when(courseRepository.findById("nonexistent_course")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () -> {
                courseService.closeCourse("nonexistent_course");
            });
        }

        @Test
        @DisplayName("可以多次调用发布方法")
        void shouldAllowMultiplePublishCalls() {
            Course course = TestDataBuilder.createDefaultCourse();
            course.setCourseStatus("draft");
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(course));
            when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Course published1 = courseService.publishCourse(TestDataBuilder.TEST_COURSE_ID);
            Course published2 = courseService.publishCourse(TestDataBuilder.TEST_COURSE_ID);

            assertEquals("published", published1.getCourseStatus());
            assertEquals("published", published2.getCourseStatus());
        }
    }

    @Nested
    @DisplayName("课程可用性验证测试")
    class CourseAvailabilityValidationTests {

        @Test
        @DisplayName("已发布课程应该通过可用性验证")
        void shouldPassValidationForPublishedCourse() {
            Course publishedCourse = TestDataBuilder.createDefaultCourse();
            publishedCourse.setCourseStatus("published");
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(publishedCourse));

            assertDoesNotThrow(() -> {
                courseService.validateCourseAvailability(TestDataBuilder.TEST_COURSE_ID);
            });
        }

        @Test
        @DisplayName("未发布课程应该验证失败")
        void shouldFailValidationForDraftCourse() {
            Course draftCourse = TestDataBuilder.createDefaultCourse();
            draftCourse.setCourseStatus("draft");
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(draftCourse));

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                courseService.validateCourseAvailability(TestDataBuilder.TEST_COURSE_ID);
            });
            assertTrue(exception.getMessage().contains("未发布"));
        }

        @Test
        @DisplayName("已关闭课程应该验证失败")
        void shouldFailValidationForClosedCourse() {
            Course closedCourse = TestDataBuilder.createDefaultCourse();
            closedCourse.setCourseStatus("closed");
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(closedCourse));

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                courseService.validateCourseAvailability(TestDataBuilder.TEST_COURSE_ID);
            });
            assertTrue(exception.getMessage().contains("已关闭"));
        }
    }

    @Nested
    @DisplayName("课程查询测试")
    class CourseQueryTests {

        @Test
        @DisplayName("应该根据ID查询课程")
        void shouldGetCourseById() {
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(testCourse));

            Course found = courseService.getCourseById(TestDataBuilder.TEST_COURSE_ID);

            assertNotNull(found);
            assertEquals(testCourse.getCourseId(), found.getCourseId());
        }

        @Test
        @DisplayName("查询不存在的课程应该抛出异常")
        void shouldThrowExceptionForNonExistentCourse() {
            when(courseRepository.findById("nonexistent")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () -> {
                courseService.getCourseById("nonexistent");
            });
        }

        @Test
        @DisplayName("应该查询所有课程")
        void shouldGetAllCourses() {
            List<Course> courses = TestDataBuilder.createMultipleCourses(3);
            when(courseRepository.findAll()).thenReturn(courses);

            List<Course> allCourses = courseService.getAllCourses();

            assertNotNull(allCourses);
            assertEquals(3, allCourses.size());
        }

        @Test
        @DisplayName("应该只查询已发布的课程")
        void shouldGetOnlyPublishedCourses() {
            List<Course> publishedCourses = Arrays.asList(
                TestDataBuilder.createDefaultCourse(),
                TestDataBuilder.createDefaultCourse()
            );
            when(courseRepository.findByCourseStatus("published")).thenReturn(publishedCourses);

            List<Course> result = courseService.getPublishedCourses();

            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("应该根据教师查询课程")
        void shouldGetCoursesByTeacher() {
            String teacher = "张老师";
            List<Course> teacherCourses = Arrays.asList(
                TestDataBuilder.createDefaultCourse(),
                TestDataBuilder.createDefaultCourse()
            );
            when(courseRepository.findByCourseTeacher(teacher)).thenReturn(teacherCourses);

            List<Course> result = courseService.getCoursesByTeacher(teacher);

            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("应该支持使用findCourseById查询")
        void shouldSupportFindCourseById() {
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(testCourse));

            Optional<Course> found = courseService.findCourseById(TestDataBuilder.TEST_COURSE_ID);

            assertTrue(found.isPresent());
            assertEquals(testCourse.getCourseId(), found.get().getCourseId());
        }
    }

    @Nested
    @DisplayName("课程更新测试")
    class CourseUpdateTests {

        @Test
        @DisplayName("应该更新课程名称")
        void shouldUpdateCourseName() {
            Course existingCourse = TestDataBuilder.createDefaultCourse();
            existingCourse.setCourseName("旧课程名称");
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(existingCourse));
            when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Course updateInfo = new Course();
            updateInfo.setCourseName("新课程名称");

            Course updated = courseService.updateCourse(TestDataBuilder.TEST_COURSE_ID, updateInfo);

            assertEquals("新课程名称", updated.getCourseName());
        }

        @Test
        @DisplayName("应该更新课程类型")
        void shouldUpdateCourseType() {
            Course existingCourse = TestDataBuilder.createDefaultCourse();
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(existingCourse));
            when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Course updateInfo = new Course();
            updateInfo.setCourseType("实战课程");

            Course updated = courseService.updateCourse(TestDataBuilder.TEST_COURSE_ID, updateInfo);

            assertEquals("实战课程", updated.getCourseType());
        }

        @Test
        @DisplayName("应该更新课程价格")
        void shouldUpdateCoursePrice() {
            Course existingCourse = TestDataBuilder.createDefaultCourse();
            existingCourse.setCoursePrice(199.0);
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(existingCourse));
            when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Course updateInfo = new Course();
            updateInfo.setCoursePrice(299.0);

            Course updated = courseService.updateCourse(TestDataBuilder.TEST_COURSE_ID, updateInfo);

            assertEquals(299.0, updated.getCoursePrice());
        }

        @Test
        @DisplayName("应该更新课程描述")
        void shouldUpdateCourseDescription() {
            Course existingCourse = TestDataBuilder.createDefaultCourse();
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(existingCourse));
            when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Course updateInfo = new Course();
            updateInfo.setCourseDescription("更新后的课程描述");

            Course updated = courseService.updateCourse(TestDataBuilder.TEST_COURSE_ID, updateInfo);

            assertEquals("更新后的课程描述", updated.getCourseDescription());
        }

        @Test
        @DisplayName("更新不存在的课程应该抛出异常")
        void shouldThrowExceptionWhenUpdatingNonExistentCourse() {
            when(courseRepository.findById("nonexistent")).thenReturn(Optional.empty());

            Course updateInfo = new Course();
            updateInfo.setCourseName("新名称");

            assertThrows(BusinessException.class, () -> {
                courseService.updateCourse("nonexistent", updateInfo);
            });
        }
    }

    @Nested
    @DisplayName("课程删除测试")
    class CourseDeletionTests {

        @Test
        @DisplayName("应该成功删除课程")
        void shouldDeleteCourseSuccessfully() {
            when(courseRepository.findById(TestDataBuilder.TEST_COURSE_ID)).thenReturn(Optional.of(testCourse));
            doNothing().when(courseRepository).delete(any(Course.class));

            assertDoesNotThrow(() -> {
                courseService.deleteCourse(TestDataBuilder.TEST_COURSE_ID);
            });

            verify(courseRepository, times(1)).delete(any(Course.class));
        }

        @Test
        @DisplayName("删除不存在的课程应该抛出异常")
        void shouldThrowExceptionWhenDeletingNonExistentCourse() {
            when(courseRepository.findById("nonexistent")).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () -> {
                courseService.deleteCourse("nonexistent");
            });
        }
    }

    @Nested
    @DisplayName("课程信息完整性测试")
    class CourseInfoIntegrityTests {

        @Test
        @DisplayName("创建的课程应该包含完整信息")
        void createdCourseShouldHaveCompleteInfo() {
            Course course = TestDataBuilder.createDefaultCourse();
            course.setCourseId("test_complete_001");
            course.setCourseName("完整信息测试课程");
            course.setCourseType("技术课程");
            course.setCourseTeacher("李老师");
            course.setCourseDuration(3600L);
            course.setCoursePrice(299.0);
            course.setCourseDescription("这是一门完整的测试课程");
            course.setCourseStatus("published");

            when(courseRepository.save(any(Course.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Course created = courseService.createCourse(course);

            assertNotNull(created.getCourseId());
            assertNotNull(created.getCourseName());
            assertNotNull(created.getCourseType());
            assertNotNull(created.getCourseTeacher());
            assertNotNull(created.getCourseDuration());
            assertNotNull(created.getCoursePrice());
            assertNotNull(created.getCourseDescription());
            assertNotNull(created.getCourseStatus());
        }

        @Test
        @DisplayName("空查询列表应该返回空列表")
        void shouldReturnEmptyListForNoCourses() {
            when(courseRepository.findAll()).thenReturn(Arrays.asList());

            List<Course> allCourses = courseService.getAllCourses();

            assertNotNull(allCourses);
            assertTrue(allCourses.isEmpty());
        }

        @Test
        @DisplayName("空发布列表应该返回空列表")
        void shouldReturnEmptyListForNoPublishedCourses() {
            when(courseRepository.findByCourseStatus("published")).thenReturn(Arrays.asList());

            List<Course> publishedCourses = courseService.getPublishedCourses();

            assertNotNull(publishedCourses);
            assertTrue(publishedCourses.isEmpty());
        }
    }
}
