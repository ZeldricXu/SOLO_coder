package com.proteinviewer.render;

import java.util.List;

public final class RenderModel {
    private final List<SpherePrimitive> atomSpheres;
    private final List<CylinderPrimitive> bondCylinders;
    private final SurfaceMesh surfaceMesh;

    public RenderModel(List<SpherePrimitive> atomSpheres, List<CylinderPrimitive> bondCylinders,
                       SurfaceMesh surfaceMesh) {
        this.atomSpheres = atomSpheres;
        this.bondCylinders = bondCylinders;
        this.surfaceMesh = surfaceMesh;
    }

    public List<SpherePrimitive> getAtomSpheres() { return atomSpheres; }
    public List<CylinderPrimitive> getBondCylinders() { return bondCylinders; }
    public SurfaceMesh getSurfaceMesh() { return surfaceMesh; }
}
