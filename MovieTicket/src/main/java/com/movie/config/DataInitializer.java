package com.movie.config;

import com.movie.dto.CinemaCreateRequest;
import com.movie.dto.MovieCreateRequest;
import com.movie.dto.ScheduleCreateRequest;
import com.movie.service.CinemaService;
import com.movie.service.MovieService;
import com.movie.service.ScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

@Component
@Profile("!test")
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private MovieService movieService;

    @Autowired
    private CinemaService cinemaService;

    @Autowired
    private ScheduleService scheduleService;

    @Override
    public void run(String... args) {
        if (movieService.getAllMovies().isEmpty()) {
            initSampleData();
        }
    }

    private void initSampleData() {
        MovieCreateRequest movie1 = new MovieCreateRequest();
        movie1.setMovieName("流浪地球3");
        movie1.setMovieType("sci-fi");
        movie1.setMovieDuration(150);
        movie1.setMovieRating(9.2);
        movie1.setMovieStatus("showing");
        movie1.setMoviePoster("http://example.com/poster1.jpg");
        movie1.setReleaseDate(LocalDate.now().minusDays(10));
        movieService.createMovie(movie1);

        MovieCreateRequest movie2 = new MovieCreateRequest();
        movie2.setMovieName("速度与激情11");
        movie2.setMovieType("action");
        movie2.setMovieDuration(135);
        movie2.setMovieRating(8.5);
        movie2.setMovieStatus("showing");
        movie2.setMoviePoster("http://example.com/poster2.jpg");
        movie2.setReleaseDate(LocalDate.now().minusDays(5));
        movieService.createMovie(movie2);

        MovieCreateRequest movie3 = new MovieCreateRequest();
        movie3.setMovieName("哪吒之魔童闹海");
        movie3.setMovieType("animation");
        movie3.setMovieDuration(120);
        movie3.setMovieRating(9.5);
        movie3.setMovieStatus("showing");
        movie3.setMoviePoster("http://example.com/poster3.jpg");
        movie3.setReleaseDate(LocalDate.now().minusDays(3));
        movieService.createMovie(movie3);

        CinemaCreateRequest cinema1 = new CinemaCreateRequest();
        cinema1.setCinemaName("万达影城（朝阳店）");
        cinema1.setCinemaAddress("北京市朝阳区建国路88号");
        cinema1.setCinemaRegion("北京-朝阳区");
        cinema1.setCinemaStatus("active");
        cinema1.setCinemaRating(4.8);
        cinema1.setSeatTotal(150);
        cinemaService.createCinema(cinema1);

        CinemaCreateRequest cinema2 = new CinemaCreateRequest();
        cinema2.setCinemaName("UME国际影城（新天地店）");
        cinema2.setCinemaAddress("上海市黄浦区淮海中路333号");
        cinema2.setCinemaRegion("上海-黄浦区");
        cinema2.setCinemaStatus("active");
        cinema2.setCinemaRating(4.7);
        cinema2.setSeatTotal(120);
        cinemaService.createCinema(cinema2);

        CinemaCreateRequest cinema3 = new CinemaCreateRequest();
        cinema3.setCinemaName("金逸影城（天河店）");
        cinema3.setCinemaAddress("广州市天河区天河路385号");
        cinema3.setCinemaRegion("广州-天河区");
        cinema3.setCinemaStatus("active");
        cinema3.setCinemaRating(4.6);
        cinema3.setSeatTotal(100);
        cinemaService.createCinema(cinema3);

        try {
            ScheduleCreateRequest schedule1 = new ScheduleCreateRequest();
            schedule1.setMovieId("movie_001");
            schedule1.setCinemaId("cinema_001");
            schedule1.setScheduleDate(LocalDate.now());
            schedule1.setScheduleTime(LocalTime.of(14, 0));
            schedule1.setSchedulePrice(new BigDecimal("50.00"));
            schedule1.setScheduleSeats(100);
            scheduleService.createSchedule(schedule1);

            ScheduleCreateRequest schedule2 = new ScheduleCreateRequest();
            schedule2.setMovieId("movie_001");
            schedule2.setCinemaId("cinema_001");
            schedule2.setScheduleDate(LocalDate.now());
            schedule2.setScheduleTime(LocalTime.of(19, 30));
            schedule2.setSchedulePrice(new BigDecimal("60.00"));
            schedule2.setScheduleSeats(100);
            scheduleService.createSchedule(schedule2);

            ScheduleCreateRequest schedule3 = new ScheduleCreateRequest();
            schedule3.setMovieId("movie_002");
            schedule3.setCinemaId("cinema_001");
            schedule3.setScheduleDate(LocalDate.now());
            schedule3.setScheduleTime(LocalTime.of(16, 0));
            schedule3.setSchedulePrice(new BigDecimal("45.00"));
            schedule3.setScheduleSeats(100);
            scheduleService.createSchedule(schedule3);

            ScheduleCreateRequest schedule4 = new ScheduleCreateRequest();
            schedule4.setMovieId("movie_003");
            schedule4.setCinemaId("cinema_002");
            schedule4.setScheduleDate(LocalDate.now());
            schedule4.setScheduleTime(LocalTime.of(10, 30));
            schedule4.setSchedulePrice(new BigDecimal("40.00"));
            schedule4.setScheduleSeats(80);
            scheduleService.createSchedule(schedule4);

            ScheduleCreateRequest schedule5 = new ScheduleCreateRequest();
            schedule5.setMovieId("movie_003");
            schedule5.setCinemaId("cinema_003");
            schedule5.setScheduleDate(LocalDate.now());
            schedule5.setScheduleTime(LocalTime.of(20, 0));
            schedule5.setSchedulePrice(new BigDecimal("55.00"));
            schedule5.setScheduleSeats(80);
            scheduleService.createSchedule(schedule5);
        } catch (Exception e) {
            System.err.println("排片初始化警告: " + e.getMessage());
        }

        System.out.println("==============================================");
        System.out.println("MovieTicket 电影票务管理服务初始化完成");
        System.out.println("==============================================");
        System.out.println("API端点:");
        System.out.println("  排片查询: GET /api/v1/schedules/query?movie_id=movie_001");
        System.out.println("  座位查询: GET /api/v1/seats/query?schedule_id=schedule_001");
        System.out.println("  票务预订: POST /api/v1/tickets/create");
        System.out.println("H2控制台: http://localhost:8080/h2-console");
        System.out.println("==============================================");
    }
}
