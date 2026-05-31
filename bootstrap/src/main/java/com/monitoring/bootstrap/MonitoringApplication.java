package com.monitoring.bootstrap;

import com.monitoring.common.config.CommonAutoConfiguration;
import com.monitoring.config.config.ConfigAutoConfiguration;
import com.monitoring.dal.config.DalAutoConfiguration;
import com.monitoring.alert.config.AlertAutoConfiguration;
import com.monitoring.anomaly.config.AnomalyAutoConfiguration;
import com.monitoring.trace.config.TraceAutoConfiguration;
import com.monitoring.profiler.config.ProfilerAutoConfiguration;
import com.monitoring.metrics.config.MetricsAutoConfiguration;
import com.monitoring.logging.config.LoggingAutoConfiguration;
import com.monitoring.storage.config.StorageAutoConfiguration;
import com.monitoring.core.config.CoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
        CommonAutoConfiguration.class,
        ConfigAutoConfiguration.class,
        DalAutoConfiguration.class,
        AlertAutoConfiguration.class,
        AnomalyAutoConfiguration.class,
        TraceAutoConfiguration.class,
        ProfilerAutoConfiguration.class,
        MetricsAutoConfiguration.class,
        LoggingAutoConfiguration.class,
        StorageAutoConfiguration.class,
        CoreAutoConfiguration.class
})
public class MonitoringApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitoringApplication.class, args);
    }
}
