package com.cdcsync.cdc.core;

import com.alibaba.fastjson2.JSON;
import com.cdcsync.cdc.domain.ChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EventSerializer {

    public String toJson(ChangeEvent event) {
        return JSON.toJSONString(event);
    }

    public ChangeEvent fromJson(String json) {
        return JSON.parseObject(json, ChangeEvent.class);
    }

    public byte[] toAvro(ChangeEvent event) {
        return JSON.toJSONBytes(event);
    }
}
