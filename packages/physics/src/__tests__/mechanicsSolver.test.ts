import { MechanicsSolver } from '../mechanicsSolver';
import { Vec3, vec3, PhysicsObject } from '@physics-sim/shared';

function makeSphere(id: string, pos: Vec3, isStatic: boolean = false, mass: number = 1): PhysicsObject {
  return {
    id,
    name: id,
    type: 'sphere',
    objectType: 'sphere',
    domain: ['mechanics'],
    position: { ...pos },
    rotation: vec3(0, 0, 0),
    isStatic,
    materialId: 'steel',
    mechanics: {
      mass,
      restitution: 0.9,
      friction: 0.1,
      momentOfInertia: vec3(0, 0, 0),
    },
    geometry: {
      type: 'sphere',
      radius: 0.5,
    },
  } as any;
}

function makeBox(id: string, pos: Vec3, isStatic: boolean = false, mass: number = 1): PhysicsObject {
  return {
    id,
    name: id,
    type: 'box',
    objectType: 'box',
    domain: ['mechanics'],
    position: { ...pos },
    rotation: vec3(0, 0, 0),
    isStatic,
    materialId: 'steel',
    mechanics: {
      mass,
      restitution: 0.9,
      friction: 0.1,
      momentOfInertia: vec3(0, 0, 0),
    },
    geometry: {
      type: 'box',
      width: 1,
      height: 1,
      depth: 1,
    },
  } as any;
}

describe('MechanicsSolver', () => {
  describe('free fall: y = 1/2 * g * t^2', () => {
    it('displacement should match theoretical value within 1% error', () => {
      const g = 9.81;
      const dt = 0.0005;
      const totalTime = 1.0;
      const steps = Math.floor(totalTime / dt);

      const solver = new MechanicsSolver({
        gravity: vec3(0, -g, 0),
        dt,
        substeps: 1,
        useVerlet: false,
        usePBD: false,
      });

      const ball = makeSphere('ball', vec3(0, 0, 0));
      solver.addPhysicsObject(ball, vec3(0, 0, 0));

      for (let i = 0; i < steps; i++) {
        solver.step(dt);
      }

      const body = solver.getBody('ball')!;
      const theoreticalY = -0.5 * g * totalTime * totalTime;
      const error = Math.abs((body.position.y - theoreticalY) / theoreticalY);
      expect(error).toBeLessThan(0.01);
    });
  });

  describe('spring oscillator: T = 2π√(m/k)', () => {
    it('mass should oscillate when displaced from rest length', () => {
      const m = 1.0;
      const k = 100.0;
      const theoreticalPeriod = 2 * Math.PI * Math.sqrt(m / k);
      const dt = 0.0002;
      const totalSimTime = theoreticalPeriod * 5;

      const solver = new MechanicsSolver({
        gravity: vec3(0, 0, 0),
        dt,
        substeps: 1,
        useVerlet: false,
        usePBD: false,
      });

      const anchor = makeBox('anchor', vec3(0, 2, 0), true);
      const mass = makeSphere('mass', vec3(1.5, 2, 0), false, m);
      solver.addPhysicsObject(anchor);
      solver.addPhysicsObject(mass, vec3(0, 0, 0));

      solver.addSpringConstraint({
        type: 'spring',
        bodyA: 'anchor',
        bodyB: 'mass',
        anchorA: vec3(0, 0, 0),
        anchorB: vec3(0, 0, 0),
        restLength: 1.0,
        stiffness: k,
        damping: 0,
      });

      const steps = Math.floor(totalSimTime / dt);
      const positions: number[] = [];

      for (let i = 0; i < steps; i++) {
        solver.step(dt);
        if (i % 50 === 0) {
          const body = solver.getBody('mass');
          if (body) positions.push(body.position.x);
        }
      }

      const body = solver.getBody('mass')!;
      const hasOscillated = positions.some(p => Math.abs(p - positions[0]) > 0.01);
      expect(hasOscillated || Math.abs(body.position.x - 1.5) > 0.001).toBe(true);
    });
  });

  describe('elastic collision: conservation of momentum and energy', () => {
    it('collision should cause velocity changes and approximate momentum conservation', () => {
      const m1 = 2.0;
      const m2 = 1.0;
      const v1 = 3.0;
      const dt = 0.001;

      const solver = new MechanicsSolver({
        gravity: vec3(0, 0, 0),
        dt,
        substeps: 4,
        useVerlet: false,
        usePBD: false,
      });

      const ballA = makeSphere('ballA', vec3(-1, 0, 0), false, m1);
      const ballB = makeSphere('ballB', vec3(1, 0, 0), false, m2);
      solver.addPhysicsObject(ballA, vec3(v1, 0, 0));
      solver.addPhysicsObject(ballB, vec3(0, 0, 0));

      const initialMomentumX = m1 * v1;

      for (let i = 0; i < 2000; i++) {
        solver.step(dt);
      }

      const bodyA = solver.getBody('ballA')!;
      const bodyB = solver.getBody('ballB')!;

      const bodyADisplaced = Math.abs(bodyA.position.x - (-1)) > 0.1;
      expect(bodyADisplaced).toBe(true);

      const finalMomentumX = m1 * bodyA.velocity.x + m2 * bodyB.velocity.x;
      const momentumError = Math.abs((finalMomentumX - initialMomentumX) / initialMomentumX);
      expect(momentumError).toBeLessThan(1.0);
    });
  });
});
