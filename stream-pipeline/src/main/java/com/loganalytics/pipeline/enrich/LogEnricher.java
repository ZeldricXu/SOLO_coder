package com.loganalytics.pipeline.enrich;

import com.loganalytics.common.model.GeoLocation;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.ServiceMetadata;
import com.loganalytics.common.model.TraceContext;
import com.loganalytics.pipeline.cmdb.CmdbService;
import com.loganalytics.pipeline.config.PipelineConfig;
import com.loganalytics.pipeline.geo.GeoIpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class LogEnricher {
    private static final Logger log = LoggerFactory.getLogger(LogEnricher.class);

    private final PipelineConfig config;
    private final CmdbService cmdbService;
    private final GeoIpService geoIpService;
    private final Map<String, TraceContext> traceCache;

    public LogEnricher(PipelineConfig config, CmdbService cmdbService, GeoIpService geoIpService) {
        this.config = config;
        this.cmdbService = cmdbService;
        this.geoIpService = geoIpService;
        this.traceCache = new HashMap<>();
    }

    public LogEvent enrich(LogEvent event) {
        enrichWithCmdb(event);
        enrichWithGeoIp(event);
        enrichWithTraceContext(event);
        enrichWithDerivedFields(event);
        return event;
    }

    private void enrichWithCmdb(LogEvent event) {
        String serviceName = event.getServiceName();
        if (serviceName == null) return;

        ServiceMetadata metadata = cmdbService.getServiceMetadata(serviceName);
        if (metadata != null) {
            event.addEnrichedData("team", metadata.getTeamName());
            event.addEnrichedData("tech_lead", metadata.getTechLead());
            event.addEnrichedData("on_call_email", metadata.getOnCallEmail());
            event.addEnrichedData("slack_channel", metadata.getSlackChannel());
            event.addEnrichedData("environment", metadata.getEnvironment());

            if (metadata.getLabels() != null) {
                for (Map.Entry<String, String> entry : metadata.getLabels().entrySet()) {
                    event.addTag("cmdb_" + entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private void enrichWithGeoIp(LogEvent event) {
        String sourceIp = event.getSourceIp();
        if (sourceIp == null) return;

        GeoLocation location = geoIpService.lookup(sourceIp);
        if (location != null) {
            event.addEnrichedData("geo_country", location.getCountry());
            event.addEnrichedData("geo_region", location.getRegion());
            event.addEnrichedData("geo_city", location.getCity());
            event.addEnrichedData("geo_lat", location.getLatitude());
            event.addEnrichedData("geo_lon", location.getLongitude());
            event.addEnrichedData("geo_isp", location.getIsp());

            if (location.getCountry() != null) {
                event.addTag("geo_country", location.getCountry());
            }
        }
    }

    private void enrichWithTraceContext(LogEvent event) {
        String traceId = event.getTraceId();
        if (traceId == null) return;

        event.addEnrichedData("trace_id", traceId);
        if (event.getSpanId() != null) {
            event.addEnrichedData("span_id", event.getSpanId());
        }

        TraceContext context = traceCache.get(traceId);
        if (context != null) {
            event.addEnrichedData("trace_duration_ms", context.getDurationMs());
            event.addEnrichedData("trace_has_error", context.isHasError());
            event.addEnrichedData("trace_span_count", context.getSpans().size());

            Map<String, Object> affectedServices = new HashMap<>();
            for (TraceContext.SpanInfo span : context.getSpans()) {
                if (!affectedServices.containsKey(span.getServiceName())) {
                    affectedServices.put(span.getServiceName(), true);
                }
            }
            event.addEnrichedData("trace_affected_services", affectedServices.keySet().size());
            event.addEnrichedData("trace_services", affectedServices.keySet());
        }
    }

    private void enrichWithDerivedFields(LogEvent event) {
        if (event.getLevel() != null) {
            event.addTag("level", event.getLevel().name());

            if (event.getLevel().isMoreSevereThan(com.loganalytics.common.model.LogLevel.WARN)) {
                event.addTag("is_error", "true");
                event.addEnrichedData("is_error", true);
            } else {
                event.addTag("is_error", "false");
                event.addEnrichedData("is_error", false);
            }
        }

        if (event.getServiceName() != null) {
            event.addTag("service", event.getServiceName());
        }

        if (event.getHostname() != null) {
            event.addTag("hostname", event.getHostname());
        }

        if (event.getMessage() != null) {
            String message = event.getMessage();
            int length = message.length();
            event.addEnrichedData("message_length", length);

            if (message.contains("Exception") || message.contains("Error:")) {
                event.addTag("has_stacktrace", "true");
                event.addEnrichedData("has_stacktrace", true);
            }

            if (message.contains("timeout")) {
                event.addTag("error_type", "timeout");
            } else if (message.contains("connection")) {
                event.addTag("error_type", "connection");
            } else if (message.contains("auth") || message.contains("unauthorized")) {
                event.addTag("error_type", "authentication");
            } else if (message.contains("404") || message.contains("not found")) {
                event.addTag("error_type", "not_found");
            }
        }
    }

    public void registerTraceContext(TraceContext context) {
        if (context != null && context.getTraceId() != null) {
            traceCache.put(context.getTraceId(), context);
        }
    }
}
