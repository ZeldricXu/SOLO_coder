package com.maplocation.service;

import com.maplocation.dto.SearchRequest;
import com.maplocation.model.SearchHistory;
import com.maplocation.repository.SearchHistoryRepository;
import com.maplocation.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HistoryService {

    private final SearchHistoryRepository historyRepository;

    public void recordSearchHistory(SearchRequest request, List<String> resultLocationIds) {
        SearchHistory history = SearchHistory.builder()
                .historyId(IdGenerator.generateHistoryId())
                .userId("anonymous")
                .searchType(request.getSearchType())
                .keyword(request.getKeyword())
                .centerLocation(request.getCenterLocation())
                .searchRadius(request.getSearchRadius())
                .resultLocationIds(resultLocationIds)
                .searchedAt(Instant.now())
                .build();

        historyRepository.save(history);
    }

    public List<SearchHistory> getUserHistory(String userId) {
        return historyRepository.findByUserId(userId);
    }

    public Page<SearchHistory> getUserHistoryPaged(String userId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "searchedAt"));
        return historyRepository.findByUserId(userId, pageRequest);
    }

    public List<SearchHistory> getAllHistory() {
        return historyRepository.findAll();
    }
}
