package com.movie.builder;

import com.movie.dto.*;
import com.movie.entity.*;
import com.movie.service.SeatService;
import com.movie.service.TicketService;
import com.movie.util.IdGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestDataBuilder {

    public static final String DEFAULT_MOVIE_ID = "movie_test_001";
    public static final String DEFAULT_CINEMA_ID = "cinema_test_001";
    public static final String DEFAULT_SCHEDULE_ID = "schedule_test_001";
    public static final String DEFAULT_USER_ID = "user_test_001";
    public static final String DEFAULT_VIP_USER_ID = "user_vip_001";

    public static Movie buildMovie() {
        Movie movie = new Movie();
        movie.setMovieId(DEFAULT_MOVIE_ID);
        movie.setMovieName("流浪地球3");
        movie.setMovieType("sci-fi");
        movie.setMovieDuration(150);
        movie.setMovieRating(9.2);
        movie.setMovieStatus("showing");
        movie.setMoviePoster("http://test.com/poster.jpg");
        movie.setReleaseDate(LocalDate.now().minusDays(10));
        movie.setCreatedAt(LocalDateTime.now());
        movie.setScheduleCount(0);
        return movie;
    }

    public static Movie buildMovie(String movieId) {
        Movie movie = buildMovie();
        movie.setMovieId(movieId);
        return movie;
    }

    public static Movie buildMovie(String name, String type) {
        Movie movie = buildMovie();
        movie.setMovieName(name);
        movie.setMovieType(type);
        return movie;
    }

    public static Movie buildMovieWithId(String movieId, String name, String type) {
        Movie movie = buildMovie(name, type);
        movie.setMovieId(movieId);
        return movie;
    }

    public static Cinema buildCinema() {
        Cinema cinema = new Cinema();
        cinema.setCinemaId(DEFAULT_CINEMA_ID);
        cinema.setCinemaName("万达影城（朝阳店）");
        cinema.setCinemaAddress("北京市朝阳区建国路88号");
        cinema.setCinemaRegion("北京-朝阳区");
        cinema.setCinemaStatus("active");
        cinema.setCinemaRating(4.8);
        cinema.setSeatTotal(100);
        cinema.setScheduleCount(0);
        cinema.setCreatedAt(LocalDateTime.now());
        return cinema;
    }

    public static Cinema buildCinema(String cinemaId) {
        Cinema cinema = buildCinema();
        cinema.setCinemaId(cinemaId);
        return cinema;
    }

    public static Cinema buildCinemaWithCapacity(String cinemaId, int seatTotal) {
        Cinema cinema = buildCinema(cinemaId);
        cinema.setSeatTotal(seatTotal);
        return cinema;
    }

    public static Schedule buildSchedule() {
        return buildSchedule(DEFAULT_MOVIE_ID, DEFAULT_CINEMA_ID);
    }

    public static Schedule buildSchedule(String movieId, String cinemaId) {
        Schedule schedule = new Schedule();
        schedule.setScheduleId(DEFAULT_SCHEDULE_ID);
        schedule.setMovieId(movieId);
        schedule.setCinemaId(cinemaId);
        schedule.setScheduleDate(LocalDate.now());
        schedule.setScheduleTime(LocalTime.of(14, 0));
        schedule.setSchedulePrice(new BigDecimal("50.00"));
        schedule.setScheduleSeats(100);
        schedule.setScheduleAvailable(100);
        schedule.setScheduleStatus("available");
        schedule.setCreatedAt(LocalDateTime.now());
        return schedule;
    }

    public static Schedule buildSchedule(String scheduleId, String movieId, String cinemaId, LocalTime time, BigDecimal price) {
        Schedule schedule = buildSchedule(movieId, cinemaId);
        schedule.setScheduleId(scheduleId);
        schedule.setScheduleTime(time);
        schedule.setSchedulePrice(price);
        return schedule;
    }

    public static Schedule buildScheduleWithCapacity(String scheduleId, int totalSeats, int availableSeats) {
        Schedule schedule = buildSchedule();
        schedule.setScheduleId(scheduleId);
        schedule.setScheduleSeats(totalSeats);
        schedule.setScheduleAvailable(availableSeats);
        return schedule;
    }

    public static Schedule buildScheduleWithStatus(String status) {
        Schedule schedule = buildSchedule();
        schedule.setScheduleStatus(status);
        return schedule;
    }

    public static Seat buildSeat() {
        return buildSeat(DEFAULT_SCHEDULE_ID, 1, 1);
    }

    public static Seat buildSeat(String scheduleId, int row, int col) {
        Seat seat = new Seat();
        seat.setSeatId(IdGenerator.generateSeatId());
        seat.setScheduleId(scheduleId);
        seat.setSeatRow(row);
        seat.setSeatColumn(col);
        seat.setSeatNumber(row + "-" + col);
        seat.setSeatType("standard");
        seat.setSeatStatus(SeatService.STATUS_AVAILABLE);
        seat.setSeatPrice(new BigDecimal("50.00"));
        return seat;
    }

    public static Seat buildSeatWithId(String seatId, String scheduleId, int row, int col) {
        Seat seat = buildSeat(scheduleId, row, col);
        seat.setSeatId(seatId);
        return seat;
    }

    public static Seat buildSeatWithStatus(String status) {
        Seat seat = buildSeat();
        seat.setSeatStatus(status);
        return seat;
    }

    public static Seat buildSeatWithPrice(String scheduleId, int row, int col, BigDecimal price) {
        Seat seat = buildSeat(scheduleId, row, col);
        seat.setSeatPrice(price);
        return seat;
    }

    public static Seat buildSeatLocked(String scheduleId, int row, int col, String userId) {
        Seat seat = buildSeat(scheduleId, row, col);
        seat.setSeatStatus(SeatService.STATUS_LOCKED);
        seat.setLockUserId(userId);
        seat.setLockTime(LocalDateTime.now());
        return seat;
    }

    public static Seat buildSeatSold(String scheduleId, int row, int col, String ticketId) {
        Seat seat = buildSeat(scheduleId, row, col);
        seat.setSeatStatus(SeatService.STATUS_SOLD);
        seat.setTicketId(ticketId);
        return seat;
    }

    public static List<Seat> buildSeats(String scheduleId, int rowCount, int colCount) {
        List<Seat> seats = new ArrayList<>();
        for (int row = 1; row <= rowCount; row++) {
            for (int col = 1; col <= colCount; col++) {
                seats.add(buildSeat(scheduleId, row, col));
            }
        }
        return seats;
    }

    public static List<Seat> buildSeatsWithIds(String scheduleId, List<String> seatIds) {
        List<Seat> seats = new ArrayList<>();
        int row = 1;
        int col = 1;
        for (String seatId : seatIds) {
            Seat seat = buildSeatWithId(seatId, scheduleId, row, col);
            seats.add(seat);
            col++;
            if (col > 10) {
                col = 1;
                row++;
            }
        }
        return seats;
    }

    public static User buildUser() {
        User user = new User();
        user.setUserId(DEFAULT_USER_ID);
        user.setUserName("张三");
        user.setUserPhone("13800138000");
        user.setUserStatus("active");
        user.setUserLevel(SeatService.LEVEL_NORMAL);
        user.setRegisteredAt(LocalDateTime.now());
        return user;
    }

    public static User buildUser(String userId, String name, String phone) {
        User user = buildUser();
        user.setUserId(userId);
        user.setUserName(name);
        user.setUserPhone(phone);
        return user;
    }

    public static User buildVipUser() {
        User user = buildUser();
        user.setUserId(DEFAULT_VIP_USER_ID);
        user.setUserName("VIP会员");
        user.setUserLevel(SeatService.LEVEL_VIP);
        return user;
    }

    public static User buildVipUser(String userId) {
        User user = buildVipUser();
        user.setUserId(userId);
        return user;
    }

    public static User buildUserWithLevel(String level) {
        User user = buildUser();
        user.setUserLevel(level);
        return user;
    }

    public static Ticket buildTicket() {
        return buildTicket(DEFAULT_SCHEDULE_ID, DEFAULT_USER_ID, Arrays.asList("seat_001", "seat_002"));
    }

    public static Ticket buildTicket(String scheduleId, String userId, List<String> seatIds) {
        Ticket ticket = new Ticket();
        ticket.setTicketId(IdGenerator.generateTicketId());
        ticket.setScheduleId(scheduleId);
        ticket.setUserId(userId);
        ticket.setSeatIds(seatIds);
        ticket.setSeatIdsJson(Arrays.toString(seatIds.toArray()));
        ticket.setTicketAmount(new BigDecimal("100.00"));
        ticket.setTicketStatus(TicketService.STATUS_CONFIRMED);
        ticket.setTicketTime(LocalDateTime.now());
        ticket.setMovieName("测试电影");
        ticket.setCinemaName("测试影院");
        ticket.setScheduleDate(LocalDate.now());
        ticket.setScheduleTime(LocalTime.of(14, 0));
        return ticket;
    }

    public static Ticket buildTicketWithStatus(String status) {
        Ticket ticket = buildTicket();
        ticket.setTicketStatus(status);
        return ticket;
    }

    public static Ticket buildPendingPaymentTicket() {
        Ticket ticket = buildTicket();
        ticket.setTicketStatus(TicketService.STATUS_PENDING_PAYMENT);
        ticket.setConfirmedAt(null);
        return ticket;
    }

    public static MovieCreateRequest buildMovieCreateRequest() {
        MovieCreateRequest request = new MovieCreateRequest();
        request.setMovieName("新电影");
        request.setMovieType("comedy");
        request.setMovieDuration(110);
        request.setMovieRating(7.8);
        request.setMovieStatus("showing");
        request.setReleaseDate(LocalDate.now());
        return request;
    }

    public static MovieCreateRequest buildMovieCreateRequest(String name, String type) {
        MovieCreateRequest request = buildMovieCreateRequest();
        request.setMovieName(name);
        request.setMovieType(type);
        return request;
    }

    public static CinemaCreateRequest buildCinemaCreateRequest() {
        CinemaCreateRequest request = new CinemaCreateRequest();
        request.setCinemaName("新影院");
        request.setCinemaAddress("新地址");
        request.setCinemaRegion("新区域");
        request.setCinemaStatus("active");
        request.setCinemaRating(4.2);
        request.setSeatTotal(120);
        return request;
    }

    public static CinemaCreateRequest buildCinemaCreateRequest(String name, int seatTotal) {
        CinemaCreateRequest request = buildCinemaCreateRequest();
        request.setCinemaName(name);
        request.setSeatTotal(seatTotal);
        return request;
    }

    public static ScheduleCreateRequest buildScheduleCreateRequest() {
        return buildScheduleCreateRequest(DEFAULT_MOVIE_ID, DEFAULT_CINEMA_ID);
    }

    public static ScheduleCreateRequest buildScheduleCreateRequest(String movieId, String cinemaId) {
        ScheduleCreateRequest request = new ScheduleCreateRequest();
        request.setMovieId(movieId);
        request.setCinemaId(cinemaId);
        request.setScheduleDate(LocalDate.now());
        request.setScheduleTime(LocalTime.of(19, 30));
        request.setSchedulePrice(new BigDecimal("60.00"));
        request.setScheduleSeats(100);
        return request;
    }

    public static ScheduleCreateRequest buildScheduleCreateRequest(String movieId, String cinemaId, LocalTime time, BigDecimal price) {
        ScheduleCreateRequest request = buildScheduleCreateRequest(movieId, cinemaId);
        request.setScheduleTime(time);
        request.setSchedulePrice(price);
        return request;
    }

    public static TicketCreateRequest buildTicketCreateRequest() {
        return buildTicketCreateRequest(DEFAULT_SCHEDULE_ID, Arrays.asList("seat_001", "seat_002"));
    }

    public static TicketCreateRequest buildTicketCreateRequest(String scheduleId, List<String> seatIds) {
        TicketCreateRequest request = new TicketCreateRequest();
        request.setScheduleId(scheduleId);
        request.setUserId(DEFAULT_USER_ID);
        request.setSeatIds(seatIds);
        return request;
    }

    public static TicketCreateRequest buildTicketCreateRequest(String scheduleId, String userId, List<String> seatIds) {
        TicketCreateRequest request = buildTicketCreateRequest(scheduleId, seatIds);
        request.setUserId(userId);
        return request;
    }

    public static UserCreateRequest buildUserCreateRequest() {
        UserCreateRequest request = new UserCreateRequest();
        request.setUserName("新用户");
        request.setUserPhone("13900139000");
        return request;
    }

    public static UserCreateRequest buildUserCreateRequest(String name, String phone) {
        UserCreateRequest request = buildUserCreateRequest();
        request.setUserName(name);
        request.setUserPhone(phone);
        return request;
    }

    public static BoxOfficeStat buildBoxOfficeStat() {
        return buildBoxOfficeStat(DEFAULT_MOVIE_ID, DEFAULT_CINEMA_ID);
    }

    public static BoxOfficeStat buildBoxOfficeStat(String movieId, String cinemaId) {
        BoxOfficeStat stat = new BoxOfficeStat();
        stat.setStatId(IdGenerator.generateStatId());
        stat.setStatDate(LocalDate.now());
        stat.setMovieId(movieId);
        stat.setCinemaId(cinemaId);
        stat.setTicketCount(10);
        stat.setBoxOffice(new BigDecimal("500.00"));
        return stat;
    }

    public static BoxOfficeStat buildBoxOfficeStatWithValues(int ticketCount, BigDecimal boxOffice) {
        BoxOfficeStat stat = buildBoxOfficeStat();
        stat.setTicketCount(ticketCount);
        stat.setBoxOffice(boxOffice);
        return stat;
    }

    public static List<String> generateSeatIds(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add("seat_" + String.format("%03d", i));
        }
        return ids;
    }
}
