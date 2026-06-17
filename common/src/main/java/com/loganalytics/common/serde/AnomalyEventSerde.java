package com.loganalytics.common.serde;

import com.loganalytics.common.model.AnomalyEvent;
import com.loganalytics.common.util.JsonUtils;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;

public class AnomalyEventSerde implements Serde<AnomalyEvent> {
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
    }

    @Override
    public void close() {
    }

    @Override
    public Serializer<AnomalyEvent> serializer() {
        return new AnomalyEventSerializer();
    }

    @Override
    public Deserializer<AnomalyEvent> deserializer() {
        return new AnomalyEventDeserializer();
    }

    public static class AnomalyEventSerializer implements Serializer<AnomalyEvent> {
        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
        }

        @Override
        public byte[] serialize(String topic, AnomalyEvent data) {
            if (data == null) return null;
            return JsonUtils.toJson(data).getBytes();
        }

        @Override
        public void close() {
        }
    }

    public static class AnomalyEventDeserializer implements Deserializer<AnomalyEvent> {
        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
        }

        @Override
        public AnomalyEvent deserialize(String topic, byte[] data) {
            if (data == null) return null;
            return JsonUtils.fromJson(new String(data), AnomalyEvent.class);
        }

        @Override
        public void close() {
        }
    }
}
