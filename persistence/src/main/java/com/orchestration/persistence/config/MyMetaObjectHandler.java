package com.orchestration.persistence.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.orchestration.common.context.TenantContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "deleted", Integer.class, 0);

        Long userId = TenantContext.getUserId();
        if (userId != null) {
            this.strictInsertFill(metaObject, "createdBy", String.class, userId.toString());
            this.strictInsertFill(metaObject, "updatedBy", String.class, userId.toString());
        }

        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null && metaObject.hasSetter("tenantId")) {
            this.strictInsertFill(metaObject, "tenantId", Long.class, tenantId);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        Long userId = TenantContext.getUserId();
        if (userId != null) {
            this.strictUpdateFill(metaObject, "updatedBy", String.class, userId.toString());
        }
    }
}
