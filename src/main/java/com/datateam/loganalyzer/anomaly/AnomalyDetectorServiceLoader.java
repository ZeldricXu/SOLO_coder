package com.datateam.loganalyzer.anomaly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AnomalyDetectorServiceLoader {

    private static final Logger logger = LoggerFactory.getLogger(AnomalyDetectorServiceLoader.class);

    private static AnomalyDetectorServiceLoader instance;

    private final ServiceLoader<AnomalyDetector> serviceLoader;
    private final Map<String, AnomalyDetector> detectorCache;
    private final Map<String, Class<? extends AnomalyDetector>> detectorClasses;
    private volatile boolean initialized = false;

    private AnomalyDetectorServiceLoader() {
        this.serviceLoader = ServiceLoader.load(AnomalyDetector.class);
        this.detectorCache = new ConcurrentHashMap<>();
        this.detectorClasses = new ConcurrentHashMap<>();
    }

    public static synchronized AnomalyDetectorServiceLoader getInstance() {
        if (instance == null) {
            instance = new AnomalyDetectorServiceLoader();
        }
        return instance;
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        logger.info("Loading AnomalyDetector implementations via ServiceLoader...");

        try {
            Iterator<AnomalyDetector> iterator = serviceLoader.iterator();
            while (iterator.hasNext()) {
                try {
                    AnomalyDetector detector = iterator.next();
                    String className = detector.getAlgorithmClassName();
                    String name = detector.getName();

                    detectorCache.put(className, detector);
                    detectorCache.put(name, detector);
                    detectorClasses.put(className, detector.getClass());
                    detectorClasses.put(name, detector.getClass());

                    logger.info("Loaded anomaly detector: {} ({})", name, className);
                } catch (ServiceConfigurationError e) {
                    logger.warn("Failed to load anomaly detector: {}", e.getMessage());
                } catch (Exception e) {
                    logger.warn("Error loading anomaly detector: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("ServiceLoader initialization failed, using built-in detectors: {}", e.getMessage());
        }

        registerBuiltinDetectors();

        initialized = true;
        logger.info("AnomalyDetectorServiceLoader initialized with {} detectors", detectorCache.size());
    }

    private void registerBuiltinDetectors() {
        if (!detectorCache.containsKey(ZScoreDetector.class.getName())) {
            ZScoreDetector zScore = new ZScoreDetector();
            detectorCache.put(zScore.getAlgorithmClassName(), zScore);
            detectorCache.put(zScore.getName(), zScore);
            detectorClasses.put(zScore.getAlgorithmClassName(), ZScoreDetector.class);
            detectorClasses.put(zScore.getName(), ZScoreDetector.class);
            logger.debug("Registered built-in detector: zscore");
        }

        if (!detectorCache.containsKey(MovingAverageDetector.class.getName())) {
            MovingAverageDetector ma = new MovingAverageDetector();
            detectorCache.put(ma.getAlgorithmClassName(), ma);
            detectorCache.put(ma.getName(), ma);
            detectorClasses.put(ma.getAlgorithmClassName(), MovingAverageDetector.class);
            detectorClasses.put(ma.getName(), MovingAverageDetector.class);
            logger.debug("Registered built-in detector: moving-average");
        }
    }

    public AnomalyDetector getDetector(String nameOrClassName) {
        if (!initialized) {
            initialize();
        }

        AnomalyDetector cached = detectorCache.get(nameOrClassName);
        if (cached != null) {
            try {
                return cached.getClass().getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                logger.warn("Failed to create new instance of {}, returning cached: {}", nameOrClassName, e.getMessage());
                return cached;
            }
        }

        try {
            Class<?> clazz = Class.forName(nameOrClassName);
            if (AnomalyDetector.class.isAssignableFrom(clazz)) {
                @SuppressWarnings("unchecked")
                Class<? extends AnomalyDetector> detectorClass = (Class<? extends AnomalyDetector>) clazz;
                AnomalyDetector detector = detectorClass.getDeclaredConstructor().newInstance();
                detectorCache.put(nameOrClassName, detector);
                detectorClasses.put(nameOrClassName, detectorClass);
                logger.info("Loaded detector from class: {}", nameOrClassName);
                return detector;
            }
        } catch (ClassNotFoundException e) {
            logger.warn("Detector class not found: {}", nameOrClassName);
        } catch (Exception e) {
            logger.warn("Failed to instantiate detector {}: {}", nameOrClassName, e.getMessage());
        }

        return null;
    }

    public AnomalyDetector getDetector(String nameOrClassName, Map<String, Object> config) {
        AnomalyDetector detector = getDetector(nameOrClassName);
        if (detector != null && config != null && !config.isEmpty()) {
            detector.configure(config);
        }
        return detector;
    }

    public List<String> getAvailableDetectors() {
        if (!initialized) {
            initialize();
        }
        return new ArrayList<>(detectorClasses.keySet());
    }

    public boolean hasDetector(String nameOrClassName) {
        if (!initialized) {
            initialize();
        }
        return detectorCache.containsKey(nameOrClassName) || detectorClasses.containsKey(nameOrClassName);
    }

    public void registerDetector(AnomalyDetector detector) {
        if (detector == null) return;
        if (!initialized) {
            initialize();
        }
        detectorCache.put(detector.getAlgorithmClassName(), detector);
        detectorCache.put(detector.getName(), detector);
        detectorClasses.put(detector.getAlgorithmClassName(), detector.getClass());
        detectorClasses.put(detector.getName(), detector.getClass());
        logger.info("Registered detector: {} ({})", detector.getName(), detector.getAlgorithmClassName());
    }

    public void reload() {
        detectorCache.clear();
        detectorClasses.clear();
        initialized = false;
        serviceLoader.reload();
        initialize();
        logger.info("AnomalyDetectorServiceLoader reloaded");
    }

    public synchronized void clear() {
        detectorCache.clear();
        detectorClasses.clear();
        initialized = false;
        instance = null;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
