import { Vec3 } from './vectors';
export type ObjectType = 'box' | 'sphere' | 'cylinder' | 'plane' | 'spring' | 'incline' | 'pulley' | 'charge' | 'magnet' | 'sensor' | 'rope' | 'joint';
export type PhysicsDomain = 'mechanics' | 'electromagnetics' | 'thermodynamics';
export interface PhysicsObjectBase {
    id: string;
    name: string;
    type: ObjectType;
    objectType: ObjectType;
    domain: PhysicsDomain[];
    position: Vec3;
    rotation: Vec3;
    velocity?: Vec3;
    angularVelocity?: Vec3;
    isStatic: boolean;
    materialId: string;
    userData?: Record<string, unknown>;
    geometry?: any;
    mechanics?: Partial<{
        velocity: Vec3;
        angularVelocity: Vec3;
        mass: number;
        restitution: number;
        friction: number;
        momentOfInertia: Vec3;
    }>;
    electromagnetic?: Partial<{
        charge: number;
        isConductor: boolean;
        isDielectric: boolean;
        dielectricConstant: number;
        magneticMoment: Vec3;
        permeability: number;
    }>;
    sensorType?: string;
    thermal?: Partial<{
        temperature: number;
        heatSource: number;
        isInsulator: boolean;
    }>;
}
export interface MechanicsProperties {
    velocity: Vec3;
    angularVelocity: Vec3;
    mass: number;
    restitution: number;
    friction: number;
    momentOfInertia: Vec3;
}
export interface ElectromagneticProperties {
    charge: number;
    isConductor: boolean;
    isDielectric: boolean;
    dielectricConstant: number;
}
export interface ThermalProperties {
    temperature: number;
    heatSource: number;
    isInsulator: boolean;
}
export interface BoxObject extends PhysicsObjectBase {
    type: 'box';
    geometry: {
        type: 'box';
        width: number;
        height: number;
        depth: number;
    };
    mechanics?: Partial<MechanicsProperties>;
    electromagnetic?: Partial<ElectromagneticProperties>;
    thermal?: Partial<ThermalProperties>;
}
export interface SphereObject extends PhysicsObjectBase {
    type: 'sphere';
    geometry: {
        type: 'sphere';
        radius: number;
    };
    mechanics?: Partial<MechanicsProperties>;
    electromagnetic?: Partial<ElectromagneticProperties>;
    thermal?: Partial<ThermalProperties>;
}
export interface CylinderObject extends PhysicsObjectBase {
    type: 'cylinder';
    geometry: {
        type: 'cylinder';
        radiusTop: number;
        radiusBottom: number;
        height: number;
    };
    mechanics?: Partial<MechanicsProperties>;
    electromagnetic?: Partial<ElectromagneticProperties>;
    thermal?: Partial<ThermalProperties>;
}
export interface PlaneObject extends PhysicsObjectBase {
    type: 'plane';
    geometry: {
        type: 'plane';
        width: number;
        height: number;
    };
    mechanics?: Partial<MechanicsProperties>;
    electromagnetic?: Partial<ElectromagneticProperties>;
    thermal?: Partial<ThermalProperties>;
}
export interface InclineObject extends PhysicsObjectBase {
    type: 'incline';
    geometry: {
        type: 'incline';
        width: number;
        height: number;
        depth: number;
        angle: number;
    };
    mechanics?: Partial<MechanicsProperties>;
}
export interface SpringObject extends PhysicsObjectBase {
    type: 'spring';
    geometry: {
        type: 'spring';
        restLength: number;
        stiffness: number;
        damping: number;
        connectedBodyA?: string;
        connectedBodyB?: string;
        anchorA: Vec3;
        anchorB: Vec3;
    };
}
export interface RopeObject extends PhysicsObjectBase {
    type: 'rope';
    geometry: {
        type: 'rope';
        length: number;
        segments: number;
        thickness: number;
        connectedBodyA?: string;
        connectedBodyB?: string;
        anchorA: Vec3;
        anchorB: Vec3;
    };
}
export interface PulleyObject extends PhysicsObjectBase {
    type: 'pulley';
    geometry: {
        type: 'pulley';
        radius: number;
        thickness: number;
    };
    mechanics?: Partial<MechanicsProperties>;
}
export interface JointObject extends PhysicsObjectBase {
    type: 'joint';
    jointType: 'hinge' | 'slider' | 'ball' | 'fixed';
    connectedBodyA?: string;
    connectedBodyB?: string;
    anchor: Vec3;
    axis: Vec3;
    limits?: {
        min: number;
        max: number;
    };
}
export interface ChargeObject extends PhysicsObjectBase {
    type: 'charge';
    geometry: {
        type: 'sphere';
        radius: number;
    };
    electromagnetic: {
        charge: number;
        isConductor: boolean;
        isDielectric: boolean;
        dielectricConstant: number;
    };
}
export interface MagnetObject extends PhysicsObjectBase {
    type: 'magnet';
    geometry: {
        type: 'box' | 'cylinder';
        width?: number;
        height?: number;
        depth?: number;
        radius?: number;
    };
    electromagnetic: {
        magneticMoment: Vec3;
        permeability: number;
    };
}
export type PhysicsObject = BoxObject | SphereObject | CylinderObject | PlaneObject | InclineObject | SpringObject | RopeObject | PulleyObject | JointObject | ChargeObject | MagnetObject;
//# sourceMappingURL=physicsObjects.d.ts.map