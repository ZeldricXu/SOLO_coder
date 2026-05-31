package com.taskflow.tenant.context;

import com.taskflow.common.model.Constants;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

public class TenantContext {

    private static final String TENANT_ID_KEY = "tenantId";
    private static final String USER_ID_KEY = "userId";

    public static Mono<String> getCurrentTenantId() {
        return Mono.deferContextual(ctx -> {
            if (ctx.hasKey(TENANT_ID_KEY)) {
                return Mono.just(ctx.get(TENANT_ID_KEY));
            }
            return Mono.just(Constants.DEFAULT_TENANT_ID);
        });
    }

    public static Mono<String> getCurrentUserId() {
        return Mono.deferContextual(ctx -> {
            if (ctx.hasKey(USER_ID_KEY)) {
                return Mono.just(ctx.get(USER_ID_KEY));
            }
            return Mono.empty();
        });
    }

    public static Context setTenantContext(String tenantId, String userId) {
        return Context.empty()
                .put(TENANT_ID_KEY, tenantId)
                .put(USER_ID_KEY, userId);
    }

    public static Context setTenantId(String tenantId) {
        return Context.of(TENANT_ID_KEY, tenantId);
    }
}
