package com.cqrsengine.common.converter;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes({Object.class})
public class JsonTypeHandler extends JacksonTypeHandler {

    public JsonTypeHandler(Class<?> type) {
        super(type);
    }
}
