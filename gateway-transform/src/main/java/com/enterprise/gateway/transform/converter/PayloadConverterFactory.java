package com.enterprise.gateway.transform.converter;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PayloadConverterFactory {

    private final List<PayloadConverter> converters;

    public Optional<PayloadConverter> getConverter(MediaType from, MediaType to) {
        return converters.stream()
                .filter(converter -> converter.supports(from, to))
                .findFirst();
    }
}
