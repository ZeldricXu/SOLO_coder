package com.meteorology.nwp.common;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

public class NWPConfig {
    private static final Logger logger = LoggerFactory.getLogger(NWPConfig.class);
    private static volatile NWPConfig instance;
    private final Config config;

    private NWPConfig(String configFile) {
        if (configFile != null && !configFile.isEmpty()) {
            File file = new File(configFile);
            if (file.exists()) {
                this.config = ConfigFactory.parseFile(file).withFallback(ConfigFactory.load());
            } else {
                logger.warn("Config file not found: {}, using default", configFile);
                this.config = ConfigFactory.load();
            }
        } else {
            this.config = ConfigFactory.load();
        }
    }

    public static NWPConfig getInstance() {
        return getInstance(null);
    }

    public static NWPConfig getInstance(String configFile) {
        if (instance == null) {
            synchronized (NWPConfig.class) {
                if (instance == null) {
                    instance = new NWPConfig(configFile);
                }
            }
        }
        return instance;
    }

    public Config getGridConfig() { return config.getConfig("nwp.grid"); }
    public Config getDynamicsConfig() { return config.getConfig("nwp.dynamics"); }
    public Config getPhysicsConfig() { return config.getConfig("nwp.physics"); }
    public Config getAssimilationConfig() { return config.getConfig("nwp.assimilation"); }
    public Config getParallelConfig() { return config.getConfig("nwp.parallel"); }
    public Config getIOConfig() { return config.getConfig("nwp.io"); }
    public Config getStorageConfig() { return config.getConfig("nwp.storage"); }
    public Config getPostprocessConfig() { return config.getConfig("nwp.postprocess"); }
    public Config getOutputConfig() { return config.getConfig("nwp.output"); }
    public Config getRawConfig() { return config; }

    public int getNX() { return getGridConfig().getInt("nx"); }
    public int getNY() { return getGridConfig().getInt("ny"); }
    public int getNZ() { return getGridConfig().getInt("nz"); }
    public double getDX() { return getGridConfig().getDouble("dx"); }
    public double getDY() { return getGridConfig().getDouble("dy"); }
    public double getLatMin() { return getGridConfig().getDouble("lat-min"); }
    public double getLatMax() { return getGridConfig().getDouble("lat-max"); }
    public double getLonMin() { return getGridConfig().getDouble("lon-min"); }
    public double getLonMax() { return getGridConfig().getDouble("lon-max"); }
    public List<Double> getSigmaLevels() { return getGridConfig().getDoubleList("sigma-levels"); }
    public double getTimeStep() { return getDynamicsConfig().getDouble("time-step"); }
    public int getTotalSteps() { return getDynamicsConfig().getInt("total-steps"); }
    public int getSpectralTruncation() { return getDynamicsConfig().getInt("spectral-truncation"); }
    public double getDiffusionCoef() { return getDynamicsConfig().getDouble("diffusion-coef"); }
    public String getSparkMaster() { return getParallelConfig().getString("spark-master"); }
    public int getPartitionsX() { return getParallelConfig().getInt("partitions-x"); }
    public int getPartitionsY() { return getParallelConfig().getInt("partitions-y"); }
    public int getHaloWidth() { return getParallelConfig().getInt("halo-width"); }
    public int getCheckpointInterval() { return getParallelConfig().getInt("checkpoint-interval"); }
    public String getHDFSNamenode() { return getIOConfig().getConfig("hdfs").getString("namenode"); }
    public String getHDFSBasePath() { return getIOConfig().getConfig("hdfs").getString("base-path"); }
    public String getPostgresURL() { return getStorageConfig().getConfig("postgres").getString("url"); }
    public String getPostgresUser() { return getStorageConfig().getConfig("postgres").getString("user"); }
    public String getPostgresPassword() { return getStorageConfig().getConfig("postgres").getString("password"); }
    public String getKafkaBootstrap() { return getStorageConfig().getConfig("kafka").getString("bootstrap-servers"); }
    public String getKafkaTaskTopic() { return getStorageConfig().getConfig("kafka").getString("task-topic"); }
    public String getKafkaResultTopic() { return getStorageConfig().getConfig("kafka").getString("result-topic"); }
    public String getKafkaGroupId() { return getStorageConfig().getConfig("kafka").getString("group-id"); }
    public List<String> getOutputVariables() { return getOutputConfig().getStringList("variables"); }
    public List<Integer> getPressureLevels() { return getOutputConfig().getIntList("pressure-levels"); }
}
