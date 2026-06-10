package com.loganalytics.common.serde;

import com.loganalytics.common.model.MetricPoint;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class MetricPointSerde implements Serde<MetricPoint> {

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
    }

    @Override
    public void close() {
    }

    @Override
    public Serializer<MetricPoint> serializer() {
        return new Serializer<MetricPoint>() {
            @Override
            public void configure(Map<String, ?> configs, boolean isKey) {
            }

            @Override
            public byte[] serialize(String topic, MetricPoint data) {
                if (data == null) {
                    return null;
                }
                return data.toJson().getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public Deserializer<MetricPoint> deserializer() {
        return new Deserializer<MetricPoint>() {
            @Override
            public void configure(Map<String, ?> configs, boolean isKey) {
            }

            @Override
            public MetricPoint deserialize(String topic, byte[] data) {
                if (data == null) {
                    return null;
                }
                String json = new String(data, StandardCharsets.UTF_8);
                return MetricPoint.fromJson(json);
            }

            @Override
            public void close() {
            }
        };
    }
}
