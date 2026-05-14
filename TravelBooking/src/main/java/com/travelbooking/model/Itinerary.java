package com.travelbooking.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "itineraries")
public class Itinerary {
    @Id
    @Column(name = "itinerary_id", length = 50)
    private String itineraryId;

    @Column(name = "booking_id", length = 50)
    private String bookingId;

    @Column(name = "route_id", length = 50)
    private String routeId;

    @Column(name = "guide_id", length = 50)
    private String guideId;

    @Column(name = "team_id", length = 50)
    private String teamId;

    @Column(name = "itinerary_status", length = 50)
    private String itineraryStatus;

    @Column(name = "itinerary_start")
    private LocalDate itineraryStart;

    @Column(name = "itinerary_end")
    private LocalDate itineraryEnd;
}
