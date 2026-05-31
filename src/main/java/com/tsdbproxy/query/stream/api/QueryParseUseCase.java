package com.tsdbproxy.query.stream.api;

import com.tsdbproxy.query.stream.model.ParseResult;
import com.tsdbproxy.query.stream.model.QueryStatement;
import reactor.core.publisher.Mono;

public interface QueryParseUseCase {

    Mono<ParseResult> execute(QueryStatement statement);
}
