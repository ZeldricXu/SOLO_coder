package com.meteorology.nwp.common;

public enum VariableType {
    T("Temperature", "K", true, true),
    U("Zonal wind", "m/s", true, true),
    V("Meridional wind", "m/s", true, true),
    W("Vertical velocity", "Pa/s", true, false),
    QV("Water vapor mixing ratio", "kg/kg", true, true),
    QC("Cloud water mixing ratio", "kg/kg", true, false),
    QR("Rain water mixing ratio", "kg/kg", true, false),
    QI("Ice mixing ratio", "kg/kg", true, false),
    QS("Snow mixing ratio", "kg/kg", true, false),
    QG("Graupel mixing ratio", "kg/kg", true, false),
    PSFC("Surface pressure", "Pa", false, true),
    SLP("Sea level pressure", "Pa", false, true),
    PRECIP("Precipitation rate", "mm/h", false, false),
    T2("2m temperature", "K", false, true),
    Q2("2m specific humidity", "kg/kg", false, false),
    U10("10m zonal wind", "m/s", false, true),
    V10("10m meridional wind", "m/s", false, true),
    SWDOWN("Shortwave radiation down", "W/m2", false, false),
    LWDOWN("Longwave radiation down", "W/m2", false, false),
    HFX("Sensible heat flux", "W/m2", false, false),
    LH("Latent heat flux", "W/m2", false, false),
    PBLH("Boundary layer height", "m", false, false),
    CLDFRA("Cloud fraction", "1", false, false),
    RH("Relative humidity", "%", true, true),
    GEOPOTENTIAL("Geopotential height", "m2/s2", true, true),
    DIV("Divergence", "1/s", true, false),
    VOR("Vorticity", "1/s", true, false);

    private final String description;
    private final String unit;
    private final boolean is3D;
    private final boolean isPrognostic;

    VariableType(String description, String unit, boolean is3D, boolean isPrognostic) {
        this.description = description;
        this.unit = unit;
        this.is3D = is3D;
        this.isPrognostic = isPrognostic;
    }

    public String getDescription() { return description; }
    public String getUnit() { return unit; }
    public boolean is3D() { return is3D; }
    public boolean isPrognostic() { return isPrognostic; }

    public static VariableType fromGribCode(int discipline, int category, int parameter) {
        return switch (discipline * 10000 + category * 100 + parameter) {
            case 0 -> T;
            case 2 -> U;
            case 3 -> V;
            case 131 -> U;
            case 132 -> V;
            case 133 -> QV;
            case 134 -> PSFC;
            case 135, 8 -> Q2;
            case 156 -> T2;
            case 165 -> U10;
            case 166 -> V10;
            case 167 -> T2;
            case 172 -> SLP;
            default -> null;
        };
    }
}
