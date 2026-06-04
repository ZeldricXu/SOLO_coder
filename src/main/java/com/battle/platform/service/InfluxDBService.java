package com.battle.platform.service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
public class InfluxDBService {

    private final InfluxDBClient influxDBClient;

    private String influxOrg;
    private String influxBucket;

    public InfluxDBService(InfluxDBClient influxDBClient,
                           @Value("${influxdb.org}") String influxOrg,
                           @Value("${influxdb.bucket}") String influxBucket) {
        this.influxDBClient = influxDBClient;
        this.influxOrg = influxOrg;
        this.influxBucket = influxBucket;
    }

    public void writeBattleEvent(String battleId, Long playerId, String eventType,
                                 double value, String serverId, String guildId) {
        try {
            WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();

            Point point = Point.measurement("battle_events")
                    .addTag("battle_id", battleId)
                    .addTag("player_id", playerId.toString())
                    .addTag("event_type", eventType)
                    .addTag("server_id", serverId)
                    .addField("value", value)
                    .time(Instant.now(), WritePrecision.MS);

            if (guildId != null) {
                point.addTag("guild_id", guildId);
            }

            writeApi.writePoint(influxBucket, influxOrg, point);

        } catch (Exception e) {
            log.error("Failed to write InfluxDB event: battle={} player={} type={}",
                    battleId, playerId, eventType, e);
        }
    }

    public void writeKillEvent(String battleId, Long killerId, Long victimId,
                               int skillId, String serverId) {
        try {
            WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();

            Point point = Point.measurement("kill_events")
                    .addTag("battle_id", battleId)
                    .addTag("killer_id", killerId.toString())
                    .addTag("victim_id", victimId.toString())
                    .addTag("server_id", serverId)
                    .addField("skill_id", skillId)
                    .time(Instant.now(), WritePrecision.MS);

            writeApi.writePoint(influxBucket, influxOrg, point);

        } catch (Exception e) {
            log.error("Failed to write kill event to InfluxDB", e);
        }
    }

    public void writePerformanceMetric(String metricName, double value, String... tags) {
        try {
            WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();

            Point point = Point.measurement("performance_metrics")
                    .addTag("metric", metricName)
                    .addField("value", value)
                    .time(Instant.now(), WritePrecision.MS);

            for (int i = 0; i < tags.length - 1; i += 2) {
                point.addTag(tags[i], tags[i + 1]);
            }

            writeApi.writePoint(influxBucket, influxOrg, point);

        } catch (Exception e) {
            log.error("Failed to write performance metric to InfluxDB", e);
        }
    }
}
