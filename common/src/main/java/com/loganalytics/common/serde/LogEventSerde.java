package com.loganalytics.common.serde;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.util.JsonUtils;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;

public class LogEventSerde implements Serde<LogEvent> {
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
    }

    @Override
    public void close() {
    }

    @Override
    public Serializer<LogEvent> serializer() {
        return new LogEventSerializer();
    }

    @Override
    public Deserializer<LogEvent> deserializer() {
        return new LogEventDeserializer();
    }

    public static class LogEventSerializer implements Serializer<LogEvent> {
        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
        }

        @Override
        public byte[] serialize(String topic, LogEvent data) {
            if (data == null) return null;
            return JsonUtils.toJson(data).getBytes();
        }

        @Override
        public void close() {
        }
    }

    public static class LogEventDeserializer implements Deserializer<LogEvent> {
        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
        }

        @Override
        public LogEvent deserialize(String topic, byte[] data) {
            if (data == null) return null;
            return JsonUtils.fromJson(new String(data), LogEvent.class);
        }

        @Override
        public void close() {
        }
    }
}
