package com.movie.service;

import com.movie.builder.TestDataBuilder;
import com.movie.dto.TicketCreateRequest;
import com.movie.dto.TicketCreateResponse;
import com.movie.entity.*;
import com.movie.exception.MovieException;
import com.movie.repository.TicketRepository;
import com.movie.util.JsonUtil;
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
import java.time.LocalDateTime;
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
@DisplayName("票务模块单元测试")
public class TicketServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private SeatService seatService;

    @Mock
    private MovieService movieService;

    @Mock
    private CinemaService cinemaService;

    @Mock
    private UserService userService;

    @Mock
    private HistoryService historyService;

    @Mock
    private AnalysisService analysisService;

    @InjectMocks
    private TicketService ticketService;

    private Schedule schedule;
    private Movie movie;
    private Cinema cinema;
    private User user;
    private List<Seat> seats;
    private TicketCreateRequest ticketRequest;
    private List<String> seatIds;

    @BeforeEach
    void setUp() {
        movie = TestDataBuilder.buildMovie();
        cinema = TestDataBuilder.buildCinema();
        schedule = TestDataBuilder.buildSchedule(movie.getMovieId(), cinema.getCinemaId());
        user = TestDataBuilder.buildUser();
        
        seatIds = Arrays.asList("seat_001", "seat_002", "seat_003");
        seats = TestDataBuilder.buildSeatsWithIds(schedule.getScheduleId(), seatIds);
        
        ticketRequest = TestDataBuilder.buildTicketCreateRequest(
                schedule.getScheduleId(), 
                user.getUserId(), 
                seatIds
        );
    }

    @AfterEach
    void tearDown() {
        ticketService.shutdown();
    }

    @Nested
    @DisplayName("支付超时机制测试")
    class PaymentTimeoutTests {

        @Test
        @DisplayName("验证普通用户支付超时时间 - 300秒")
        void testNormalUserPaymentTimeout() {
            int timeout = ticketService.getPaymentTimeoutSeconds(user);
            
            assertEquals(TicketService.PAYMENT_TIMEOUT_SECONDS, timeout);
            assertEquals(300, timeout);
        }

        @Test
        @DisplayName("验证VIP用户支付超时时间 - 600秒")
        void testVipUserPaymentTimeout() {
            User vipUser = TestDataBuilder.buildVipUser();
            int timeout = ticketService.getPaymentTimeoutSeconds(vipUser);
            
            assertEquals(TicketService.PAYMENT_TIMEOUT_SECONDS_VIP, timeout);
            assertEquals(600, timeout);
        }

        @Test
        @DisplayName("验证VIP用户有更长的支付时间")
        void testVipHasLongerPaymentTime() {
            User vipUser = TestDataBuilder.buildVipUser();
            int vipTimeout = ticketService.getPaymentTimeoutSeconds(vipUser);
            int normalTimeout = ticketService.getPaymentTimeoutSeconds(user);
            
            assertTrue(vipTimeout > normalTimeout, "VIP应该有更长的支付时间");
            assertEquals(300, vipTimeout - normalTimeout);
        }
    }

    @Nested
    @DisplayName("票务创建与支付场景测试")
    class TicketCreationTests {

        private void setupMockForTicketCreation() {
            when(scheduleService.getScheduleOrThrow(schedule.getScheduleId())).thenReturn(schedule);
            when(movieService.getMovieOrThrow(movie.getMovieId())).thenReturn(movie);
            when(cinemaService.getCinemaOrThrow(cinema.getCinemaId())).thenReturn(cinema);
            when(userService.getOrCreateUser(eq(user.getUserId()), isNull(), isNull())).thenReturn(user);
            when(seatService.getSeatsByIds(seatIds)).thenReturn(seats);
            when(seatService.calculateTotalPrice(seats)).thenReturn(new BigDecimal("150.00"));
            when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("验证支付成功场景 - 票务状态变为已确认")
        void testPaymentSuccessFlow() {
            setupMockForTicketCreation();

            TicketCreateResponse response = ticketService.createTicketWithPayment(ticketRequest, true);

            assertNotNull(response);
            assertEquals(TicketService.STATUS_CONFIRMED, response.getTicketStatus());
            assertEquals(new BigDecimal("150.00"), response.getTicketAmount());

            verify(seatService, times(1)).lockSeats(seatIds, user.getUserId());
            verify(seatService, times(1)).sellSeats(eq(seatIds), anyString());
            verify(scheduleService, times(1)).decreaseAvailableSeats(schedule.getScheduleId(), seatIds.size());
            verify(analysisService, times(1)).recordTicketSale(any(Ticket.class), eq(movie), eq(cinema));
        }

        @Test
        @DisplayName("验证支付失败场景 - 票务状态变为已取消")
        void testPaymentFailureFlow() {
            setupMockForTicketCreation();

            TicketCreateResponse response = ticketService.createTicketWithPayment(ticketRequest, false);

            assertNotNull(response);
            assertEquals(TicketService.STATUS_CANCELLED, response.getTicketStatus());

            verify(seatService, times(1)).lockSeats(seatIds, user.getUserId());
            verify(seatService, times(1)).releaseLock(seatIds);
            verify(seatService, never()).sellSeats(anyList(), anyString());
            verify(analysisService, never()).recordTicketSale(any(Ticket.class), any(Movie.class), any(Cinema.class));
        }

        @Test
        @DisplayName("验证支付失败后座位锁定被释放")
        void testPaymentFailureReleasesSeats() {
            setupMockForTicketCreation();

            ticketService.createTicketWithPayment(ticketRequest, false);

            verify(seatService, times(1)).releaseLock(seatIds);
        }

        @Test
        @DisplayName("验证场次ID不能为空")
        void testCreateTicketWithNullScheduleId() {
            TicketCreateRequest request = new TicketCreateRequest();
            request.setScheduleId(null);
            request.setSeatIds(seatIds);

            MovieException exception = assertThrows(MovieException.class,
                    () -> ticketService.createTicket(request));

            assertTrue(exception.getMessage().contains("场次ID不能为空"));
            assertEquals(400, exception.getCode());
        }

        @Test
        @DisplayName("验证座位不能为空")
        void testCreateTicketWithEmptySeats() {
            TicketCreateRequest request = new TicketCreateRequest();
            request.setScheduleId(schedule.getScheduleId());
            request.setSeatIds(Arrays.asList());

            MovieException exception = assertThrows(MovieException.class,
                    () -> ticketService.createTicket(request));

            assertTrue(exception.getMessage().contains("座位未选择"));
        }
    }

    @Nested
    @DisplayName("票务费用计算测试")
    class TicketPriceCalculationTests {

        @Test
        @DisplayName("验证单座位票务费用计算")
        void testSingleSeatTicketPrice() {
            Seat seat = TestDataBuilder.buildSeatWithPrice("s_001", 1, 1, new BigDecimal("50.00"));
            List<Seat> singleSeat = Arrays.asList(seat);
            
            when(seatService.calculateTotalPrice(singleSeat)).thenReturn(new BigDecimal("50.00"));

            BigDecimal total = ticketService.calculateTicketAmount(singleSeat);

            assertEquals(new BigDecimal("50.00"), total);
        }

        @Test
        @DisplayName("验证多座位票务费用计算")
        void testMultipleSeatsTicketPrice() {
            Seat seat1 = TestDataBuilder.buildSeatWithPrice("s_001", 1, 1, new BigDecimal("50.00"));
            Seat seat2 = TestDataBuilder.buildSeatWithPrice("s_002", 1, 2, new BigDecimal("60.00"));
            Seat seat3 = TestDataBuilder.buildSeatWithPrice("s_003", 1, 3, new BigDecimal("70.00"));
            List<Seat> threeSeats = Arrays.asList(seat1, seat2, seat3);
            
            when(seatService.calculateTotalPrice(threeSeats)).thenReturn(new BigDecimal("180.00"));

            BigDecimal total = ticketService.calculateTicketAmount(threeSeats);

            assertEquals(new BigDecimal("180.00"), total);
        }

        @Test
        @DisplayName("验证不同价格座位的总费用")
        void testMixedPriceSeats() {
            Seat vipSeat = TestDataBuilder.buildSeatWithPrice("s_001", 1, 1, new BigDecimal("80.00"));
            Seat normalSeat = TestDataBuilder.buildSeatWithPrice("s_002", 2, 1, new BigDecimal("50.00"));
            List<Seat> mixedSeats = Arrays.asList(vipSeat, normalSeat);
            
            when(seatService.calculateTotalPrice(mixedSeats)).thenReturn(new BigDecimal("130.00"));

            BigDecimal total = ticketService.calculateTicketAmount(mixedSeats);

            assertEquals(new BigDecimal("130.00"), total);
        }
    }

    @Nested
    @DisplayName("票务取消测试")
    class TicketCancellationTests {

        @Test
        @DisplayName("验证已确认票务可以取消")
        void testCancelConfirmedTicket() {
            Ticket ticket = TestDataBuilder.buildTicket();
            ticket.setTicketStatus(TicketService.STATUS_CONFIRMED);
            ticket.setSeatIdsJson(JsonUtil.toJson(seatIds));

            when(ticketRepository.findByTicketId(ticket.getTicketId())).thenReturn(Optional.of(ticket));
            when(ticketRepository.save(any(Ticket.class))).thenReturn(ticket);
            when(scheduleService.getScheduleById(ticket.getScheduleId())).thenReturn(Optional.of(schedule));
            when(movieService.getMovieById(movie.getMovieId())).thenReturn(Optional.of(movie));
            when(cinemaService.getCinemaById(cinema.getCinemaId())).thenReturn(Optional.of(cinema));

            Ticket result = ticketService.cancelTicket(ticket.getTicketId());

            assertEquals(TicketService.STATUS_CANCELLED, result.getTicketStatus());
            verify(seatService, times(1)).releaseSoldSeats(seatIds);
            verify(scheduleService, times(1)).increaseAvailableSeats(ticket.getScheduleId(), seatIds.size());
            verify(analysisService, times(1)).recordTicketRefund(eq(ticket), eq(movie), eq(cinema));
        }

        @Test
        @DisplayName("验证未确认票务不能取消")
        void testCancelPendingPaymentTicketThrowsException() {
            Ticket pendingTicket = TestDataBuilder.buildPendingPaymentTicket();

            when(ticketRepository.findByTicketId(pendingTicket.getTicketId())).thenReturn(Optional.of(pendingTicket));

            MovieException exception = assertThrows(MovieException.class,
                    () -> ticketService.cancelTicket(pendingTicket.getTicketId()));

            assertTrue(exception.getMessage().contains("只有已确认的票务可以取消"));
        }

        @Test
        @DisplayName("验证不存在的票务取消抛出异常")
        void testCancelNonExistentTicket() {
            String nonExistentId = "nonexistent_ticket_001";

            when(ticketRepository.findByTicketId(nonExistentId)).thenReturn(Optional.empty());

            MovieException exception = assertThrows(MovieException.class,
                    () -> ticketService.cancelTicket(nonExistentId));

            assertTrue(exception.getMessage().contains("票务不存在"));
            assertEquals(404, exception.getCode());
        }
    }

    @Nested
    @DisplayName("票务状态流转测试")
    class TicketStateTransitionTests {

        @Test
        @DisplayName("验证票务创建后初始状态为待支付")
        void testInitialTicketStatus() {
            setupMockForTicketCreation();

            AtomicReference<Ticket> capturedTicket = new AtomicReference<>();
            when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> {
                Ticket t = invocation.getArgument(0);
                if (capturedTicket.get() == null) {
                    capturedTicket.set(t);
                    Ticket copy = new Ticket();
                    copy.setTicketId(t.getTicketId());
                    copy.setScheduleId(t.getScheduleId());
                    copy.setUserId(t.getUserId());
                    copy.setSeatIds(t.getSeatIds());
                    copy.setSeatIdsJson(t.getSeatIdsJson());
                    copy.setTicketAmount(t.getTicketAmount());
                    copy.setTicketStatus(t.getTicketStatus());
                    copy.setTicketTime(t.getTicketTime());
                    return copy;
                }
                return invocation.getArgument(0);
            });

            ticketService.createTicketWithPayment(ticketRequest, true);

            assertNotNull(capturedTicket.get());
            assertEquals(TicketService.STATUS_CONFIRMED, capturedTicket.get().getTicketStatus());
        }

        @Test
        @DisplayName("验证待支付票务可以转为已取消")
        void testPendingPaymentToCancelled() {
            setupMockForTicketCreation();

            TicketCreateResponse response = ticketService.createTicketWithPayment(ticketRequest, false);

            assertEquals(TicketService.STATUS_CANCELLED, response.getTicketStatus());
        }

        @Test
        @DisplayName("验证已确认票务可以转为已取消")
        void testConfirmedToCancelled() {
            Ticket confirmedTicket = TestDataBuilder.buildTicket();
            confirmedTicket.setTicketStatus(TicketService.STATUS_CONFIRMED);
            confirmedTicket.setSeatIdsJson(JsonUtil.toJson(seatIds));

            when(ticketRepository.findByTicketId(confirmedTicket.getTicketId())).thenReturn(Optional.of(confirmedTicket));
            when(ticketRepository.save(any(Ticket.class))).thenReturn(confirmedTicket);
            when(scheduleService.getScheduleById(confirmedTicket.getScheduleId())).thenReturn(Optional.of(schedule));
            when(movieService.getMovieById(movie.getMovieId())).thenReturn(Optional.of(movie));
            when(cinemaService.getCinemaById(cinema.getCinemaId())).thenReturn(Optional.of(cinema));

            Ticket result = ticketService.cancelTicket(confirmedTicket.getTicketId());

            assertEquals(TicketService.STATUS_CANCELLED, result.getTicketStatus());
        }
    }

    @Nested
    @DisplayName("座位与排片关联验证测试")
    class SeatScheduleValidationTests {

        @Test
        @DisplayName("验证座位必须属于当前场次")
        void testSeatMustBelongToSchedule() {
            Seat wrongSeat = TestDataBuilder.buildSeat("wrong_schedule", 1, 1);
            List<Seat> wrongSeats = Arrays.asList(wrongSeat);
            List<String> wrongSeatIds = Arrays.asList(wrongSeat.getSeatId());

            when(scheduleService.getScheduleOrThrow(schedule.getScheduleId())).thenReturn(schedule);
            when(movieService.getMovieOrThrow(movie.getMovieId())).thenReturn(movie);
            when(cinemaService.getCinemaOrThrow(cinema.getCinemaId())).thenReturn(cinema);
            when(userService.getOrCreateUser(eq(user.getUserId()), isNull(), isNull())).thenReturn(user);
            when(seatService.getSeatsByIds(wrongSeatIds)).thenReturn(wrongSeats);

            TicketCreateRequest request = TestDataBuilder.buildTicketCreateRequest(
                    schedule.getScheduleId(), user.getUserId(), wrongSeatIds);

            MovieException exception = assertThrows(MovieException.class,
                    () -> ticketService.createTicket(request));

            assertTrue(exception.getMessage().contains("座位不属于当前场次"));
        }

        @Test
        @DisplayName("验证部分座位不存在时抛出异常")
        void testPartialSeatsNotFound() {
            List<String> partialSeatIds = Arrays.asList("seat_001", "nonexistent_seat");
            List<Seat> partialSeats = Arrays.asList(seats.get(0));

            when(scheduleService.getScheduleOrThrow(schedule.getScheduleId())).thenReturn(schedule);
            when(movieService.getMovieOrThrow(movie.getMovieId())).thenReturn(movie);
            when(cinemaService.getCinemaOrThrow(cinema.getCinemaId())).thenReturn(cinema);
            when(userService.getOrCreateUser(eq(user.getUserId()), isNull(), isNull())).thenReturn(user);
            when(seatService.getSeatsByIds(partialSeatIds)).thenReturn(partialSeats);

            TicketCreateRequest request = TestDataBuilder.buildTicketCreateRequest(
                    schedule.getScheduleId(), user.getUserId(), partialSeatIds);

            MovieException exception = assertThrows(MovieException.class,
                    () -> ticketService.createTicket(request));

            assertTrue(exception.getMessage().contains("部分座位不存在"));
        }
    }

    @Test
    @DisplayName("验证票务查询功能")
    void testGetTicketById() {
        Ticket ticket = TestDataBuilder.buildTicket();

        when(ticketRepository.findByTicketId(ticket.getTicketId())).thenReturn(Optional.of(ticket));

        Optional<Ticket> result = ticketService.getTicketById(ticket.getTicketId());

        assertTrue(result.isPresent());
        assertEquals(ticket.getTicketId(), result.get().getTicketId());
    }

    @Test
    @DisplayName("验证用户票务查询")
    void testGetTicketsByUser() {
        Ticket ticket1 = TestDataBuilder.buildTicket();
        Ticket ticket2 = TestDataBuilder.buildTicket();
        List<Ticket> userTickets = Arrays.asList(ticket1, ticket2);

        when(ticketRepository.findByUserId(user.getUserId())).thenReturn(userTickets);

        List<Ticket> result = ticketService.getTicketsByUser(user.getUserId());

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("验证票务存在性检查")
    void testTicketExists() {
        when(ticketRepository.existsByTicketId("existing_ticket")).thenReturn(true);
        when(ticketRepository.existsByTicketId("nonexistent_ticket")).thenReturn(false);

        assertTrue(ticketService.exists("existing_ticket"));
        assertFalse(ticketService.exists("nonexistent_ticket"));
    }
}
