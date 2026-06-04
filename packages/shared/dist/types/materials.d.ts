export interface Material {
    id: string;
    name: string;
    density: number;
    youngsModulus: number;
    poissonRatio: number;
    thermalConductivity: number;
    specificHeat: number;
    electricalConductivity: number;
    magneticPermeability: number;
    color: string;
}
export declare const MATERIALS: Record<string, Material>;
//# sourceMappingURL=materials.d.ts.map