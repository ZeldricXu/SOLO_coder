package com.enterprise.gateway.transform.converter;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

public interface PayloadConverter {

    Mono<DataBuffer> convert(DataBuffer input, MediaType from, MediaType to);

    boolean supports(MediaType from, MediaType to);
}
