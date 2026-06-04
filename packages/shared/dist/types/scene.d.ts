import { PhysicsObject } from './physicsObjects';
import { Sensor } from './sensors';
import { SimulationConfig } from './simulation';
import { FieldVisualization } from './fields';
export interface SceneMetadata {
    id: string;
    name: string;
    description: string;
    author: string;
    createdAt: number;
    updatedAt: number;
    version: string;
    tags: string[];
    isTemplate: boolean;
    templateId?: string;
}
export interface Scene {
    metadata: SceneMetadata;
    objects: PhysicsObject[];
    sensors: Sensor[];
    simulationConfig: SimulationConfig;
    fieldVisualizations: FieldVisualization[];
    background: {
        color: string;
        showGrid: boolean;
        showAxes: boolean;
        gridSize: number;
    };
    camera: {
        position: {
            x: number;
            y: number;
            z: number;
        };
        target: {
            x: number;
            y: number;
            z: number;
        };
        fov: number;
    };
}
export interface SceneTemplate {
    id: string;
    name: string;
    description: string;
    category: string;
    difficulty: 'beginner' | 'intermediate' | 'advanced';
    author: string;
    createdAt: number;
    updatedAt: number;
    version: string;
    sceneData: Scene;
    learningObjectives: string[];
    expectedResults: string[];
    ratings: {
        average: number;
        count: number;
    };
    comments: {
        userId: string;
        userName: string;
        comment: string;
        rating: number;
        timestamp: number;
    }[];
}
export interface SceneShare {
    sceneId: string;
    sharedBy: string;
    sharedWith: string[];
    permissions: 'view' | 'edit' | 'collaborate';
    expiresAt?: number;
}
//# sourceMappingURL=scene.d.ts.map