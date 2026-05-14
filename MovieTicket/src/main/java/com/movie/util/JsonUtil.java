package com.movie.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Collections;
import java.util.List;

public class JsonUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public static String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }

    public static List<String> fromJsonStringList(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
