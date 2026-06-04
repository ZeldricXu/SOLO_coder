package com.proteinviewer.mapper;

import com.proteinviewer.domain.Atom;
import com.proteinviewer.domain.Bond;
import com.proteinviewer.domain.Structure;
import com.proteinviewer.render.CylinderPrimitive;
import com.proteinviewer.render.RenderModel;
import com.proteinviewer.render.SpherePrimitive;
import com.proteinviewer.render.SurfaceMesh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RenderMapper {

    private static final Map<String, String> ELEMENT_COLORS = new HashMap<>();
    private static final Map<String, Double> ELEMENT_RADII = new HashMap<>();

    static {
        ELEMENT_COLORS.put("H", "#FFFFFF");
        ELEMENT_COLORS.put("C", "#909090");
        ELEMENT_COLORS.put("N", "#3050F8");
        ELEMENT_COLORS.put("O", "#FF0D0D");
        ELEMENT_COLORS.put("S", "#FFFF30");
        ELEMENT_COLORS.put("P", "#FF8000");
        ELEMENT_COLORS.put("CL", "#1FF01F");
        ELEMENT_COLORS.put("FE", "#E06633");
        ELEMENT_COLORS.put("ZN", "#7D80B0");

        ELEMENT_RADII.put("H", 0.25);
        ELEMENT_RADII.put("C", 0.40);
        ELEMENT_RADII.put("N", 0.40);
        ELEMENT_RADII.put("O", 0.40);
        ELEMENT_RADII.put("S", 0.50);
    }

    private static final double DEFAULT_RADIUS = 0.40;
    private static final String DEFAULT_COLOR = "#FF1493";
    private static final double BOND_RADIUS = 0.10;

    public SpherePrimitive toAtomSphere(Atom atom) {
        String element = atom.getElement() != null ? atom.getElement().toUpperCase() : "";
        String color = ELEMENT_COLORS.getOrDefault(element, DEFAULT_COLOR);
        double radius = ELEMENT_RADII.getOrDefault(element, DEFAULT_RADIUS);
        String label = atom.getResidueName() + ":" + atom.getResidueSeqNumber() + "." + atom.getAtomName();

        return new SpherePrimitive(
                atom.getX(),
                atom.getY(),
                atom.getZ(),
                radius,
                color,
                label,
                atom.getElement(),
                atom.getSerialNumber()
        );
    }

    public CylinderPrimitive toBondCylinder(Atom a1, Atom a2) {
        String e1 = a1.getElement() != null ? a1.getElement().toUpperCase() : "";
        String e2 = a2.getElement() != null ? a2.getElement().toUpperCase() : "";
        String c1 = ELEMENT_COLORS.getOrDefault(e1, DEFAULT_COLOR);
        String c2 = ELEMENT_COLORS.getOrDefault(e2, DEFAULT_COLOR);
        String avgColor = averageColorHex(c1, c2);

        return new CylinderPrimitive(
                a1.getX(), a1.getY(), a1.getZ(),
                a2.getX(), a2.getY(), a2.getZ(),
                BOND_RADIUS,
                avgColor
        );
    }

    public RenderModel toRenderModel(Structure structure) {
        List<SpherePrimitive> spheres = new ArrayList<>();
        for (Atom atom : structure.getAtoms()) {
            spheres.add(toAtomSphere(atom));
        }

        List<CylinderPrimitive> cylinders = new ArrayList<>();
        Map<Integer, Atom> atomMap = new HashMap<>();
        for (Atom atom : structure.getAtoms()) {
            atomMap.put(atom.getSerialNumber(), atom);
        }

        for (Bond bond : structure.getBonds()) {
            Atom a1 = atomMap.get(bond.getAtomSerial());
            if (a1 == null) continue;
            for (Integer bondedSerial : bond.getBondedAtoms()) {
                Atom a2 = atomMap.get(bondedSerial);
                if (a2 == null) continue;
                if (a1.getSerialNumber() < a2.getSerialNumber()) {
                    cylinders.add(toBondCylinder(a1, a2));
                }
            }
        }

        return new RenderModel(spheres, cylinders, null);
    }

    public RenderModel toRenderModelWithSurface(Structure structure, SurfaceMesh surface) {
        List<SpherePrimitive> spheres = new ArrayList<>();
        for (Atom atom : structure.getAtoms()) {
            spheres.add(toAtomSphere(atom));
        }

        List<CylinderPrimitive> cylinders = new ArrayList<>();
        Map<Integer, Atom> atomMap = new HashMap<>();
        for (Atom atom : structure.getAtoms()) {
            atomMap.put(atom.getSerialNumber(), atom);
        }

        for (Bond bond : structure.getBonds()) {
            Atom a1 = atomMap.get(bond.getAtomSerial());
            if (a1 == null) continue;
            for (Integer bondedSerial : bond.getBondedAtoms()) {
                Atom a2 = atomMap.get(bondedSerial);
                if (a2 == null) continue;
                if (a1.getSerialNumber() < a2.getSerialNumber()) {
                    cylinders.add(toBondCylinder(a1, a2));
                }
            }
        }

        return new RenderModel(spheres, cylinders, surface);
    }

    private String averageColorHex(String hex1, String hex2) {
        int r1 = Integer.parseInt(hex1.substring(1, 3), 16);
        int g1 = Integer.parseInt(hex1.substring(3, 5), 16);
        int b1 = Integer.parseInt(hex1.substring(5, 7), 16);
        int r2 = Integer.parseInt(hex2.substring(1, 3), 16);
        int g2 = Integer.parseInt(hex2.substring(3, 5), 16);
        int b2 = Integer.parseInt(hex2.substring(5, 7), 16);
        int r = (r1 + r2) / 2;
        int g = (g1 + g2) / 2;
        int b = (b1 + b2) / 2;
        return String.format("#%02X%02X%02X", r, g, b);
    }
}
