package com.meteorology.nwp.physics;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

public class RRTMGRadiation implements PhysicsScheme {
    private static final Logger logger = LoggerFactory.getLogger(RRTMGRadiation.class);
    private static final int NW_LW = 16;
    private static final int NW_SW = 14;
    private NWPConfig config;
    private int nz, nx, ny;
    private double[] pressureLevels;
    private double[] sigmaLevels;
    private double solarConstant;
    private double[] lwBandCenter;
    private double[] swBandCenter;
    private double[][] lwKCoefH2O, lwKCoefCO2, lwKCoefO3;
    private double[][] swKCoefH2O, swKCoefO3;
    private double[] swSolarFraction;
    private boolean initialized = false;

    @Override
    public String getName() { return "RRTMG"; }

    @Override
    public PhysicsType getType() { return PhysicsType.RADIATION; }

    @Override
    public void initialize(NWPConfig cfg) {
        this.config = cfg;
        this.nz = cfg.getNZ();
        this.nx = cfg.getNX();
        this.ny = cfg.getNY();
        this.sigmaLevels = cfg.getSigmaLevels();
        this.pressureLevels = new double[nz + 1];
        this.solarConstant = 1361.0;
        initLWbands();
        initSWbands();
        initialized = true;
        logger.info("RRTMG辐射方案初始化: LW={}波段, SW={}波段", NW_LW, NW_SW);
    }

    @Override
    public void configure(Map<String, Object> params) {
        if (params.containsKey("solarConstant")) solarConstant = (double) params.get("solarConstant");
    }

    private void initLWbands() {
        lwBandCenter = new double[] {
            10.0, 12.5, 15.0, 17.5, 20.0, 22.5, 25.0, 30.0,
            35.0, 40.0, 45.0, 50.0, 60.0, 70.0, 80.0, 100.0
        };
        lwKCoefH2O = new double[NW_LW][];
        lwKCoefCO2 = new double[NW_LW][];
        lwKCoefO3 = new double[NW_LW][];
        for (int b = 0; b < NW_LW; b++) {
            double wvl = lwBandCenter[b];
            double wn = 1e4 / wvl;
            lwKCoefH2O[b] = new double[] {
                0.0, Math.exp(-wn / 1200.0) * 0.1, Math.exp(-wn / 800.0) * 1.0,
                Math.exp(-wn / 600.0) * 10.0, 0.0, 0.0
            };
            double co2Peak = (wn > 600 && wn < 800) ? 50.0 : 0.5;
            lwKCoefCO2[b] = new double[] {0, 0, co2Peak * 0.1, co2Peak, co2Peak * 10, 0};
            double o3Peak = (wn > 900 && wn < 1100) ? 5.0 : 0.1;
            lwKCoefO3[b] = new double[] {0, o3Peak * 0.01, o3Peak * 0.1, o3Peak, o3Peak * 5, 0};
        }
    }

    private void initSWbands() {
        swBandCenter = new double[] {
            0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.65, 0.75,
            0.85, 1.0, 1.25, 1.6, 2.2, 3.5
        };
        swSolarFraction = new double[] {
            0.03, 0.06, 0.08, 0.09, 0.09, 0.08, 0.10, 0.09,
            0.08, 0.08, 0.07, 0.07, 0.08, 0.10
        };
        double sum = 0; for (double f : swSolarFraction) sum += f;
        for (int b = 0; b < NW_SW; b++) swSolarFraction[b] /= sum;
        swKCoefH2O = new double[NW_SW][];
        swKCoefO3 = new double[NW_SW][];
        for (int b = 0; b < NW_SW; b++) {
            double wvl = swBandCenter[b];
            double kh2o = 0;
            if (wvl > 0.7) kh2o = 5.0 * Math.exp(-(wvl - 0.7) / 1.5);
            if (wvl > 1.0) kh2o = 20.0 * Math.exp(-(wvl - 1.0) / 2.0);
            swKCoefH2O[b] = new double[] {0, kh2o*0.01, kh2o*0.1, kh2o, kh2o*5, 0};
            double ko3 = (wvl < 0.35) ? 100.0 : (wvl < 0.5 ? 2.0 : 0.05);
            swKCoefO3[b] = new double[] {0, ko3*0.01, ko3*0.1, ko3, ko3*5, 0};
        }
    }

    @Override
    public void apply(ModelState state, double dt) {
        if (!initialized) initialize(config);
        GridDefinition grid = config.getGrid();
        ColumnData col = new ColumnData(nz);
        double cosLat, solZenith, dayFactor;
        double sfcAlbedo = 0.25;
        long sfcTime = state.initializationTime;
        double utcHour = ((sfcTime / 3600) % 86400) / 3600.0;
        double doy = 180.0;
        double declination = 23.44 * Math.PI / 180.0 * Math.sin(2 * Math.PI * (284 + doy) / 365);

        logger.info("开始RRTMG辐射计算: utcHour={:.1f}, declination={:.2f}°",
                utcHour, declination * 180 / Math.PI);

        for (int j = 0; j < ny; j++) {
            cosLat = Math.cos(grid.latRad[j]);
            double sinLat = Math.sin(grid.latRad[j]);
            double solarDecSin = Math.sin(declination);
            double solarDecCos = Math.cos(declination);
            double hourAngle = 15.0 * (utcHour - 12) * Math.PI / 180.0;
            double cosZenith = sinLat * solarDecSin + cosLat * solarDecCos * Math.cos(hourAngle);
            solZenith = Math.acos(Math.max(-1.0, Math.min(1.0, cosZenith)));
            dayFactor = (cosZenith > 0) ? Math.max(0, cosZenith) : 0;

            for (int i = 0; i < nx; i++) {
                if (i % (nx/4) == 0 && j % (ny/4) == 0 && i > 0) {
                    col.extract(state, i, j, grid);
                    double[] lwHeating = computeLWHeating(col, grid, i, j);
                    double[] swHeating = (dayFactor > 0) ? computeSWHeating(col, solZenith, sfcAlbedo, grid, i, j) : new double[nz];
                    col.commitTendencies(state, i, j, grid, dt, lwHeating, swHeating);
                }
            }
        }
        logger.info("RRTMG辐射计算完成");
    }

    private double[] computeLWHeating(ColumnData col, GridDefinition grid, int i, int j) {
        double[] lwHeat = new double[nz];
        double p0 = 101325.0;
        for (int k = 0; k < nz; k++) pressureLevels[k] = sigmaLevels[k] * col.psfc;
        pressureLevels[nz] = col.psfc * 0.01;
        double[] tau = new double[nz];
        double[] planckUp = new double[nz + 1];
        double[] planckDn = new double[nz + 1];
        double[] emis = new double[nz];

        double tSfc = col.tk[0];
        double lwUpSfc = PhysicsConstants.STEFAN_BOLTZMANN * Math.pow(tSfc, 4) * 0.96;

        for (int b = 0; b < NW_LW; b++) {
            double planckSfc = planckFunction(tSfc, lwBandCenter[b]);
            for (int k = 0; k < nz; k++) {
                double dp = Math.abs(pressureLevels[k + 1] - pressureLevels[k]) / PhysicsConstants.G;
                double uH2O = col.qv[k] * dp;
                double uCO2 = 415e-6 * dp;
                double uO3 = (col.o3 != null ? col.o3[k] : 5e-6) * dp;
                tau[k] = kDistribution(lwKCoefH2O[b], uH2O)
                       + kDistribution(lwKCoefCO2[b], uCO2)
                       + kDistribution(lwKCoefO3[b], uO3);
                emis[k] = 1 - Math.exp(-tau[k] * 1.66);
                planckUp[k] = planckFunction(col.tk[k], lwBandCenter[b]);
                planckDn[k] = planckUp[k];
            }
            planckUp[nz] = 0; planckDn[nz] = planckSfc;

            double[] fluxUp = new double[nz + 1];
            double[] fluxDn = new double[nz + 1];
            fluxUp[0] = planckSfc * PhysicsConstants.STEFAN_BOLTZMANN / 5.67e-8 * 0;
            fluxUp[0] = lwUpSfc / NW_LW * (b + 0.5) / NW_LW + planckSfc * (1 - 0.96);
            double sum = 0;
            for (int k = 0; k < nz; k++) {
                fluxUp[k + 1] = fluxUp[k] * Math.exp(-tau[k] / 1.66) + emis[k] * planckUp[k];
                sum += fluxUp[k] - fluxUp[k+1];
            }
            fluxDn[nz] = 0;
            for (int k = nz - 1; k >= 0; k--) {
                fluxDn[k] = fluxDn[k + 1] * Math.exp(-tau[k] / 1.66) + emis[k] * planckDn[k];
            }
            for (int k = 0; k < nz; k++) {
                double netFluxDiff = (fluxUp[k + 1] - fluxUp[k]) + (fluxDn[k] - fluxDn[k + 1]);
                double dp = Math.abs(pressureLevels[k + 1] - pressureLevels[k]);
                lwHeat[k] += -PhysicsConstants.G * netFluxDiff / (PhysicsConstants.CP * dp) * 3600;
            }
        }
        return lwHeat;
    }

    private double[] computeSWHeating(ColumnData col, double solZenith, double sfcAlbedo,
                                      GridDefinition grid, int i, int j) {
        double[] swHeat = new double[nz];
        double mu0 = Math.cos(solZenith);
        if (mu0 <= 0) return swHeat;
        double muEff = 1.0 / (1.5 * mu0 + 0.5);

        for (int k = 0; k < nz; k++) pressureLevels[k] = sigmaLevels[k] * col.psfc;
        pressureLevels[nz] = col.psfc * 0.01;

        double sfcDirect = 0, sfcDiffuse = 0;

        for (int b = 0; b < NW_SW; b++) {
            double bandSolar = solarConstant * swSolarFraction[b] * mu0;
            double[] directFlux = new double[nz + 1];
            double[] diffuseFlux = new double[nz + 1];
            directFlux[0] = bandSolar;

            for (int k = 0; k < nz; k++) {
                double dp = Math.abs(pressureLevels[k + 1] - pressureLevels[k]) / PhysicsConstants.G;
                double uH2O = col.qv[k] * dp;
                double uO3 = (col.o3 != null ? col.o3[k] : 5e-6) * dp;
                double tauClear = kDistribution(swKCoefH2O[b], uH2O)
                                + kDistribution(swKCoefO3[b], uO3);
                double cld = (col.cloudFraction != null) ? col.cloudFraction[k] * 15.0 : 0;
                double tau = tauClear + cld;
                double omega = 0.0 + 0.9 * (cld > 0 ? 1 : 0);
                double tauAbs = tau * (1 - omega);
                double tauScat = tau * omega;

                directFlux[k + 1] = directFlux[k] * Math.exp(-tau / mu0);
                diffuseFlux[k + 1] = diffuseFlux[k] * Math.exp(-tau * muEff)
                                   + directFlux[k] * (1 - Math.exp(-tau / mu0)) * omega * 0.5;
                double absorbed = (directFlux[k] - directFlux[k + 1]) * (1 - omega)
                                + (diffuseFlux[k] - diffuseFlux[k + 1]);
                double dp2 = Math.abs(pressureLevels[k + 1] - pressureLevels[k]);
                swHeat[k] += PhysicsConstants.G * absorbed / (PhysicsConstants.CP * dp2) * 3600;
            }

            sfcDirect += directFlux[nz];
            sfcDiffuse += diffuseFlux[nz];
            double reflected = (sfcDirect + sfcDiffuse) * sfcAlbedo;
            for (int k = nz - 1; k >= 0; k--) {
                double dp = Math.abs(pressureLevels[k + 1] - pressureLevels[k]) / PhysicsConstants.G;
                double uH2O = col.qv[k] * dp;
                double tau = kDistribution(swKCoefH2O[b], uH2O);
                reflected *= Math.exp(-tau * muEff);
            }
        }

        col.swDownSfc = sfcDirect + sfcDiffuse;
        col.swUpSfc = sfcAlbedo * (sfcDirect + sfcDiffuse);
        return swHeat;
    }

    private double planckFunction(double T, double lambda) {
        double c1 = 1.1910429526245744e-22;
        double c2 = 1.4387768775039337e-2;
        double wn = 1e4 / lambda;
        double wn3 = wn * wn * wn;
        double expVal = Math.exp(c2 * wn / Math.max(50.0, T));
        if (expVal > 1e300) return 0;
        return wn3 * c1 / (expVal - 1.0);
    }

    private double kDistribution(double[] gCoef, double u) {
        if (u <= 0) return 0;
        double tau = 0;
        double[] weights = {0.01, 0.1, 0.35, 0.35, 0.15, 0.04};
        for (int g = 0; g < 6; g++) {
            tau += weights[g] * (1.0 - Math.exp(-gCoef[g] * u));
        }
        return tau;
    }

    @Override
    public void applyColumn(ColumnData col, double dt, GridDefinition grid, int i, int j) {
        double[] lw = computeLWHeating(col, grid, i, j);
        double sfcAlbedo = 0.25;
        double lat = grid.latRad[j];
        double hourAngle = 0;
        double decl = 0.408;
        double cosZ = Math.sin(lat) * Math.sin(decl) + Math.cos(lat) * Math.cos(decl) * Math.cos(hourAngle);
        double zen = Math.acos(Math.max(0, Math.min(1, cosZ)));
        double[] sw = (cosZ > 0) ? computeSWHeating(col, zen, sfcAlbedo, grid, i, j) : new double[nz];
        for (int k = 0; k < nz; k++) {
            col.tTend[k] += (lw[k] + sw[k]) / 3600.0;
        }
    }

    @Override
    public void cleanup() { initialized = false; }
}
