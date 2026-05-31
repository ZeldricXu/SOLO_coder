package com.cdcsync.streamquery.service;

import com.cdcsync.common.service.BaseService;
import com.cdcsync.streamquery.domain.StreamQuery;

import java.util.Map;

public interface StreamQueryService extends BaseService<StreamQuery, String> {

    StreamQuery parseSql(String sql);

    StreamQuery optimizePlan(String id);

    StreamQuery generatePhysicalPlan(String id);

    Object executeQuery(String id, Map<String, Object> params);
}
