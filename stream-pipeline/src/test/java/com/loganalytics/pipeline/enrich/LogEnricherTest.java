package com.loganalytics.pipeline.enrich;

import com.loganalytics.common.model.GeoLocation;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.ServiceMetadata;
import com.loganalytics.common.model.TraceContext;
import com.loganalytics.pipeline.cmdb.CmdbService;
import com.loganalytics.pipeline.config.PipelineConfig;
import com.loganalytics.pipeline.geo.GeoIpService;
import com.loganalytics.test.builder.LogEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogEnricherTest {

    @Mock
    private CmdbService cmdbService;

    @Mock
    private GeoIpService geoIpService;

    private PipelineConfig config;
    private LogEnricher enricher;

    @BeforeEach
    void setUp() {
        config = new PipelineConfig();
        config.setCmdbCacheTtlMinutes(30);
        enricher = new LogEnricher(config, cmdbService, geoIpService);
    }

    @Test
    void shouldEnrichWithCmdbServiceMetadata() {
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName("payment-service");
        metadata.setTeamName("payment-team");
        metadata.setTechLead("john.doe@example.com");
        metadata.setOnCallEmail("payment-oncall@example.com");
        metadata.setSlackChannel("#payment-alerts");
        metadata.setEnvironment("production");
        metadata.setLabels(Map.of("tier", "core", "criticality", "high"));

        when(cmdbService.getServiceMetadata("payment-service")).thenReturn(metadata);

        LogEvent event = LogEventBuilder.aLogEvent()
                .withPaymentService()
                .build();

        LogEvent enriched = enricher.enrich(event);

        assertThat(enriched.getEnrichedData()).containsEntry("team", "payment-team");
        assertThat(enriched.getEnrichedData()).containsEntry("tech_lead", "john.doe@example.com");
        assertThat(enriched.getEnrichedData()).containsEntry("on_call_email", "payment-oncall@example.com");
        assertThat(enriched.getEnrichedData()).containsEntry("slack_channel", "#payment-alerts");
        assertThat(enriched.getEnrichedData()).containsEntry("environment", "production");
        assertThat(enriched.getTags()).containsEntry("cmdb_tier", "core");
        assertThat(enriched.getTags()).containsEntry("cmdb_criticality", "high");
    }

    @Test
    void shouldUseCachedCmdbDataWhenApiTimesOut() {
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName("payment-service");
        metadata.setTeamName("payment-team");
        metadata.setEnvironment("production");

        when(cmdbService.getServiceMetadata("payment-service"))
                .thenReturn(metadata)
                .thenThrow(new RuntimeException("CMDB API timeout"));

        LogEvent event1 = LogEventBuilder.aLogEvent().withPaymentService().build();
        enricher.enrich(event1);

        LogEvent event2 = LogEventBuilder.aLogEvent().withPaymentService().build();
        LogEvent enriched = enricher.enrich(event2);

        assertThat(enriched.getEnrichedData()).containsEntry("team", "payment-team");
        verify(cmdbService, times(2)).getServiceMetadata("payment-service");
    }

    @Test
    void shouldEnrichWithGeoIpLocation() {
        GeoLocation location = new GeoLocation();
        location.setCountry("CN");
        location.setRegion("Beijing");
        location.setCity("Beijing");
        location.setLatitude(39.9042);
        location.setLongitude(116.4074);
        location.setIsp("China Telecom");

        when(geoIpService.lookup("114.23.56.78")).thenReturn(location);

        LogEvent event = LogEventBuilder.aLogEvent()
                .withSourceIp("114.23.56.78")
                .build();

        LogEvent enriched = enricher.enrich(event);

        assertThat(enriched.getEnrichedData()).containsEntry("geo_country", "CN");
        assertThat(enriched.getEnrichedData()).containsEntry("geo_region", "Beijing");
        assertThat(enriched.getEnrichedData()).containsEntry("geo_city", "Beijing");
        assertThat(enriched.getEnrichedData()).containsEntry("geo_lat", 39.9042);
        assertThat(enriched.getEnrichedData()).containsEntry("geo_lon", 116.4074);
        assertThat(enriched.getEnrichedData()).containsEntry("geo_isp", "China Telecom");
        assertThat(enriched.getTags()).containsEntry("geo_country", "CN");
    }

    @Test
    void shouldNotFailWhenGeoIpLookupReturnsNull() {
        when(geoIpService.lookup(anyString())).thenReturn(null);

        LogEvent event = LogEventBuilder.aLogEvent()
                .withSourceIp("192.168.1.100")
                .build();

        LogEvent enriched = enricher.enrich(event);

        assertThat(enriched).isNotNull();
        assertThat(enriched.getEnrichedData()).doesNotContainKey("geo_country");
    }

    @Test
    void shouldEnrichWithTraceContext() {
        TraceContext context = new TraceContext();
        context.setTraceId("trace-abc123");
        context.setDurationMs(250);
        context.setHasError(true);
        context.setSpans(List.of(
                createSpan("trace-abc123", "span-1", "payment-service", 100),
                createSpan("trace-abc123", "span-2", "user-service", 150)
        ));

        enricher.registerTraceContext(context);

        LogEvent event = LogEventBuilder.aLogEvent()
                .withTraceId("trace-abc123")
                .withSpanId("span-1")
                .build();

        LogEvent enriched = enricher.enrich(event);

        assertThat(enriched.getEnrichedData()).containsEntry("trace_id", "trace-abc123");
        assertThat(enriched.getEnrichedData()).containsEntry("span_id", "span-1");
        assertThat(enriched.getEnrichedData()).containsEntry("trace_duration_ms", 250);
        assertThat(enriched.getEnrichedData()).containsEntry("trace_has_error", true);
        assertThat(enriched.getEnrichedData()).containsEntry("trace_span_count", 2);
        assertThat(enriched.getEnrichedData()).containsEntry("trace_affected_services", 2);
    }

    @Test
    void shouldEnrichWithDerivedFieldsForError() {
        LogEvent event = LogEventBuilder.aLogEvent()
                .withLevelError()
                .withMessage("Database connection timeout after 30s")
                .build();

        LogEvent enriched = enricher.enrich(event);

        assertThat(enriched.getTags()).containsEntry("is_error", "true");
        assertThat(enriched.getEnrichedData()).containsEntry("is_error", true);
        assertThat(enriched.getTags()).containsEntry("level", "ERROR");
        assertThat(enriched.getEnrichedData()).containsEntry("message_length", 42);
        assertThat(enriched.getTags()).containsEntry("error_type", "timeout");
    }

    @Test
    void shouldEnrichWithDerivedFieldsForInfo() {
        LogEvent event = LogEventBuilder.aLogEvent()
                .withLevelInfo()
                .withMessage("User login successful")
                .build();

        LogEvent enriched = enricher.enrich(event);

        assertThat(enriched.getTags()).containsEntry("is_error", "false");
        assertThat(enriched.getEnrichedData()).containsEntry("is_error", false);
        assertThat(enriched.getTags()).containsEntry("level", "INFO");
        assertThat(enriched.getEnrichedData()).containsEntry("message_length", 21);
    }

    @Test
    void shouldNotEnrichCmdbWhenServiceNameIsNull() {
        LogEvent event = LogEventBuilder.aLogEvent()
                .withServiceName(null)
                .build();

        enricher.enrich(event);

        verify(cmdbService, never()).getServiceMetadata(anyString());
    }

    @Test
    void shouldHandleNullEnrichmentSourcesGracefully() {
        LogEvent event = new LogEvent();
        event.setServiceName(null);
        event.setSourceIp(null);
        event.setTraceId(null);
        event.setLevel(null);
        event.setMessage(null);

        LogEvent enriched = enricher.enrich(event);

        assertThat(enriched).isNotNull();
        verify(cmdbService, never()).getServiceMetadata(anyString());
        verify(geoIpService, never()).lookup(anyString());
    }

    @Test
    void shouldTagErrorTypeForAuthenticationErrors() {
        LogEvent event = LogEventBuilder.aLogEvent()
                .withErrorMessage("User unauthorized: invalid token")
                .build();

        LogEvent enriched = enricher.enrich(event);

        assertThat(enriched.getTags()).containsEntry("error_type", "authentication");
    }

    @Test
    void shouldTagErrorTypeForNotFoundErrors() {
        LogEvent event = LogEventBuilder.aLogEvent()
                .withErrorMessage("Resource not found: /api/users/999 returned 404")
                .build();

        LogEvent enriched = enricher.enrich(event);

        assertThat(enriched.getTags()).containsEntry("error_type", "not_found");
    }

    @Test
    void shouldTagHasStacktraceForExceptionMessages() {
        LogEvent event = LogEventBuilder.aLogEvent()
                .withErrorMessage("java.lang.NullPointerException: Cannot invoke method")
 .build();

        LogEvent enriched = enricher.enrich(event);

        assertThat(enriched.getTags()).containsEntry("has_stacktrace", "true");
        assertThat(enriched.getEnrichedData()).containsEntry("has_stacktrace", true);
    }

    @Test
    void shouldNotCallCmdbMultipleTimesForSameService() {
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName("payment-service");
        metadata.setTeamName("payment-team");

        when(cmdbService.getServiceMetadata("payment-service")).thenReturn(metadata);

        for (int i = 0; i < 10; i++) {
            LogEvent event = LogEventBuilder.aLogEvent().withPaymentService().build();
            enricher.enrich(event);
        }

        verify(cmdbService, atLeastOnce()).getServiceMetadata("payment-service");
    }

    private TraceContext.SpanInfo createSpan(String traceId, String spanId, String service, long duration) {
        TraceContext.SpanInfo span = new TraceContext.SpanInfo();
        span.setTraceId(traceId);
        span.setSpanId(spanId);
        span.setServiceName(service);
        span.setDurationMs(duration);
        return span;
    }
}
