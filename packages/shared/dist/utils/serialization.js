import { pack, unpack } from 'msgpackr';
export function serializeScene(scene) {
    const plainScene = {
        ...scene,
        metadata: { ...scene.metadata },
        objects: [...scene.objects],
        sensors: [...scene.sensors],
        simulationConfig: { ...scene.simulationConfig },
        fieldVisualizations: [...scene.fieldVisualizations],
        background: { ...scene.background },
        camera: { ...scene.camera },
    };
    return pack(plainScene);
}
export function deserializeScene(data) {
    return unpack(data);
}
export function sceneToJSON(scene) {
    return JSON.stringify(scene, null, 2);
}
export function sceneFromJSON(json) {
    return JSON.parse(json);
}
export function validateScene(scene) {
    if (typeof scene !== 'object' || scene === null)
        return false;
    const s = scene;
    return (typeof s.metadata === 'object' &&
        Array.isArray(s.objects) &&
        Array.isArray(s.sensors) &&
        typeof s.simulationConfig === 'object');
}
//# sourceMappingURL=serialization.js.map