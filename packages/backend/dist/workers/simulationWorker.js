import { SimulationEngine } from '@physics-sim/physics/src/simulationEngine.js';
import { pack } from 'msgpackr';
export async function runSimulation(input) {
    const { scene, config, duration, jobId } = input;
    console.log(`[Worker ${process.pid}] Starting simulation job ${jobId}`);
    const startTime = performance.now();
    const engine = new SimulationEngine({});
    const frameInterval = config.timeStep * 10;
    let nextFrameTime = frameInterval;
    const frames = [];
    const sensorData = new Map();
    let currentTime = 0;
    const totalSteps = Math.ceil(duration / config.timeStep);
    let stepsCompleted = 0;
    while (currentTime < duration) {
        const stepResult = engine.step(config.timeStep);
        currentTime += config.timeStep;
        stepsCompleted++;
        if (currentTime >= nextFrameTime) {
            const state = engine.getState();
            frames.push({
                time: currentTime,
                objects: Array.from(state.objects.entries()).map(([id, obj]) => ({
                    id,
                    position: { ...obj.position },
                    rotation: { ...obj.rotation },
                    velocity: { ...obj.velocity },
                    angularVelocity: { ...obj.angularVelocity },
                })),
            });
            const sensorDataMap = stepResult.sensorData;
            for (const [sensorId, data] of sensorDataMap) {
                if (!sensorData.has(sensorId)) {
                    sensorData.set(sensorId, []);
                }
                sensorData.get(sensorId).push(...data);
            }
            nextFrameTime += frameInterval;
        }
        if (stepsCompleted % 1000 === 0) {
            const progress = ((currentTime / duration) * 100).toFixed(1);
            console.log(`[Worker ${process.pid}] Job ${jobId}: ${progress}% complete`);
        }
    }
    const endTime = performance.now();
    const computationTime = (endTime - startTime) / 1000;
    console.log(`[Worker ${process.pid}] Simulation job ${jobId} completed in ${computationTime.toFixed(2)}s`);
    console.log(`[Worker ${process.pid}] - ${totalSteps} steps, ${frames.length} frames`);
    return {
        jobId,
        success: true,
        frames,
        sensorData: Object.fromEntries(sensorData),
        statistics: {
            totalTime: duration,
            timeStep: config.timeStep,
            totalSteps,
            framesRendered: frames.length,
            computationTime,
            stepsPerSecond: totalSteps / computationTime,
            realTimeFactor: duration / computationTime,
        },
    };
}
export async function runSimulationBatch(inputs) {
    const results = [];
    for (const input of inputs) {
        results.push(await runSimulation(input));
    }
    return results;
}
export async function runSimulationBinary(input) {
    const result = await runSimulation(input);
    return pack(result);
}
export default {
    runSimulation,
    runSimulationBatch,
    runSimulationBinary,
};
//# sourceMappingURL=simulationWorker.js.map