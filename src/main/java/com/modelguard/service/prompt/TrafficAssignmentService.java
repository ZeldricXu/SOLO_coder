package com.modelguard.service.prompt;

import reactor.core.publisher.Mono;
import java.util.List;

public interface TrafficAssignmentService {

    Mono<String> assignGroup(String userId, String experimentId, List<String> groups);

    Mono<Boolean> isInTraffic(String userId, String experimentId, double trafficRatio);

    Mono<Integer> getGroupIndex(String userId, String experimentId, int groupCount);

    Mono<String> assignDeterministicGroup(String userId, String salt, List<String> groups);

    Mono<Boolean> shouldIncludeUser(String userId, String salt, double percentage);
}
