package com.loganalytics.pipeline.geo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.loganalytics.common.model.GeoLocation;
import com.loganalytics.common.util.JsonUtils;
import com.loganalytics.pipeline.config.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

public class GeoIpService {
    private static final Logger log = LoggerFactory.getLogger(GeoIpService.class);
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");

    private final PipelineConfig config;
    private final Cache<String, GeoLocation> geoCache;
    private final HttpClient httpClient;
    private final boolean useExternalApi;

    public GeoIpService(PipelineConfig config) {
        this.config = config;
        this.geoCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(24))
                .maximumSize(100000)
                .build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        this.useExternalApi = false;
    }

    public GeoLocation lookup(String ip) {
        if (ip == null || ip.isBlank() || !isValidIp(ip)) {
            return null;
        }

        if (isPrivateIp(ip)) {
            return buildPrivateIpLocation(ip);
        }

        try {
            return geoCache.get(ip, this::doLookup);
        } catch (Exception e) {
            log.debug("GeoIP lookup failed for {}", ip, e);
            return buildDefaultLocation(ip);
        }
    }

    private boolean isValidIp(String ip) {
        return IP_PATTERN.matcher(ip).matches();
    }

    private boolean isPrivateIp(String ip) {
        return ip.startsWith("10.")
                || ip.startsWith("172.16.")
                || ip.startsWith("172.17.")
                || ip.startsWith("172.18.")
                || ip.startsWith("172.19.")
                || ip.startsWith("172.2")
                || ip.startsWith("172.30.")
                || ip.startsWith("172.31.")
                || ip.startsWith("192.168.")
                || ip.startsWith("127.")
                || ip.startsWith("169.254.");
    }

    private GeoLocation doLookup(String ip) {
        if (!useExternalApi) {
            return buildDefaultLocation(ip);
        }

        try {
            String url = "http://ip-api.com/json/" + ip;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(500))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null) {
                var data = JsonUtils.fromJson(response.body(), java.util.Map.class);
                GeoLocation loc = new GeoLocation();
                loc.setIp(ip);
                loc.setCountry((String) data.get("country"));
                loc.setRegion((String) data.get("regionName"));
                loc.setCity((String) data.get("city"));
                loc.setLatitude(((Number) data.getOrDefault("lat", 0.0)).doubleValue());
                loc.setLongitude(((Number) data.getOrDefault("lon", 0.0)).doubleValue());
                loc.setTimezone((String) data.get("timezone"));
                loc.setIsp((String) data.get("isp"));
                loc.setAsn((String) data.get("as"));
                return loc;
            }
        } catch (Exception e) {
            log.debug("External GeoIP API failed", e);
        }
        return buildDefaultLocation(ip);
    }

    private GeoLocation buildPrivateIpLocation(String ip) {
        GeoLocation loc = new GeoLocation();
        loc.setIp(ip);
        loc.setCountry("Internal");
        loc.setRegion("Private Network");
        loc.setCity("Internal");
        loc.setIsp("Internal");
        return loc;
    }

    private GeoLocation buildDefaultLocation(String ip) {
        GeoLocation loc = new GeoLocation();
        loc.setIp(ip);
        loc.setCountry("Unknown");
        loc.setRegion("Unknown");
        loc.setCity("Unknown");
        return loc;
    }
}
