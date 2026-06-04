package com.proteinviewer.domain;

public final class Atom {
    private final int serialNumber;
    private final String atomName;
    private final char altLocation;
    private final String residueName;
    private final String chainId;
    private final int residueSeqNumber;
    private final char iCode;
    private final double x;
    private final double y;
    private final double z;
    private final double occupancy;
    private final double tempFactor;
    private final String element;
    private final String charge;
    private final boolean hetatm;

    public Atom(int serialNumber, String atomName, char altLocation, String residueName,
                String chainId, int residueSeqNumber, char iCode, double x, double y, double z,
                double occupancy, double tempFactor, String element, String charge, boolean hetatm) {
        this.serialNumber = serialNumber;
        this.atomName = atomName;
        this.altLocation = altLocation;
        this.residueName = residueName;
        this.chainId = chainId;
        this.residueSeqNumber = residueSeqNumber;
        this.iCode = iCode;
        this.x = x;
        this.y = y;
        this.z = z;
        this.occupancy = occupancy;
        this.tempFactor = tempFactor;
        this.element = element;
        this.charge = charge;
        this.hetatm = hetatm;
    }

    public int getSerialNumber() { return serialNumber; }
    public String getAtomName() { return atomName; }
    public char getAltLocation() { return altLocation; }
    public String getResidueName() { return residueName; }
    public String getChainId() { return chainId; }
    public int getResidueSeqNumber() { return residueSeqNumber; }
    public char getICode() { return iCode; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public double getOccupancy() { return occupancy; }
    public double getTempFactor() { return tempFactor; }
    public String getElement() { return element; }
    public String getCharge() { return charge; }
    public boolean isHetatm() { return hetatm; }
}
