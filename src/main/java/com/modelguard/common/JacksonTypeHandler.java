package com.modelguard.common;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.type.MappedTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;

@MappedTypes({Map.class, ObjectNode.class})
public class JacksonTypeHandler extends JacksonTypeHandler {

    private static final ObjectMapper OBJECT_MAPPER;

    static {
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.registerModule(new JavaTimeModule());
    }

    public JacksonTypeHandler(Class<?> type) {
        super(type);
    }

    @Override
    protected ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }
}
