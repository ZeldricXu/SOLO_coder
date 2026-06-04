import { Scene } from '../types/scene';
export declare function serializeScene(scene: Scene): Uint8Array;
export declare function deserializeScene(data: Uint8Array): Scene;
export declare function sceneToJSON(scene: Scene): string;
export declare function sceneFromJSON(json: string): Scene;
export declare function validateScene(scene: unknown): scene is Scene;
//# sourceMappingURL=serialization.d.ts.map