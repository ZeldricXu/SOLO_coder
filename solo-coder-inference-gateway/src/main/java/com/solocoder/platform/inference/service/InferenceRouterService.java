package com.solocoder.platform.inference.service;

import com.solocoder.platform.inference.model.InferenceRequest;
import com.solocoder.platform.inference.model.InferenceResponse;

public interface InferenceRouterService {

    InferenceResponse route(InferenceRequest request);

    InferenceResponse routeWithFallback(InferenceRequest request);
}
