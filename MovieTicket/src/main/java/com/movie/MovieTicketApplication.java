package com.movie;

import com.movie.config.MovieTypeConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

@SpringBootApplication
@EnableConfigurationProperties(MovieTypeConfig.class)
public class MovieTicketApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(MovieTicketApplication.class, args);
    }
}
