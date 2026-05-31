package com.orchestration.common.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.orchestration.common.context.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class TenantLineHandler implements TenantLineHandler {

    private static final Set<String> IGNORE_TABLES = Set.of(
            "sys_tenant",
            "sys_user",
            "flyway_schema_history"
    );

    @Override
    public Expression getTenantId() {
        Long tenantId = TenantContext.getTenantId();
        return tenantId != null ? new LongValue(tenantId) : new LongValue(0);
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return true;
        }
        return IGNORE_TABLES.contains(tableName.toLowerCase());
    }
}
