package com.logistics.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class TrackQueryResponse {

    private List<TrackInfo> tracks;
}
