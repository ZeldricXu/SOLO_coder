package com.gamestats.realtime.serialization;

import com.gamestats.realtime.model.GameEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class GameEventDeserializationSchema implements DeserializationSchema<GameEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(GameEventDeserializationSchema.class);
    private static final Gson gson = new GsonBuilder()
            .setFieldNamingStrategy(com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    @Override
    public GameEvent deserialize(byte[] message) throws IOException {
        try {
            String json = new String(message, StandardCharsets.UTF_8);
            LOG.debug("Deserializing event: {}", json);
            return gson.fromJson(json, GameEvent.class);
        } catch (Exception e) {
            LOG.error("Failed to deserialize game event", e);
            return null;
        }
    }

    @Override
    public boolean isEndOfStream(GameEvent nextElement) {
        return false;
    }

    @Override
    public TypeInformation<GameEvent> getProducedType() {
        return TypeInformation.of(GameEvent.class);
    }
}
