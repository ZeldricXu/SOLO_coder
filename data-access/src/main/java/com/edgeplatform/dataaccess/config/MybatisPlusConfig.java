package com.edgeplatform.dataaccess.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@MapperScan(basePackages = {
        "com.edgeplatform.dataaccess.mapper",
        "com.edgeplatform.config.mapper",
        "com.edgeplatform.device.mapper",
        "com.edgeplatform.shadow.mapper",
        "com.edgeplatform.storage.mapper",
        "com.edgeplatform.inference.mapper",
        "com.edgeplatform.rules.mapper",
        "com.edgeplatform.notification.mapper",
        "com.edgeplatform.monitoring.mapper",
        "com.edgeplatform.common.mapper"
})
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
