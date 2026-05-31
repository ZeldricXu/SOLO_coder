package com.solocoder.platform.inference.routing;

import com.solocoder.platform.inference.model.InferenceRequest;
import com.solocoder.platform.inference.model.InferenceResponse;

public interface FallbackStrategy {

    InferenceResponse executeWithFallback(InferenceRequest request);
}
