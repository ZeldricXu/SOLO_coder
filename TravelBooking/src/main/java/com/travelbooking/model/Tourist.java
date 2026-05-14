package com.travelbooking.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data
@Entity
@Table(name = "tourists")
public class Tourist {
    @Id
    @Column(name = "tourist_id", length = 50)
    private String touristId;

    @Column(name = "tourist_name", nullable = false, length = 100)
    private String touristName;

    @Column(name = "tourist_phone", length = 50)
    private String touristPhone;

    @Column(name = "tourist_id_type", length = 50)
    private String touristIdType;

    @Column(name = "tourist_id_number", length = 100)
    private String touristIdNumber;

    @Column(name = "registered_at")
    private Instant registeredAt;
}
