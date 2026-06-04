import { vec3 } from '@physics-sim/shared';
import { DEFAULT_SIMULATION_CONFIG } from '@physics-sim/shared';
import { MechanicsSolver } from './mechanicsSolver';
import { ElectromagneticsSolver } from './electromagneticsSolver';
import { ThermodynamicsSolver } from './thermodynamicsSolver';
export class SimulationEngine {
    constructor(config = {}) {
        this.config = {
            enableMechanics: true,
            enableElectromagnetics: false,
            enableThermodynamics: false,
            couplingEnabled: true,
            simulationConfig: {},
            ...config,
        };
        this.simulationConfig = {
            ...DEFAULT_SIMULATION_CONFIG,
            ...this.config.simulationConfig,
        };
        this.sensorData = new Map();
        this.errors = [];
        this.isRunning = false;
        this.isPaused = false;
        this.speed = this.simulationConfig.speed;
        this.state = this.createInitialState();
        this.initializeSolvers();
    }
    createInitialState() {
        return {
            time: this.simulationConfig.startTime,
            timeStep: this.simulationConfig.mechanics.timeStep,
            isRunning: false,
            isPaused: false,
            speed: this.speed,
            objects: new Map(),
            sensors: new Map(),
            fields: new Map(),
            iteration: 0,
        };
    }
    initializeSolvers() {
        if (this.config.enableMechanics) {
            const mechConfig = this.simulationConfig.mechanics;
            this.mechanicsSolver = new MechanicsSolver({
                gravity: mechConfig.gravity,
                dt: mechConfig.timeStep,
                substeps: mechConfig.collisionIterations,
                solverIterations: mechConfig.constraintIterations,
                baumgarte: mechConfig.baumgarteStabilization,
                usePBD: true,
                useVerlet: true,
                adaptiveStepSize: mechConfig.useAdaptiveStep,
                tolerance: mechConfig.tolerance,
                minDt: mechConfig.minTimeStep,
                maxDt: mechConfig.maxTimeStep,
            });
        }
        if (this.config.enableElectromagnetics) {
            const emConfig = this.simulationConfig.electromagnetics;
            this.electromagneticsSolver = new ElectromagneticsSolver({
                resolution: emConfig.gridResolution,
                maxIterations: emConfig.maxIterations,
                tolerance: emConfig.tolerance,
                relaxationFactor: emConfig.relaxationFactor,
            });
        }
        if (this.config.enableThermodynamics) {
            const thermoConfig = this.simulationConfig.thermodynamics;
            this.thermodynamicsSolver = new ThermodynamicsSolver({
                dt: thermoConfig.timeStep,
                maxIterations: thermoConfig.maxIterations,
                tolerance: thermoConfig.tolerance,
            });
        }
    }
    addPhysicsObject(obj, initialVelocity = vec3(0, 0, 0), initialAngularVelocity = vec3(0, 0, 0)) {
        this.state.objects.set(obj.id, obj);
        if (this.mechanicsSolver) {
            this.mechanicsSolver.addPhysicsObject(obj, initialVelocity, initialAngularVelocity);
        }
    }
    removePhysicsObject(id) {
        const removed = this.state.objects.delete(id);
        if (removed && this.mechanicsSolver) {
            this.mechanicsSolver.removeBody(id);
        }
        return removed;
    }
    addSensor(sensor) {
        this.state.sensors.set(sensor.id, sensor);
        if (!this.sensorData.has(sensor.id)) {
            this.sensorData.set(sensor.id, []);
        }
    }
    removeSensor(id) {
        const removed = this.state.sensors.delete(id);
        if (removed) {
            this.sensorData.delete(id);
        }
        return removed;
    }
    step(dt) {
        const startTime = performance.now();
        this.errors = [];
        const actualDt = dt || this.state.timeStep;
        const scaledDt = actualDt * this.speed;
        let mechanicsResult;
        let electromagneticsResult;
        let thermodynamicsResult;
        try {
            if (this.mechanicsSolver && this.simulationConfig.mechanics.enabled) {
                mechanicsResult = this.mechanicsSolver.step(scaledDt);
                this.updatePhysicsObjectsFromBodies(mechanicsResult.bodies);
            }
            if (this.electromagneticsSolver && this.simulationConfig.electromagnetics.enabled) {
                electromagneticsResult = this.electromagneticsSolver.solveElectrostatic(this.state.time);
                this.state.fields.set(electromagneticsResult.field.id, electromagneticsResult.field);
                if (electromagneticsResult.potential) {
                    this.state.fields.set(electromagneticsResult.potential.id, electromagneticsResult.potential);
                }
                if (this.config.couplingEnabled && this.mechanicsSolver) {
                    this.applyElectromagneticForces(electromagneticsResult.field);
                }
            }
            if (this.thermodynamicsSolver && this.simulationConfig.thermodynamics.enabled) {
                thermodynamicsResult = this.thermodynamicsSolver.step(scaledDt);
                this.state.fields.set(thermodynamicsResult.temperature.id, thermodynamicsResult.temperature);
                if (this.config.couplingEnabled && this.mechanicsSolver) {
                    this.applyThermalExpansion(thermodynamicsResult.temperature);
                }
            }
            this.collectSensorData();
            this.state.time += scaledDt;
            this.state.iteration++;
            this.state.timeStep = actualDt;
        }
        catch (error) {
            this.errors.push(`Simulation error: ${error}`);
        }
        const endTime = performance.now();
        return {
            time: this.state.time,
            mechanics: mechanicsResult,
            electromagnetics: electromagneticsResult,
            thermodynamics: thermodynamicsResult,
            sensorData: new Map(this.sensorData),
            totalSolveTime: endTime - startTime,
            errors: [...this.errors],
        };
    }
    updatePhysicsObjectsFromBodies(bodies) {
        bodies.forEach((body, id) => {
            const obj = this.state.objects.get(id);
            if (obj) {
                obj.position = { ...body.position };
                obj.rotation = { ...body.rotation };
                if (obj.type !== 'joint' && !obj.isStatic) {
                    obj.mechanics = obj.mechanics || {};
                    obj.mechanics.velocity = { ...body.velocity };
                    obj.mechanics.angularVelocity = { ...body.angularVelocity };
                }
            }
        });
    }
    applyElectromagneticForces(field) {
        if (!this.mechanicsSolver || !this.electromagneticsSolver)
            return;
        const EM_CHARGE_PROPERTY = 'charge';
        this.state.objects.forEach((obj, id) => {
            const charge = obj[EM_CHARGE_PROPERTY];
            if (charge !== undefined && charge !== 0) {
                const body = this.mechanicsSolver?.getBody(id);
                if (body && !body.isStatic) {
                    const eField = this.electromagneticsSolver.getFieldAtPosition(field, obj.position);
                    const force = {
                        x: charge * eField.x,
                        y: charge * eField.y,
                        z: charge * eField.z,
                    };
                    this.mechanicsSolver?.applyForce(id, force, obj.position);
                }
            }
        });
    }
    applyThermalExpansion(temperature) {
        if (!this.mechanicsSolver || !this.thermodynamicsSolver)
            return;
        const THERMAL_EXPANSION_COEFFICIENT = 1e-5;
        this.state.objects.forEach((obj, id) => {
            const body = this.mechanicsSolver?.getBody(id);
            if (body && !body.isStatic) {
                const temp = this.thermodynamicsSolver.getTemperatureAtPosition(obj.position);
                const deltaT = temp - 300;
                const expansionFactor = 1 + THERMAL_EXPANSION_COEFFICIENT * deltaT;
                if ('size' in obj && obj.size) {
                    obj.size = {
                        x: obj.size.x * expansionFactor,
                        y: obj.size.y * expansionFactor,
                        z: obj.size.z * expansionFactor,
                    };
                }
            }
        });
    }
    collectSensorData() {
        this.state.sensors.forEach((sensor, id) => {
            const value = this.readSensor(sensor);
            if (value !== null) {
                const data = this.sensorData.get(id);
                if (data) {
                    data.push({ time: this.state.time, value });
                    if (data.length > 10000) {
                        data.shift();
                    }
                }
            }
        });
    }
    readSensor(sensor) {
        const position = sensor.position;
        switch (sensor.type) {
            case 'displacement':
                return { ...position };
            case 'velocity':
                if (sensor.targetObjectId) {
                    const body = this.mechanicsSolver?.getBody(sensor.targetObjectId);
                    if (body)
                        return { ...body.velocity };
                }
                return vec3(0, 0, 0);
            case 'acceleration':
                if (sensor.targetObjectId) {
                    const body = this.mechanicsSolver?.getBody(sensor.targetObjectId);
                    if (body) {
                        return {
                            x: body.force.x / body.mass,
                            y: body.force.y / body.mass,
                            z: body.force.z / body.mass,
                        };
                    }
                }
                return vec3(0, 0, 0);
            case 'force':
                if (sensor.targetObjectId) {
                    const body = this.mechanicsSolver?.getBody(sensor.targetObjectId);
                    if (body)
                        return { ...body.force };
                }
                return vec3(0, 0, 0);
            case 'temperature':
                if (this.thermodynamicsSolver) {
                    return this.thermodynamicsSolver.getTemperatureAtPosition(position);
                }
                return 300;
            case 'electricField':
                if (this.electromagneticsSolver) {
                    for (const field of this.state.fields.values()) {
                        if (field.type === 'electric' && 'dataX' in field) {
                            return this.electromagneticsSolver.getFieldAtPosition(field, position);
                        }
                    }
                }
                return vec3(0, 0, 0);
            case 'magneticField':
                if (this.electromagneticsSolver) {
                    for (const field of this.state.fields.values()) {
                        if (field.type === 'magnetic' && 'dataX' in field) {
                            return this.electromagneticsSolver.getFieldAtPosition(field, position);
                        }
                    }
                }
                return vec3(0, 0, 0);
            default:
                return null;
        }
    }
    start() {
        this.isRunning = true;
        this.isPaused = false;
        this.state.isRunning = true;
        this.state.isPaused = false;
    }
    pause() {
        this.isPaused = true;
        this.state.isPaused = true;
    }
    resume() {
        this.isPaused = false;
        this.state.isPaused = false;
    }
    stop() {
        this.isRunning = false;
        this.isPaused = false;
        this.state.isRunning = false;
        this.state.isPaused = false;
    }
    reset() {
        this.stop();
        this.state = this.createInitialState();
        this.sensorData.clear();
        this.errors = [];
        if (this.mechanicsSolver) {
            this.mechanicsSolver.reset();
        }
        if (this.electromagneticsSolver) {
            this.electromagneticsSolver.reset();
        }
        if (this.thermodynamicsSolver) {
            this.thermodynamicsSolver.reset();
        }
    }
    setSpeed(speed) {
        this.speed = Math.max(0.1, Math.min(10, speed));
        this.state.speed = this.speed;
    }
    getSpeed() {
        return this.speed;
    }
    getState() {
        return {
            ...this.state,
            objects: new Map(this.state.objects),
            sensors: new Map(this.state.sensors),
            fields: new Map(this.state.fields),
        };
    }
    getSensorData() {
        return new Map(this.sensorData);
    }
    getSensorDataById(sensorId) {
        return [...(this.sensorData.get(sensorId) || [])];
    }
    getMechanicsSolver() {
        return this.mechanicsSolver;
    }
    getElectromagneticsSolver() {
        return this.electromagneticsSolver;
    }
    getThermodynamicsSolver() {
        return this.thermodynamicsSolver;
    }
    setSimulationConfig(config) {
        this.simulationConfig = { ...this.simulationConfig, ...config };
        this.speed = this.simulationConfig.speed;
        this.state.speed = this.speed;
    }
    getSimulationConfig() {
        return { ...this.simulationConfig };
    }
    setEngineConfig(config) {
        const needsReinitialization = config.enableMechanics !== this.config.enableMechanics ||
            config.enableElectromagnetics !== this.config.enableElectromagnetics ||
            config.enableThermodynamics !== this.config.enableThermodynamics;
        this.config = { ...this.config, ...config };
        if (needsReinitialization) {
            this.initializeSolvers();
        }
    }
    getEngineConfig() {
        return { ...this.config };
    }
    getErrors() {
        return [...this.errors];
    }
    isEngineRunning() {
        return this.isRunning && !this.isPaused;
    }
    isEnginePaused() {
        return this.isPaused;
    }
    getComputationalComplexity() {
        let complexity = 0;
        if (this.mechanicsSolver && this.simulationConfig.mechanics.enabled) {
            const bodyCount = this.state.objects.size;
            complexity += bodyCount * bodyCount * 0.1;
        }
        if (this.electromagneticsSolver && this.simulationConfig.electromagnetics.enabled) {
            const res = this.simulationConfig.electromagnetics.gridResolution;
            complexity += res.x * res.y * res.z;
        }
        if (this.thermodynamicsSolver && this.simulationConfig.thermodynamics.enabled) {
            const res = this.thermodynamicsSolver.getConfig().resolution;
            complexity += res.x * res.y * res.z;
        }
        return complexity;
    }
    shouldOffloadToBackend() {
        if (!this.simulationConfig.useBackendComputation)
            return false;
        return this.getComputationalComplexity() > this.simulationConfig.computationThreshold;
    }
}
export const SimulationEngineOps = {
    SimulationEngine,
    DEFAULT_SIMULATION_CONFIG,
};
//# sourceMappingURL=simulationEngine.js.map