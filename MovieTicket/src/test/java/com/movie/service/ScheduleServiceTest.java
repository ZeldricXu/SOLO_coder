package com.movie.service;

import com.movie.builder.TestDataBuilder;
import com.movie.dto.ScheduleCreateRequest;
import com.movie.entity.Cinema;
import com.movie.entity.Movie;
import com.movie.entity.Schedule;
import com.movie.exception.MovieException;
import com.movie.repository.ScheduleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("排片模块单元测试")
public class ScheduleServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private MovieService movieService;

    @Mock
    private CinemaService cinemaService;

    @Mock
    private SeatService seatService;

    @Mock
    private HistoryService historyService;

    @Mock
    private AnalysisService analysisService;

    @InjectMocks
    private ScheduleService scheduleService;

    private Movie movie;
    private Cinema cinema;
    private Schedule schedule;
    private ScheduleCreateRequest scheduleRequest;

    @BeforeEach
    void setUp() {
        movie = TestDataBuilder.buildMovie();
        cinema = TestDataBuilder.buildCinema();
        schedule = TestDataBuilder.buildSchedule(movie.getMovieId(), cinema.getCinemaId());
        scheduleRequest = TestDataBuilder.buildScheduleCreateRequest(
                movie.getMovieId(),
                cinema.getCinemaId(),
                LocalTime.of(19, 30),
                new BigDecimal("60.00")
        );
    }

    @AfterEach
    void tearDown() {
        scheduleService.shutdown();
    }

    @Nested
    @DisplayName("异步排片配置测试")
    class AsyncScheduleTests {

        @Test
        @DisplayName("验证异步排片立即返回不阻塞")
        void testAsyncScheduleReturnsImmediately() throws InterruptedException {
            String taskId = scheduleService.createScheduleAsync(scheduleRequest, null, null);
            
            assertNotNull(taskId);
            assertTrue(taskId.startsWith("async_"));
        }

        @Test
        @DisplayName("验证异步任务初始状态为pending")
        void testAsyncScheduleInitialStatus() {
            String taskId = scheduleService.createScheduleAsync(scheduleRequest, null, null);
            
            String status = scheduleService.getAsyncTaskStatus(taskId);
            assertTrue(status.equals(ScheduleService.STATUS_PENDING) || 
                      status.equals(ScheduleService.STATUS_CONFIGURING));
        }

        @Test
        @DisplayName("验证异步排片完成后回调被调用")
        void testAsyncScheduleCompleteCallback() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Schedule> resultSchedule = new AtomicReference<>();

            when(movieService.getMovieOrThrow(movie.getMovieId())).thenReturn(movie);
            when(cinemaService.getCinemaOrThrow(cinema.getCinemaId())).thenReturn(cinema);
            when(scheduleRepository.save(any(Schedule.class))).thenReturn(schedule);

            String taskId = scheduleService.createScheduleAsync(scheduleRequest, 
                    s -> {
                        resultSchedule.set(s);
                        latch.countDown();
                    }, 
                    null);

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            
            assertTrue(completed, "异步任务应该在5秒内完成");
            assertNotNull(resultSchedule.get());
        }

        @Test
        @DisplayName("验证异步排片失败时错误回调被调用")
        void testAsyncScheduleErrorCallback() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Exception> resultException = new AtomicReference<>();

            when(movieService.getMovieOrThrow(movie.getMovieId()))
                    .thenThrow(new MovieException(404, "电影不存在"));

            String taskId = scheduleService.createScheduleAsync(scheduleRequest,
                    null,
                    e -> {
                        resultException.set(e);
                        latch.countDown();
                    });

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            
            assertTrue(completed, "异步任务错误处理应该在5秒内完成");
            assertNotNull(resultException.get());
            assertTrue(resultException.get().getMessage().contains("电影不存在"));
        }

        @Test
        @DisplayName("验证后台Worker执行排片配置")
        void testBackgroundWorkerExecutesConfiguration() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);

            when(movieService.getMovieOrThrow(movie.getMovieId())).thenReturn(movie);
            when(cinemaService.getCinemaOrThrow(cinema.getCinemaId())).thenReturn(cinema);
            when(scheduleRepository.save(any(Schedule.class))).thenReturn(schedule);

            scheduleService.createScheduleAsync(scheduleRequest,
                    s -> {
                        verify(seatService, times(1)).initializeSeats(
                                anyString(), anyInt(), anyInt(), any(BigDecimal.class));
                        verify(movieService, times(1)).incrementScheduleCount(movie.getMovieId());
                        verify(cinemaService, times(1)).incrementScheduleCount(cinema.getCinemaId());
                        verify(historyService, times(1)).recordScheduleCreation(
                                any(Schedule.class), eq(movie), eq(cinema));
                        latch.countDown();
                    },
                    null);

            latch.await(5, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("验证异步排片状态流转: pending -> configuring -> ready")
        void testAsyncScheduleStateTransition() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);

            when(movieService.getMovieOrThrow(movie.getMovieId())).thenReturn(movie);
            when(cinemaService.getCinemaOrThrow(cinema.getCinemaId())).thenReturn(cinema);
            when(scheduleRepository.save(any(Schedule.class))).thenReturn(schedule);

            String taskId = scheduleService.createScheduleAsync(scheduleRequest,
                    s -> latch.countDown(),
                    null);

            String initialStatus = scheduleService.getAsyncTaskStatus(taskId);
            assertTrue(initialStatus.equals(ScheduleService.STATUS_PENDING) ||
                      initialStatus.equals(ScheduleService.STATUS_CONFIGURING));

            latch.await(5, TimeUnit.SECONDS);
            
            String finalStatus = scheduleService.getAsyncTaskStatus(taskId);
            assertEquals(ScheduleService.STATUS_READY, finalStatus);
        }
    }

    @Nested
    @DisplayName("同步排片配置测试")
    class SyncScheduleTests {

        @Test
        @DisplayName("验证排片创建成功")
        void testCreateScheduleSuccess() {
            when(movieService.getMovieOrThrow(movie.getMovieId())).thenReturn(movie);
            when(cinemaService.getCinemaOrThrow(cinema.getCinemaId())).thenReturn(cinema);
            when(scheduleRepository.save(any(Schedule.class))).thenReturn(schedule);

            Schedule result = scheduleService.createSchedule(scheduleRequest);

            assertNotNull(result);
            assertEquals(schedule.getScheduleId(), result.getScheduleId());
            verify(seatService, times(1)).initializeSeats(
                    anyString(), anyInt(), anyInt(), eq(scheduleRequest.getSchedulePrice()));
        }

        @Test
        @DisplayName("验证电影ID不能为空")
        void testCreateScheduleWithNullMovieId() {
            ScheduleCreateRequest invalidRequest = TestDataBuilder.buildScheduleCreateRequest();
            invalidRequest.setMovieId(null);

            MovieException exception = assertThrows(MovieException.class,
                    () -> scheduleService.createSchedule(invalidRequest));

            assertTrue(exception.getMessage().contains("电影ID和影院ID不能为空"));
        }

        @Test
        @DisplayName("验证影院ID不能为空")
        void testCreateScheduleWithNullCinemaId() {
            ScheduleCreateRequest invalidRequest = TestDataBuilder.buildScheduleCreateRequest();
            invalidRequest.setCinemaId(null);

            MovieException exception = assertThrows(MovieException.class,
                    () -> scheduleService.createSchedule(invalidRequest));

            assertTrue(exception.getMessage().contains("电影ID和影院ID不能为空"));
        }

        @Test
        @DisplayName("验证电影不存在时抛出异常")
        void testCreateScheduleWithNonExistentMovie() {
            when(movieService.getMovieOrThrow(movie.getMovieId()))
                    .thenThrow(new MovieException(404, "电影不存在"));

            MovieException exception = assertThrows(MovieException.class,
                    () -> scheduleService.createSchedule(scheduleRequest));

            assertEquals(404, exception.getCode());
            assertTrue(exception.getMessage().contains("电影不存在"));
        }

        @Test
        @DisplayName("验证影院不存在时抛出异常")
        void testCreateScheduleWithNonExistentCinema() {
            when(movieService.getMovieOrThrow(movie.getMovieId())).thenReturn(movie);
            when(cinemaService.getCinemaOrThrow(cinema.getCinemaId()))
                    .thenThrow(new MovieException(404, "影院不存在"));

            MovieException exception = assertThrows(MovieException.class,
                    () -> scheduleService.createSchedule(scheduleRequest));

            assertEquals(404, exception.getCode());
            assertTrue(exception.getMessage().contains("影院不存在"));
        }
    }

    @Nested
    @DisplayName("排片状态流转测试")
    class ScheduleStateTransitionTests {

        @Test
        @DisplayName("验证排片创建后状态为可用")
        void testCreatedScheduleStatusIsAvailable() {
            when(movieService.getMovieOrThrow(movie.getMovieId())).thenReturn(movie);
            when(cinemaService.getCinemaOrThrow(cinema.getCinemaId())).thenReturn(cinema);
            when(scheduleRepository.save(any(Schedule.class))).thenAnswer(invocation -> {
                Schedule s = invocation.getArgument(0);
                assertEquals(ScheduleService.STATUS_AVAILABLE, s.getScheduleStatus());
                return s;
            });

            scheduleService.createSchedule(scheduleRequest);
        }

        @Test
        @DisplayName("验证排片可以关闭")
        void testCloseSchedule() {
            when(scheduleRepository.findByScheduleId(schedule.getScheduleId()))
                    .thenReturn(Optional.of(schedule));
            when(scheduleRepository.save(any(Schedule.class))).thenReturn(schedule);

            scheduleService.closeSchedule(schedule.getScheduleId());

            assertEquals(ScheduleService.STATUS_CLOSED, schedule.getScheduleStatus());
        }

        @Test
        @DisplayName("验证排片座位数减少后状态变为满座")
        void testDecreaseAvailableSeatsToFull() {
            schedule.setScheduleAvailable(1);
            schedule.setScheduleSeats(100);
            when(scheduleRepository.findByScheduleId(schedule.getScheduleId()))
                    .thenReturn(Optional.of(schedule));
            when(scheduleRepository.save(any(Schedule.class))).thenReturn(schedule);

            scheduleService.decreaseAvailableSeats(schedule.getScheduleId(), 1);

            assertEquals(0, schedule.getScheduleAvailable());
            assertEquals(ScheduleService.STATUS_FULL, schedule.getScheduleStatus());
        }

        @Test
        @DisplayName("验证座位数增加后满座状态恢复为可用")
        void testIncreaseAvailableSeatsFromFull() {
            schedule.setScheduleAvailable(0);
            schedule.setScheduleSeats(100);
            schedule.setScheduleStatus(ScheduleService.STATUS_FULL);
            when(scheduleRepository.findByScheduleId(schedule.getScheduleId()))
                    .thenReturn(Optional.of(schedule));
            when(scheduleRepository.save(any(Schedule.class))).thenReturn(schedule);

            scheduleService.increaseAvailableSeats(schedule.getScheduleId(), 5);

            assertEquals(5, schedule.getScheduleAvailable());
            assertEquals(ScheduleService.STATUS_AVAILABLE, schedule.getScheduleStatus());
        }

        @Test
        @DisplayName("验证已关闭场次验证时抛出异常")
        void testValidateClosedScheduleThrowsException() {
            Schedule closedSchedule = TestDataBuilder.buildScheduleWithStatus(ScheduleService.STATUS_CLOSED);

            MovieException exception = assertThrows(MovieException.class,
                    () -> scheduleService.validateSchedule(closedSchedule));

            assertTrue(exception.getMessage().contains("场次已关闭"));
        }

        @Test
        @DisplayName("验证已满场次验证时抛出异常")
        void testValidateFullScheduleThrowsException() {
            Schedule fullSchedule = TestDataBuilder.buildScheduleWithStatus(ScheduleService.STATUS_FULL);

            MovieException exception = assertThrows(MovieException.class,
                    () -> scheduleService.validateSchedule(fullSchedule));

            assertTrue(exception.getMessage().contains("场次已满"));
        }

        @Test
        @DisplayName("验证可用座位为零时验证抛出异常")
        void testValidateZeroAvailableSeatsThrowsException() {
            Schedule zeroSeatSchedule = TestDataBuilder.buildSchedule();
            zeroSeatSchedule.setScheduleAvailable(0);
            zeroSeatSchedule.setScheduleStatus(ScheduleService.STATUS_AVAILABLE);

            MovieException exception = assertThrows(MovieException.class,
                    () -> scheduleService.validateSchedule(zeroSeatSchedule));

            assertTrue(exception.getMessage().contains("场次已满"));
        }

        @Test
        @DisplayName("验证可用场次验证通过")
        void testValidateAvailableSchedule() {
            schedule.setScheduleStatus(ScheduleService.STATUS_AVAILABLE);
            schedule.setScheduleAvailable(50);

            assertDoesNotThrow(() -> scheduleService.validateSchedule(schedule));
        }
    }

    @Nested
    @DisplayName("排片查询测试")
    class ScheduleQueryTests {

        @Test
        @DisplayName("验证排片查询需要电影ID")
        void testQuerySchedulesWithNullMovieId() {
            MovieException exception = assertThrows(MovieException.class,
                    () -> scheduleService.querySchedules(null, LocalDate.now()));

            assertTrue(exception.getMessage().contains("电影ID不能为空"));
        }

        @Test
        @DisplayName("验证电影不存在时查询抛出异常")
        void testQuerySchedulesWithNonExistentMovie() {
            when(movieService.exists("nonexistent_movie")).thenReturn(false);

            MovieException exception = assertThrows(MovieException.class,
                    () -> scheduleService.querySchedules("nonexistent_movie", LocalDate.now()));

            assertTrue(exception.getMessage().contains("电影不存在"));
        }

        @Test
        @DisplayName("验证按电影和日期查询排片")
        void testQuerySchedulesByMovieAndDate() {
            LocalDate queryDate = LocalDate.now();
            List<Schedule> schedules = Arrays.asList(schedule);

            when(movieService.exists(movie.getMovieId())).thenReturn(true);
            when(scheduleRepository.findByMovieIdAndScheduleDate(movie.getMovieId(), queryDate))
                    .thenReturn(schedules);
            when(cinemaService.getCinemaById(cinema.getCinemaId())).thenReturn(Optional.of(cinema));

            var result = scheduleService.querySchedules(movie.getMovieId(), queryDate);

            assertEquals(1, result.size());
            assertEquals(schedule.getScheduleId(), result.get(0).getScheduleId());
        }
    }

    @Test
    @DisplayName("验证排片更新")
    void testUpdateSchedule() {
        ScheduleCreateRequest updateRequest = new ScheduleCreateRequest();
        updateRequest.setSchedulePrice(new BigDecimal("70.00"));

        when(scheduleRepository.findByScheduleId(schedule.getScheduleId()))
                .thenReturn(Optional.of(schedule));
        when(scheduleRepository.save(any(Schedule.class))).thenReturn(schedule);

        Schedule result = scheduleService.updateSchedule(schedule.getScheduleId(), updateRequest);

        assertEquals(new BigDecimal("70.00"), result.getSchedulePrice());
    }

    @Test
    @DisplayName("验证排片删除")
    void testDeleteSchedule() {
        when(scheduleRepository.findByScheduleId(schedule.getScheduleId()))
                .thenReturn(Optional.of(schedule));

        scheduleService.deleteSchedule(schedule.getScheduleId());

        verify(scheduleRepository, times(1)).delete(schedule);
    }

    @Test
    @DisplayName("验证排片存在性检查")
    void testScheduleExists() {
        when(scheduleRepository.existsByScheduleId("existing_schedule")).thenReturn(true);
        when(scheduleRepository.existsByScheduleId("nonexistent_schedule")).thenReturn(false);

        assertTrue(scheduleService.exists("existing_schedule"));
        assertFalse(scheduleService.exists("nonexistent_schedule"));
    }
}
