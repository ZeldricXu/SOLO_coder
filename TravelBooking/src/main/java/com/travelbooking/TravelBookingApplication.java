package com.travelbooking;

import com.travelbooking.config.BookingLockConfig;
import com.travelbooking.config.ItineraryReminderConfig;
import com.travelbooking.config.RouteTypeConfig;
import com.travelbooking.config.SettlementConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    BookingLockConfig.class,
    ItineraryReminderConfig.class,
    RouteTypeConfig.class,
    SettlementConfig.class
})
public class TravelBookingApplication {
    public static void main(String[] args) {
        SpringApplication.run(TravelBookingApplication.class, args);
    }
}
