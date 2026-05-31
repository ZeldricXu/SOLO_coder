package com.iotplatform.gateway.strategy;

import cn.hutool.json.JSONObject;
import com.iotplatform.gateway.dto.ProtocolConvertRequest;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProtocolConverterFactory {

    private final Map<String, ProtocolConverter> converterMap = new ConcurrentHashMap<>();

    public ProtocolConverterFactory(List<ProtocolConverter> converters) {
        for (ProtocolConverter converter : converters) {
            String key = buildKey(converter.getSourceProtocol(), converter.getTargetProtocol());
            converterMap.put(key, converter);
        }
    }

    public ProtocolConverter getConverter(String sourceProtocol, String targetProtocol) {
        String key = buildKey(sourceProtocol, targetProtocol);
        return converterMap.get(key);
    }

    public String convert(ProtocolConvertRequest request) {
        String source = request.getSourceProtocol();
        String target = request.getTargetProtocol();

        if (source.equalsIgnoreCase(target)) {
            return request.getPayload();
        }

        ProtocolConverter converter = getConverter(source, target);
        if (converter != null) {
            return converter.convert(request);
        }

        return buildGenericConversion(request, source, target);
    }

    private String buildGenericConversion(ProtocolConvertRequest request, String source, String target) {
        JSONObject json = new JSONObject();
        json.set("sourceProtocol", source);
        json.set("targetProtocol", target);
        json.set("originalPayload", request.getPayload());
        json.set("convertedAt", System.currentTimeMillis());
        return json.toString();
    }

    private static String buildKey(String source, String target) {
        return source.toUpperCase() + "->" + target.toUpperCase();
    }
}
