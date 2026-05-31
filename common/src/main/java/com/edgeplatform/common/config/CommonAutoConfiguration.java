package com.edgeplatform.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.edgeplatform.common.util.TimeUtils;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonAutoConfiguration {

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createdAt", TimeUtils::nowUtc, java.time.LocalDateTime.class);
                this.strictInsertFill(metaObject, "updatedAt", TimeUtils::nowUtc, java.time.LocalDateTime.class);
                this.strictInsertFill(metaObject, "deleted", () -> 0, Integer.class);
                this.strictInsertFill(metaObject, "createdBy", () -> "system", String.class);
                this.strictInsertFill(metaObject, "updatedBy", () -> "system", String.class);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", TimeUtils::nowUtc, java.time.LocalDateTime.class);
                this.strictUpdateFill(metaObject, "updatedBy", () -> "system", String.class);
            }
        };
    }
}
