package com.gamestats.realtime.sink;

import com.gamestats.realtime.config.FlinkConfig;
import com.gamestats.realtime.model.OnlineStats;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

public class InfluxDBSink extends RichSinkFunction<OnlineStats> {
    private static final Logger LOG = LoggerFactory.getLogger(InfluxDBSink.class);
    
    private final FlinkConfig config;
    private transient InfluxDBClient influxDBClient;
    private transient WriteApi writeApi;

    public InfluxDBSink(FlinkConfig config) {
        this.config = config;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        LOG.info("Opening InfluxDBSink with config: url={}, org={}, bucket={}",
                config.getInfluxDbUrl(), config.getInfluxDbOrg(), config.getInfluxDbBucket());
        
        influxDBClient = InfluxDBClientFactory.create(
                config.getInfluxDbUrl(),
                config.getInfluxDbToken().toCharArray(),
                config.getInfluxDbOrg(),
                config.getInfluxDbBucket()
        );
        
        writeApi = influxDBClient.getWriteApi();
        
        LOG.info("InfluxDBSink opened successfully");
    }

    @Override
    public void invoke(OnlineStats stats, Context context) throws Exception {
        try {
            LOG.debug("Writing online stats to InfluxDB: gameId={}, onlineCount={}",
                    stats.getGameId(), stats.getOnlineCount());

            Point point = Point.measurement("online_stats")
                    .addTag("game_id", stats.getGameId())
                    .addTag("stat_id", stats.getStatId())
                    .addField("online_count", stats.getOnlineCount())
                    .addField("peak_today", stats.getPeakToday())
                    .time(Instant.ofEpochMilli(stats.getSampleTime()));

            if (stats.getServerDistribution() != null) {
                for (Map.Entry<String, Integer> entry : stats.getServerDistribution().entrySet()) {
                    point.addField("server_" + entry.getKey(), entry.getValue());
                }
            }

            writeApi.writePoint(point);
            
            LOG.debug("Successfully wrote online stats to InfluxDB");
        } catch (Exception e) {
            LOG.error("Failed to write online stats to InfluxDB", e);
            throw e;
        }
    }

    @Override
    public void close() throws Exception {
        LOG.info("Closing InfluxDBSink");
        
        if (writeApi != null) {
            try {
                writeApi.close();
            } catch (Exception e) {
                LOG.warn("Error closing WriteApi", e);
            }
        }
        
        if (influxDBClient != null) {
            try {
                influxDBClient.close();
            } catch (Exception e) {
                LOG.warn("Error closing InfluxDBClient", e);
            }
        }
        
        LOG.info("InfluxDBSink closed");
    }
}
