package com.apishield.security.keysharding.api;

import com.apishield.security.keysharding.domain.ShardSecret;

public interface ShareGenerator {
    ShardSecret generate(String secret, int threshold, int totalShares);
}
