package com.solocoder.platform.prompt.service;

import com.solocoder.platform.prompt.model.ExperimentComparison;
import com.solocoder.platform.prompt.model.ExperimentConfig;
import com.solocoder.platform.prompt.model.ExperimentResult;

import java.util.List;
import java.util.Optional;

public interface ExperimentService {

    ExperimentConfig createExperiment(ExperimentConfig config);

    Optional<ExperimentConfig> getExperiment(String experimentId);

    List<ExperimentConfig> listExperiments();

    ExperimentConfig startExperiment(String experimentId);

    ExperimentConfig pauseExperiment(String experimentId);

    ExperimentResult recordResult(ExperimentResult result);

    ExperimentComparison compareResults(String experimentId);
}
