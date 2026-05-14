package com.movie.service;

import com.movie.builder.TestDataBuilder;
import com.movie.dto.MovieCreateRequest;
import com.movie.dto.MovieUpdateRequest;
import com.movie.entity.Movie;
import com.movie.exception.MovieException;
import com.movie.repository.MovieRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("电影管理模块单元测试")
public class MovieServiceTest {

    @Mock
    private MovieRepository movieRepository;

    @InjectMocks
    private MovieService movieService;

    private Movie movie;
    private MovieCreateRequest movieRequest;

    @BeforeEach
    void setUp() {
        movie = TestDataBuilder.buildMovie();
        movieRequest = TestDataBuilder.buildMovieCreateRequest();
    }

    @Nested
    @DisplayName("电影录入测试")
    class MovieCreationTests {

        @Test
        @DisplayName("验证电影录入成功")
        void testCreateMovieSuccess() {
            when(movieRepository.save(any(Movie.class))).thenReturn(movie);

            Movie result = movieService.createMovie(movieRequest);

            assertNotNull(result);
            assertEquals(movie.getMovieId(), result.getMovieId());
            verify(movieRepository, times(1)).save(any(Movie.class));
        }

        @Test
        @DisplayName("验证电影名称不能为空")
        void testCreateMovieWithNullName() {
            MovieCreateRequest invalidRequest = TestDataBuilder.buildMovieCreateRequest();
            invalidRequest.setMovieName(null);

            MovieException exception = assertThrows(MovieException.class,
                    () -> movieService.createMovie(invalidRequest));

            assertTrue(exception.getMessage().contains("电影名称不能为空"));
            assertEquals(400, exception.getCode());
        }

        @Test
        @DisplayName("验证电影类型不能为空")
        void testCreateMovieWithNullType() {
            MovieCreateRequest invalidRequest = TestDataBuilder.buildMovieCreateRequest();
            invalidRequest.setMovieType(null);

            MovieException exception = assertThrows(MovieException.class,
                    () -> movieService.createMovie(invalidRequest));

            assertTrue(exception.getMessage().contains("电影类型不能为空"));
        }

        @Test
        @DisplayName("验证电影时长必须为正整数")
        void testCreateMovieWithNegativeDuration() {
            MovieCreateRequest invalidRequest = TestDataBuilder.buildMovieCreateRequest();
            invalidRequest.setMovieDuration(-1);

            MovieException exception = assertThrows(MovieException.class,
                    () -> movieService.createMovie(invalidRequest));

            assertTrue(exception.getMessage().contains("电影时长必须为正整数"));
        }

        @Test
        @DisplayName("验证电影上映日期设置")
        void testCreateMovieWithReleaseDate() {
            LocalDate releaseDate = LocalDate.of(2024, 6, 15);
            MovieCreateRequest request = TestDataBuilder.buildMovieCreateRequest();
            request.setReleaseDate(releaseDate);

            when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> {
                Movie m = invocation.getArgument(0);
                assertEquals(releaseDate, m.getReleaseDate());
                return m;
            });

            movieService.createMovie(request);
        }

        @Test
        @DisplayName("验证电影排片数初始化为0")
        void testCreateMovieScheduleCountInitiallyZero() {
            when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> {
                Movie m = invocation.getArgument(0);
                assertEquals(0, m.getScheduleCount());
                return m;
            });

            movieService.createMovie(movieRequest);
        }
    }

    @Nested
    @DisplayName("电影查询测试")
    class MovieQueryTests {

        @Test
        @DisplayName("验证获取所有电影")
        void testGetAllMovies() {
            Movie movie2 = TestDataBuilder.buildMovie();
            List<Movie> movies = Arrays.asList(movie, movie2);

            when(movieRepository.findAll()).thenReturn(movies);

            List<Movie> result = movieService.getAllMovies();

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("验证根据ID获取电影")
        void testGetMovieById() {
            when(movieRepository.findByMovieId(movie.getMovieId())).thenReturn(Optional.of(movie));

            Optional<Movie> result = movieService.getMovieById(movie.getMovieId());

            assertTrue(result.isPresent());
            assertEquals(movie.getMovieId(), result.get().getMovieId());
        }

        @Test
        @DisplayName("验证获取或抛出异常")
        void testGetMovieOrThrow() {
            when(movieRepository.findByMovieId(movie.getMovieId())).thenReturn(Optional.of(movie));

            Movie result = movieService.getMovieOrThrow(movie.getMovieId());

            assertNotNull(result);
        }

        @Test
        @DisplayName("验证不存在的电影抛出异常")
        void testGetMovieOrThrowNotFound() {
            when(movieRepository.findByMovieId("nonexistent")).thenReturn(Optional.empty());

            MovieException exception = assertThrows(MovieException.class,
                    () -> movieService.getMovieOrThrow("nonexistent"));

            assertTrue(exception.getMessage().contains("电影不存在"));
            assertEquals(404, exception.getCode());
        }

        @Test
        @DisplayName("验证电影存在性检查")
        void testMovieExists() {
            when(movieRepository.existsByMovieId("existing_movie")).thenReturn(true);
            when(movieRepository.existsByMovieId("nonexistent_movie")).thenReturn(false);

            assertTrue(movieService.exists("existing_movie"));
            assertFalse(movieService.exists("nonexistent_movie"));
        }

        @Test
        @DisplayName("验证根据名称查询电影")
        void testGetMoviesByName() {
            List<Movie> movies = Arrays.asList(movie);

            when(movieRepository.findByMovieNameContaining(movie.getMovieName())).thenReturn(movies);

            List<Movie> result = movieService.getMoviesByName(movie.getMovieName());

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("验证根据类型查询电影")
        void testGetMoviesByType() {
            List<Movie> movies = Arrays.asList(movie);

            when(movieRepository.findByMovieType(movie.getMovieType())).thenReturn(movies);

            List<Movie> result = movieService.getMoviesByType(movie.getMovieType());

            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("电影更新与删除测试")
    class MovieModificationTests {

        @Test
        @DisplayName("验证电影名称更新")
        void testUpdateMovieName() {
            MovieUpdateRequest updateRequest = new MovieUpdateRequest();
            updateRequest.setMovieName("新电影名称");

            when(movieRepository.findByMovieId(movie.getMovieId())).thenReturn(Optional.of(movie));
            when(movieRepository.save(any(Movie.class))).thenReturn(movie);

            Movie result = movieService.updateMovie(movie.getMovieId(), updateRequest);

            assertEquals("新电影名称", result.getMovieName());
        }

        @Test
        @DisplayName("验证电影描述更新")
        void testUpdateMovieDescription() {
            MovieUpdateRequest updateRequest = new MovieUpdateRequest();
            updateRequest.setMovieDescription("新的电影描述");

            when(movieRepository.findByMovieId(movie.getMovieId())).thenReturn(Optional.of(movie));
            when(movieRepository.save(any(Movie.class))).thenReturn(movie);

            Movie result = movieService.updateMovie(movie.getMovieId(), updateRequest);

            assertEquals("新的电影描述", result.getMovieDescription());
        }

        @Test
        @DisplayName("验证电影时长更新")
        void testUpdateMovieDuration() {
            MovieUpdateRequest updateRequest = new MovieUpdateRequest();
            updateRequest.setMovieDuration(150);

            when(movieRepository.findByMovieId(movie.getMovieId())).thenReturn(Optional.of(movie));
            when(movieRepository.save(any(Movie.class))).thenReturn(movie);

            Movie result = movieService.updateMovie(movie.getMovieId(), updateRequest);

            assertEquals(150, result.getMovieDuration());
        }

        @Test
        @DisplayName("验证电影删除")
        void testDeleteMovie() {
            when(movieRepository.findByMovieId(movie.getMovieId())).thenReturn(Optional.of(movie));

            movieService.deleteMovie(movie.getMovieId());

            verify(movieRepository, times(1)).delete(movie);
        }

        @Test
        @DisplayName("验证删除不存在的电影抛出异常")
        void testDeleteNonExistentMovie() {
            when(movieRepository.findByMovieId("nonexistent")).thenReturn(Optional.empty());

            MovieException exception = assertThrows(MovieException.class,
                    () -> movieService.deleteMovie("nonexistent"));

            assertTrue(exception.getMessage().contains("电影不存在"));
        }
    }

    @Nested
    @DisplayName("电影排片数管理测试")
    class MovieScheduleCountTests {

        @Test
        @DisplayName("验证电影排片数增加")
        void testIncrementScheduleCount() {
            movie.setScheduleCount(3);
            when(movieRepository.findByMovieId(movie.getMovieId())).thenReturn(Optional.of(movie));
            when(movieRepository.save(any(Movie.class))).thenReturn(movie);

            movieService.incrementScheduleCount(movie.getMovieId());

            assertEquals(4, movie.getScheduleCount());
            verify(movieRepository, times(1)).save(movie);
        }

        @Test
        @DisplayName("验证电影排片数减少")
        void testDecrementScheduleCount() {
            movie.setScheduleCount(5);
            when(movieRepository.findByMovieId(movie.getMovieId())).thenReturn(Optional.of(movie));
            when(movieRepository.save(any(Movie.class))).thenReturn(movie);

            movieService.decrementScheduleCount(movie.getMovieId());

            assertEquals(4, movie.getScheduleCount());
            verify(movieRepository, times(1)).save(movie);
        }

        @Test
        @DisplayName("验证排片数不为负数")
        void testScheduleCountNotNegative() {
            movie.setScheduleCount(0);
            when(movieRepository.findByMovieId(movie.getMovieId())).thenReturn(Optional.of(movie));
            when(movieRepository.save(any(Movie.class))).thenReturn(movie);

            movieService.decrementScheduleCount(movie.getMovieId());

            assertEquals(0, movie.getScheduleCount());
        }
    }

    @Test
    @DisplayName("验证电影创建时间设置")
    void testMovieCreatedAt() {
        when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> {
            Movie m = invocation.getArgument(0);
            assertNotNull(m.getCreatedAt());
            return m;
        });

        movieService.createMovie(movieRequest);
    }

    @Test
    @DisplayName("验证电影状态默认值")
    void testMovieDefaultStatus() {
        when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> {
            Movie m = invocation.getArgument(0);
            assertEquals("active", m.getMovieStatus());
            return m;
        });

        movieService.createMovie(movieRequest);
    }
}
