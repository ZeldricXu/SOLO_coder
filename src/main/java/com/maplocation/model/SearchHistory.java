package com.maplocation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "search_history")
public class SearchHistory {
    @Id
    private String historyId;

    private String userId;

    private String searchType;

    private String keyword;

    private Coordinates centerLocation;

    private Double searchRadius;

    private List<String> resultLocationIds;

    private Instant searchedAt;
}
