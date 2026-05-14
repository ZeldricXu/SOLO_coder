package com.logistics.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class TrackInfo {

    private String status;
    private String location;
    private LocalDateTime time;
    private String detail;
}
