import { Vec3, vec3, PhysicsObject, SolverConfig } from '@physics-sim/shared';
import { RigidBodyState, CollisionPair, SolverStats, SpringConstraint, DistanceConstraint, HingeConstraint } from './types';
import { createRigidBody } from './rigidBody';
import { detectCollisions, CollisionDetectionResult } from './collisionDetection';
import { 
  applyGravity, 
  integrateForcesAndVelocities, 
  processCollisionResponse,
  generateContactConstraints,
  CollisionResponseResult
} from './collisionResponse';
import { 
  solveConstraintsPBD, 
  solveSpringConstraint, 
  PBDContact, 
  PBDAnyConstraint 
} from './constraints';
import { velocityVerletStep, analyzeStiffness, StiffnessAnalysisResult } from '@physics-sim/math';
import { Vec3Ops } from '@physics-sim/math';
import { detectCCD, resolveCCD, CCDResult, CCDConfig, DEFAULT_CCD_CONFIG } from './continuousCollision';

export interface MechanicsSolverConfig {
  gravity: Vec3;
  dt: number;
  substeps: number;
  solverIterations: number;
  baumgarte: number;
  usePBD: boolean;
  useVerlet: boolean;
  adaptiveStepSize: boolean;
  tolerance: number;
  minDt: number;
  maxDt: number;
  useCCD: boolean;
  ccdConfig: Partial<CCDConfig>;
}

export const DEFAULT_MECHANICS_CONFIG: MechanicsSolverConfig = {
  gravity: vec3(0, -9.81, 0),
  dt: 1 / 60,
  substeps: 4,
  solverIterations: 10,
  baumgarte: 0.2,
  usePBD: true,
  useVerlet: true,
  adaptiveStepSize: false,
  tolerance: 1e-6,
  minDt: 1e-4,
  maxDt: 1 / 30,
  useCCD: true,
  ccdConfig: DEFAULT_CCD_CONFIG,
};

export interface MechanicsStepResult {
  bodies: Map<string, RigidBodyState>;
  collisions: CollisionDetectionResult;
  response: CollisionResponseResult;
  stats: SolverStats;
  stiffness?: StiffnessAnalysisResult;
  actualDt: number;
  ccdResults?: CCDResult[];
  ccdHandled?: number;
}

export class MechanicsSolver {
  private config: MechanicsSolverConfig;
  private bodies: Map<string, RigidBodyState>;
  private springConstraints: SpringConstraint[];
  private distanceConstraints: DistanceConstraint[];
  private hingeConstraints: HingeConstraint[];
  private accumulatedTime: number;

  constructor(config: Partial<MechanicsSolverConfig> = {}) {
    this.config = { ...DEFAULT_MECHANICS_CONFIG, ...config };
    this.bodies = new Map();
    this.springConstraints = [];
    this.distanceConstraints = [];
    this.hingeConstraints = [];
    this.accumulatedTime = 0;
  }

  addPhysicsObject(obj: PhysicsObject, initialVelocity: Vec3 = vec3(0, 0, 0), initialAngularVelocity: Vec3 = vec3(0, 0, 0)): RigidBodyState {
    const body = createRigidBody(obj, initialVelocity, initialAngularVelocity);
    this.bodies.set(body.id, body);
    return body;
  }

  removeBody(id: string): boolean {
    return this.bodies.delete(id);
  }

  getBody(id: string): RigidBodyState | undefined {
    return this.bodies.get(id);
  }

  getAllBodies(): Map<string, RigidBodyState> {
    return new Map(this.bodies);
  }

  addSpringConstraint(constraint: SpringConstraint): void {
    this.springConstraints.push(constraint);
  }

  addDistanceConstraint(constraint: DistanceConstraint): void {
    this.distanceConstraints.push(constraint);
  }

  addHingeConstraint(constraint: HingeConstraint): void {
    this.hingeConstraints.push(constraint);
  }

  applyForce(bodyId: string, force: Vec3, point: Vec3): void {
    const body = this.bodies.get(bodyId);
    if (!body || body.isStatic) return;

    body.force = Vec3Ops.add(body.force, force);
    
    const torque = Vec3Ops.cross(Vec3Ops.sub(point, body.position), force);
    body.torque = Vec3Ops.add(body.torque, torque);
  }

  applyImpulse(bodyId: string, impulse: Vec3, point: Vec3): void {
    const body = this.bodies.get(bodyId);
    if (!body || body.isStatic) return;

    body.velocity = Vec3Ops.add(body.velocity, Vec3Ops.mul(impulse, body.invMass));
    
    const angularImpulse = Vec3Ops.cross(Vec3Ops.sub(point, body.position), impulse);
    const angularChange = {
      x: body.invInertiaTensor[0][0] * angularImpulse.x + body.invInertiaTensor[0][1] * angularImpulse.y + body.invInertiaTensor[0][2] * angularImpulse.z,
      y: body.invInertiaTensor[1][0] * angularImpulse.x + body.invInertiaTensor[1][1] * angularImpulse.y + body.invInertiaTensor[1][2] * angularImpulse.z,
      z: body.invInertiaTensor[2][0] * angularImpulse.x + body.invInertiaTensor[2][1] * angularImpulse.y + body.invInertiaTensor[2][2] * angularImpulse.z,
    };
    body.angularVelocity = Vec3Ops.add(body.angularVelocity, angularChange);
  }

  step(dt?: number): MechanicsStepResult {
    const startTime = performance.now();
    const actualDt = dt || this.config.dt;
    const substepDt = actualDt / this.config.substeps;

    let lastCollisionResult: CollisionDetectionResult | null = null;
    let lastResponseResult: CollisionResponseResult | null = null;
    let totalImpulse = 0;

    let stiffnessResult: StiffnessAnalysisResult | undefined;
    if (this.config.adaptiveStepSize) {
      stiffnessResult = this.checkStiffness();
    }

    let totalCCDHandled = 0;
    let allCCDResults: CCDResult[] = [];
    for (let substep = 0; substep < this.config.substeps; substep++) {
      const substepResult = this.performSubstep(substepDt);
      lastCollisionResult = substepResult.collisions;
      lastResponseResult = substepResult.response;
      totalImpulse += substepResult.stats.totalImpulse;
      totalCCDHandled += substepResult.ccdHandled;
      if (substepResult.ccdResults.length > 0) {
        allCCDResults = allCCDResults.concat(substepResult.ccdResults);
      }
    }

    const endTime = performance.now();

    return {
      bodies: new Map(this.bodies),
      collisions: lastCollisionResult || {
        pairs: [],
        broadPhasePairs: 0,
        narrowPhaseTests: 0,
        detectionTime: 0,
      },
      response: lastResponseResult || {
        contactConstraints: [],
        impulseApplied: 0,
        responseTime: 0,
      },
      stats: {
        collisionPairs: lastCollisionResult?.pairs.length || 0,
        constraintIterations: this.config.solverIterations * this.config.substeps,
        totalImpulse,
        solveTime: endTime - startTime,
      },
      stiffness: stiffnessResult,
      actualDt,
      ccdResults: allCCDResults,
      ccdHandled: totalCCDHandled,
    };
  }

  private performSubstep(dt: number): {
    collisions: CollisionDetectionResult;
    response: CollisionResponseResult;
    stats: SolverStats;
    ccdResults: CCDResult[];
    ccdHandled: number;
  } {
    applyGravity(this.bodies, this.config.gravity, dt);

    for (const spring of this.springConstraints) {
      solveSpringConstraint(spring, this.bodies, dt);
    }

    if (this.config.useVerlet) {
      this.integrateWithVerlet(dt);
    } else {
      integrateForcesAndVelocities(this.bodies, dt);
    }

    let ccdResults: CCDResult[] = [];
    let ccdHandled = 0;
    if (this.config.useCCD) {
      ccdResults = detectCCD(this.bodies, dt, this.config.ccdConfig);
      ccdHandled = resolveCCD(this.bodies, ccdResults, dt);
    }

    const collisions = detectCollisions(this.bodies);
    const response = processCollisionResponse(collisions.pairs, this.bodies, this.config.usePBD);

    let totalImpulse = response.impulseApplied;
    if (this.config.usePBD && response.contactConstraints.length > 0) {
      const pbdImpulse = solveConstraintsPBD(
        response.contactConstraints as PBDAnyConstraint[],
        this.bodies,
        dt,
        this.config.solverIterations,
        this.config.baumgarte
      );
      totalImpulse += pbdImpulse;
    }

    this.enforceDistanceConstraints(dt);
    this.enforceHingeConstraints(dt);

    return {
      collisions,
      response,
      ccdResults,
      ccdHandled,
      stats: {
        collisionPairs: collisions.pairs.length,
        constraintIterations: this.config.solverIterations,
        totalImpulse,
        solveTime: collisions.detectionTime + response.responseTime,
      },
    };
  }

  private integrateWithVerlet(dt: number): void {
    const positions: Vec3[] = [];
    const velocities: Vec3[] = [];
    const accelerations: Vec3[] = [];
    const bodyList: RigidBodyState[] = [];

    this.bodies.forEach((body) => {
      if (!body.isStatic) {
        bodyList.push(body);
        positions.push({ ...body.position });
        velocities.push({ ...body.velocity });
        accelerations.push(Vec3Ops.mul(body.force, body.invMass));
      }
    });

    if (positions.length === 0) return;

    const computeAccelerations = (pos: Vec3[]): Vec3[] => {
      return pos.map((p, i) => {
        const body = bodyList[i];
        const gravityAccel = this.config.gravity;
        const forceAccel = Vec3Ops.mul(body.force, body.invMass);
        return Vec3Ops.add(gravityAccel, forceAccel);
      });
    };

    const result = velocityVerletStep(positions, velocities, accelerations, computeAccelerations, dt);

    bodyList.forEach((body, i) => {
      body.prevPosition = { ...body.position };
      body.position = result.positions[i];
      body.velocity = result.velocities[i];
      body.force = { x: 0, y: 0, z: 0 };
      body.torque = { x: 0, y: 0, z: 0 };
    });
  }

  private enforceDistanceConstraints(dt: number): void {
    for (let i = 0; i < this.config.solverIterations; i++) {
      for (const constraint of this.distanceConstraints) {
        const bodyA = this.bodies.get(constraint.bodyA);
        const bodyB = this.bodies.get(constraint.bodyB);
        
        if (!bodyA || !bodyB) continue;

        const worldA = Vec3Ops.add(bodyA.position, constraint.anchorA);
        const worldB = Vec3Ops.add(bodyB.position, constraint.anchorB);
        
        const delta = Vec3Ops.sub(worldB, worldA);
        const distance = Vec3Ops.length(delta);
        const normal = distance > 0 ? Vec3Ops.div(delta, distance) : vec3(1, 0, 0);
        
        const error = distance - constraint.distance;
        const compliance = constraint.compliance / (dt * dt);
        
        const invMassA = bodyA.isStatic ? 0 : bodyA.invMass;
        const invMassB = bodyB.isStatic ? 0 : bodyB.invMass;
        
        const totalInverseMass = invMassA + invMassB + compliance;
        
        if (totalInverseMass <= 0) continue;
        
        const correction = -error / totalInverseMass;
        const impulse = Vec3Ops.mul(normal, correction);
        
        if (!bodyA.isStatic) {
          bodyA.position = Vec3Ops.add(bodyA.position, Vec3Ops.mul(impulse, invMassA));
        }
        if (!bodyB.isStatic) {
          bodyB.position = Vec3Ops.sub(bodyB.position, Vec3Ops.mul(impulse, invMassB));
        }
      }
    }
  }

  private enforceHingeConstraints(dt: number): void {
    for (let i = 0; i < this.config.solverIterations; i++) {
      for (const constraint of this.hingeConstraints) {
        const bodyA = this.bodies.get(constraint.bodyA);
        const bodyB = this.bodies.get(constraint.bodyB);
        
        if (!bodyA || !bodyB) continue;

        const worldAnchorA = Vec3Ops.add(bodyA.position, constraint.anchor);
        const worldAnchorB = Vec3Ops.add(bodyB.position, constraint.anchor);
        
        const delta = Vec3Ops.sub(worldAnchorB, worldAnchorA);
        const distance = Vec3Ops.length(delta);
        
        if (distance > 1e-6) {
          const normal = Vec3Ops.normalize(delta);
          const invMassA = bodyA.isStatic ? 0 : bodyA.invMass;
          const invMassB = bodyB.isStatic ? 0 : bodyB.invMass;
          const totalInverseMass = invMassA + invMassB;
          
          if (totalInverseMass > 0) {
            const correction = -distance / totalInverseMass;
            const impulse = Vec3Ops.mul(normal, correction);
            
            if (!bodyA.isStatic) {
              bodyA.position = Vec3Ops.add(bodyA.position, Vec3Ops.mul(impulse, invMassA));
            }
            if (!bodyB.isStatic) {
              bodyB.position = Vec3Ops.sub(bodyB.position, Vec3Ops.mul(impulse, invMassB));
            }
          }
        }
      }
    }
  }

  checkStiffness(): StiffnessAnalysisResult {
    const states: number[] = [];
    const derivatives: number[] = [];
    
    this.bodies.forEach((body) => {
      if (!body.isStatic) {
        states.push(body.position.x, body.position.y, body.position.z);
        states.push(body.velocity.x, body.velocity.y, body.velocity.z);
        
        const acceleration = Vec3Ops.mul(body.force, body.invMass);
        derivatives.push(body.velocity.x, body.velocity.y, body.velocity.z);
        derivatives.push(acceleration.x, acceleration.y, acceleration.z);
      }
    });
    
    if (states.length < 6) {
      return { 
        isStiff: false, 
        stiffnessRatio: 0, 
        maxEigenvalue: 0, 
        minEigenvalue: 0,
        recommendedIntegrator: 'explicit',
        recommendedTimeStep: this.config.dt
      };
    }
    
    return analyzeStiffness(
      (t: number, y: number[]) => derivatives,
      0,
      states,
      this.config.dt
    );
  }

  reset(): void {
    this.bodies.clear();
    this.springConstraints = [];
    this.distanceConstraints = [];
    this.hingeConstraints = [];
    this.accumulatedTime = 0;
  }

  setConfig(config: Partial<MechanicsSolverConfig>): void {
    this.config = { ...this.config, ...config };
  }

  getConfig(): MechanicsSolverConfig {
    return { ...this.config };
  }
}

export const MechanicsSolverOps = {
  MechanicsSolver,
  DEFAULT_MECHANICS_CONFIG,
};
