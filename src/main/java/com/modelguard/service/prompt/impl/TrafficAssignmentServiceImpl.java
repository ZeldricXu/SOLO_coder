package com.modelguard.service.prompt.impl;

import com.modelguard.service.prompt.TrafficAssignmentService;
import com.modelguard.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrafficAssignmentServiceImpl implements TrafficAssignmentService {

    @Override
    public Mono<String> assignGroup(String userId, String experimentId, List<String> groups) {
        return Mono.fromCallable(() -> HashUtil.assignGroup(userId, experimentId, groups));
    }

    @Override
    public Mono<Boolean> isInTraffic(String userId, String experimentId, double trafficRatio) {
        return Mono.fromCallable(() -> HashUtil.isInTraffic(userId, experimentId, trafficRatio));
    }

    @Override
    public Mono<Integer> getGroupIndex(String userId, String experimentId, int groupCount) {
        return Mono.fromCallable(() -> HashUtil.assignGroup(userId, experimentId, groupCount));
    }

    @Override
    public Mono<String> assignDeterministicGroup(String userId, String salt, List<String> groups) {
        return Mono.fromCallable(() -> {
            int index = HashUtil.assignGroup(userId, salt, groups.size());
            return groups.get(index);
        });
    }

    @Override
    public Mono<Boolean> shouldIncludeUser(String userId, String salt, double percentage) {
        return Mono.fromCallable(() -> HashUtil.isInTraffic(userId, salt, percentage));
    }
}
