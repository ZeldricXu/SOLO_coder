package com.meteorology.nwp.common;

public final class PhysicsConstants {
    public static final double EARTH_RADIUS = 6371000.0;
    public static final double EARTH_OMEGA = 7.2921159e-5;
    public static final double GRAVITY = 9.80665;
    public static final double GAS_CONSTANT_DRY_AIR = 287.058;
    public static final double GAS_CONSTANT_WATER_VAPOR = 461.5;
    public static final double RATIO_GAS_CONSTANTS = GAS_CONSTANT_DRY_AIR / GAS_CONSTANT_WATER_VAPOR;
    public static final double CP_DRY_AIR = 1005.0;
    public static final double CV_DRY_AIR = CP_DRY_AIR - GAS_CONSTANT_DRY_AIR;
    public static final double GAMMA_DRY = CP_DRY_AIR / CV_DRY_AIR;
    public static final double CP_WATER_VAPOR = 1870.0;
    public static final double CP_LIQUID_WATER = 4190.0;
    public static final double CP_ICE = 2106.0;
    public static final double LATENT_HEAT_VAPORIZATION = 2.501e6;
    public static final double LATENT_HEAT_FUSION = 3.337e5;
    public static final double LATENT_HEAT_SUBLIMATION = LATENT_HEAT_VAPORIZATION + LATENT_HEAT_FUSION;
    public static final double STEFAN_BOLTZMANN = 5.670374419e-8;
    public static final double SOLAR_CONSTANT = 1361.0;
    public static final double REFERENCE_PRESSURE = 100000.0;
    public static final double P0_EXPONENT = GAS_CONSTANT_DRY_AIR / CP_DRY_AIR;
    public static final double VON_KARMAN_CONSTANT = 0.40;
    public static final double MOLECULAR_WEIGHT_DRY_AIR = 28.964;
    public static final double MOLECULAR_WEIGHT_WATER = 18.015;
    public static final double ICE_DENSITY = 917.0;
    public static final double WATER_DENSITY = 1000.0;
    public static final double FREEZING_TEMP = 273.15;
    public static final double TRIPLE_POINT_TEMP = 273.16;
    public static final double TRIPLE_POINT_PRESSURE = 611.65;

    private PhysicsConstants() {}

    public static double virtualTemperatureCorrection(double qv, double qc, double qr) {
        return 1.0 / (1.0 - RATIO_GAS_CONSTANTS * (qv + qc + qr));
    }

    public static double saturationVaporPressure(double temperature) {
        double t = temperature - 273.15;
        return 611.2 * Math.exp(17.67 * t / (temperature - 29.65));
    }

    public static double saturationMixingRatio(double temperature, double pressure) {
        double es = saturationVaporPressure(temperature);
        return RATIO_GAS_CONSTANTS * es / Math.max(1.0, pressure - es);
    }

    public static double potentialTemperature(double temperature, double pressure) {
        return temperature * Math.pow(REFERENCE_PRESSURE / pressure, P0_EXPONENT);
    }

    public static double temperatureFromTheta(double theta, double pressure) {
        return theta * Math.pow(pressure / REFERENCE_PRESSURE, P0_EXPONENT);
    }

    public static double equivalentPotentialTemperature(double t, double p, double qv) {
        double ws = saturationMixingRatio(t, p);
        double r = Math.min(qv, ws);
        double Lv = LATENT_HEAT_VAPORIZATION - 2370.0 * (t - FREEZING_TEMP);
        return potentialTemperature(t, p) * Math.exp(Lv * r / (CP_DRY_AIR * t));
    }
}
