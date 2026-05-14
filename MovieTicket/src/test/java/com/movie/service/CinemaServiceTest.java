package com.movie.service;

import com.movie.builder.TestDataBuilder;
import com.movie.dto.CinemaCreateRequest;
import com.movie.dto.CinemaUpdateRequest;
import com.movie.entity.Cinema;
import com.movie.exception.MovieException;
import com.movie.repository.CinemaRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("影院管理模块单元测试")
public class CinemaServiceTest {

    @Mock
    private CinemaRepository cinemaRepository;

    @InjectMocks
    private CinemaService cinemaService;

    private Cinema cinema;
    private CinemaCreateRequest cinemaRequest;

    @BeforeEach
    void setUp() {
        cinema = TestDataBuilder.buildCinema();
        cinemaRequest = TestDataBuilder.buildCinemaCreateRequest();
    }

    @Nested
    @DisplayName("影院录入测试")
    class CinemaCreationTests {

        @Test
        @DisplayName("验证影院录入成功")
        void testCreateCinemaSuccess() {
            when(cinemaRepository.save(any(Cinema.class))).thenReturn(cinema);

            Cinema result = cinemaService.createCinema(cinemaRequest);

            assertNotNull(result);
            assertEquals(cinema.getCinemaId(), result.getCinemaId());
            verify(cinemaRepository, times(1)).save(any(Cinema.class));
        }

        @Test
        @DisplayName("验证影院名称不能为空")
        void testCreateCinemaWithNullName() {
            CinemaCreateRequest invalidRequest = TestDataBuilder.buildCinemaCreateRequest();
            invalidRequest.setCinemaName(null);

            MovieException exception = assertThrows(MovieException.class,
                    () -> cinemaService.createCinema(invalidRequest));

            assertTrue(exception.getMessage().contains("影院名称不能为空"));
            assertEquals(400, exception.getCode());
        }

        @Test
        @DisplayName("验证影院地址不能为空")
        void testCreateCinemaWithNullAddress() {
            CinemaCreateRequest invalidRequest = TestDataBuilder.buildCinemaCreateRequest();
            invalidRequest.setCinemaAddress(null);

            MovieException exception = assertThrows(MovieException.class,
                    () -> cinemaService.createCinema(invalidRequest));

            assertTrue(exception.getMessage().contains("影院地址不能为空"));
        }

        @Test
        @DisplayName("验证影院座位总数设置")
        void testCreateCinemaWithSeatTotal() {
            CinemaCreateRequest request = TestDataBuilder.buildCinemaCreateRequest();
            request.setSeatTotal(150);

            when(cinemaRepository.save(any(Cinema.class))).thenAnswer(invocation -> {
                Cinema c = invocation.getArgument(0);
                assertEquals(150, c.getSeatTotal());
                return c;
            });

            cinemaService.createCinema(request);
        }

        @Test
        @DisplayName("验证影院排片数初始化为0")
        void testCreateCinemaScheduleCountInitiallyZero() {
            when(cinemaRepository.save(any(Cinema.class))).thenAnswer(invocation -> {
                Cinema c = invocation.getArgument(0);
                assertEquals(0, c.getScheduleCount());
                return c;
            });

            cinemaService.createCinema(cinemaRequest);
        }
    }

    @Nested
    @DisplayName("场次容量管理测试")
    class CinemaCapacityTests {

        @Test
        @DisplayName("验证影院座位总数查询")
        void testGetCinemaSeatTotal() {
            when(cinemaRepository.findByCinemaId(cinema.getCinemaId())).thenReturn(Optional.of(cinema));

            Integer seatTotal = cinemaService.getSeatTotal(cinema.getCinemaId());

            assertEquals(TestDataBuilder.DEFAULT_SEAT_TOTAL, seatTotal);
        }

        @Test
        @DisplayName("验证不存在影院座位总数查询")
        void testGetSeatTotalForNonExistentCinema() {
            when(cinemaRepository.findByCinemaId("nonexistent")).thenReturn(Optional.empty());

            Integer seatTotal = cinemaService.getSeatTotal("nonexistent");

            assertNull(seatTotal);
        }

        @Test
        @DisplayName("验证影院排片数增加")
        void testIncrementScheduleCount() {
            cinema.setScheduleCount(2);
            when(cinemaRepository.findByCinemaId(cinema.getCinemaId())).thenReturn(Optional.of(cinema));
            when(cinemaRepository.save(any(Cinema.class))).thenReturn(cinema);

            cinemaService.incrementScheduleCount(cinema.getCinemaId());

            assertEquals(3, cinema.getScheduleCount());
            verify(cinemaRepository, times(1)).save(cinema);
        }

        @Test
        @DisplayName("验证影院排片数减少")
        void testDecrementScheduleCount() {
            cinema.setScheduleCount(5);
            when(cinemaRepository.findByCinemaId(cinema.getCinemaId())).thenReturn(Optional.of(cinema));
            when(cinemaRepository.save(any(Cinema.class))).thenReturn(cinema);

            cinemaService.decrementScheduleCount(cinema.getCinemaId());

            assertEquals(4, cinema.getScheduleCount());
            verify(cinemaRepository, times(1)).save(cinema);
        }

        @Test
        @DisplayName("验证排片数不为负数")
        void testScheduleCountNotNegative() {
            cinema.setScheduleCount(0);
            when(cinemaRepository.findByCinemaId(cinema.getCinemaId())).thenReturn(Optional.of(cinema));
            when(cinemaRepository.save(any(Cinema.class))).thenReturn(cinema);

            cinemaService.decrementScheduleCount(cinema.getCinemaId());

            assertEquals(0, cinema.getScheduleCount());
        }

        @Test
        @DisplayName("验证影院容量更新")
        void testUpdateCinemaCapacity() {
            CinemaUpdateRequest updateRequest = new CinemaUpdateRequest();
            updateRequest.setSeatTotal(200);

            when(cinemaRepository.findByCinemaId(cinema.getCinemaId())).thenReturn(Optional.of(cinema));
            when(cinemaRepository.save(any(Cinema.class))).thenReturn(cinema);

            Cinema result = cinemaService.updateCinema(cinema.getCinemaId(), updateRequest);

            assertEquals(200, result.getSeatTotal());
        }
    }

    @Nested
    @DisplayName("影院查询测试")
    class CinemaQueryTests {

        @Test
        @DisplayName("验证获取所有影院")
        void testGetAllCinemas() {
            Cinema cinema2 = TestDataBuilder.buildCinema();
            List<Cinema> cinemas = Arrays.asList(cinema, cinema2);

            when(cinemaRepository.findAll()).thenReturn(cinemas);

            List<Cinema> result = cinemaService.getAllCinemas();

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("验证根据ID获取影院")
        void testGetCinemaById() {
            when(cinemaRepository.findByCinemaId(cinema.getCinemaId())).thenReturn(Optional.of(cinema));

            Optional<Cinema> result = cinemaService.getCinemaById(cinema.getCinemaId());

            assertTrue(result.isPresent());
            assertEquals(cinema.getCinemaId(), result.get().getCinemaId());
        }

        @Test
        @DisplayName("验证获取或抛出异常")
        void testGetCinemaOrThrow() {
            when(cinemaRepository.findByCinemaId(cinema.getCinemaId())).thenReturn(Optional.of(cinema));

            Cinema result = cinemaService.getCinemaOrThrow(cinema.getCinemaId());

            assertNotNull(result);
        }

        @Test
        @DisplayName("验证不存在的影院抛出异常")
        void testGetCinemaOrThrowNotFound() {
            when(cinemaRepository.findByCinemaId("nonexistent")).thenReturn(Optional.empty());

            MovieException exception = assertThrows(MovieException.class,
                    () -> cinemaService.getCinemaOrThrow("nonexistent"));

            assertTrue(exception.getMessage().contains("影院不存在"));
            assertEquals(404, exception.getCode());
        }

        @Test
        @DisplayName("验证影院存在性检查")
        void testCinemaExists() {
            when(cinemaRepository.existsByCinemaId("existing_cinema")).thenReturn(true);
            when(cinemaRepository.existsByCinemaId("nonexistent_cinema")).thenReturn(false);

            assertTrue(cinemaService.exists("existing_cinema"));
            assertFalse(cinemaService.exists("nonexistent_cinema"));
        }

        @Test
        @DisplayName("验证根据名称查询影院")
        void testGetCinemasByName() {
            List<Cinema> cinemas = Arrays.asList(cinema);

            when(cinemaRepository.findByCinemaNameContaining(cinema.getCinemaName())).thenReturn(cinemas);

            List<Cinema> result = cinemaService.getCinemasByName(cinema.getCinemaName());

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("验证根据位置查询影院")
        void testGetCinemasByLocation() {
            List<Cinema> cinemas = Arrays.asList(cinema);

            when(cinemaRepository.findByCinemaAddressContaining(cinema.getCinemaAddress())).thenReturn(cinemas);

            List<Cinema> result = cinemaService.getCinemasByLocation(cinema.getCinemaAddress());

            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("影院更新与删除测试")
    class CinemaModificationTests {

        @Test
        @DisplayName("验证影院名称更新")
        void testUpdateCinemaName() {
            CinemaUpdateRequest updateRequest = new CinemaUpdateRequest();
            updateRequest.setCinemaName("新影院名称");

            when(cinemaRepository.findByCinemaId(cinema.getCinemaId())).thenReturn(Optional.of(cinema));
            when(cinemaRepository.save(any(Cinema.class))).thenReturn(cinema);

            Cinema result = cinemaService.updateCinema(cinema.getCinemaId(), updateRequest);

            assertEquals("新影院名称", result.getCinemaName());
        }

        @Test
        @DisplayName("验证影院地址更新")
        void testUpdateCinemaAddress() {
            CinemaUpdateRequest updateRequest = new CinemaUpdateRequest();
            updateRequest.setCinemaAddress("新地址");

            when(cinemaRepository.findByCinemaId(cinema.getCinemaId())).thenReturn(Optional.of(cinema));
            when(cinemaRepository.save(any(Cinema.class))).thenReturn(cinema);

            Cinema result = cinemaService.updateCinema(cinema.getCinemaId(), updateRequest);

            assertEquals("新地址", result.getCinemaAddress());
        }

        @Test
        @DisplayName("验证影院联系电话更新")
        void testUpdateCinemaPhone() {
            CinemaUpdateRequest updateRequest = new CinemaUpdateRequest();
            updateRequest.setCinemaPhone("13800138001");

            when(cinemaRepository.findByCinemaId(cinema.getCinemaId())).thenReturn(Optional.of(cinema));
            when(cinemaRepository.save(any(Cinema.class))).thenReturn(cinema);

            Cinema result = cinemaService.updateCinema(cinema.getCinemaId(), updateRequest);

            assertEquals("13800138001", result.getCinemaPhone());
        }

        @Test
        @DisplayName("验证影院删除")
        void testDeleteCinema() {
            when(cinemaRepository.findByCinemaId(cinema.getCinemaId())).thenReturn(Optional.of(cinema));

            cinemaService.deleteCinema(cinema.getCinemaId());

            verify(cinemaRepository, times(1)).delete(cinema);
        }

        @Test
        @DisplayName("验证删除不存在的影院抛出异常")
        void testDeleteNonExistentCinema() {
            when(cinemaRepository.findByCinemaId("nonexistent")).thenReturn(Optional.empty());

            MovieException exception = assertThrows(MovieException.class,
                    () -> cinemaService.deleteCinema("nonexistent"));

            assertTrue(exception.getMessage().contains("影院不存在"));
        }
    }

    @Nested
    @DisplayName("影院状态验证测试")
    class CinemaValidationTests {

        @Test
        @DisplayName("验证影院状态默认为active")
        void testCinemaDefaultStatus() {
            when(cinemaRepository.save(any(Cinema.class))).thenAnswer(invocation -> {
                Cinema c = invocation.getArgument(0);
                assertEquals("active", c.getCinemaStatus());
                return c;
            });

            cinemaService.createCinema(cinemaRequest);
        }

        @Test
        @DisplayName("验证影院状态可以更新")
        void testUpdateCinemaStatus() {
            CinemaUpdateRequest updateRequest = new CinemaUpdateRequest();
            updateRequest.setCinemaStatus("closed");

            when(cinemaRepository.findByCinemaId(cinema.getCinemaId())).thenReturn(Optional.of(cinema));
            when(cinemaRepository.save(any(Cinema.class))).thenReturn(cinema);

            Cinema result = cinemaService.updateCinema(cinema.getCinemaId(), updateRequest);

            assertEquals("closed", result.getCinemaStatus());
        }
    }

    @Test
    @DisplayName("验证影院创建时间设置")
    void testCinemaCreatedAt() {
        when(cinemaRepository.save(any(Cinema.class))).thenAnswer(invocation -> {
            Cinema c = invocation.getArgument(0);
            assertNotNull(c.getCreatedAt());
            return c;
        });

        cinemaService.createCinema(cinemaRequest);
    }
}
