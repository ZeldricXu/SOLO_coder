import React, { useRef, useEffect, useState } from 'react';
import { Canvas, useFrame, useThree } from '@react-three/fiber';
import { OrbitControls, Grid, PerspectiveCamera } from '@react-three/drei';
import * as THREE from 'three';
import { useLegacySimulationStore } from '../store';
import { PhysicsObject } from '@physics-sim/shared';
import { RigidBodyState } from '@physics-sim/physics';
import PhysicsObjectMesh from './PhysicsObjectMesh';
import FieldVisualization from './FieldVisualization';
import ForceArrow from './ForceArrow';
import TrajectoryLine from './TrajectoryLine';

interface SceneProps {
  objects: Map<string, PhysicsObject>;
  bodies?: Map<string, RigidBodyState>;
  selectedObjectId: string | null;
  showForces: boolean;
  showTrajectories: boolean;
}

function SceneContent({ objects, bodies, selectedObjectId, showForces, showTrajectories }: SceneProps) {
  const [trajectories, setTrajectories] = useState<Map<string, THREE.Vector3[]>>(new Map());

  useFrame(() => {
    if (showTrajectories && bodies) {
      setTrajectories((prev) => {
        const next = new Map(prev);
        bodies.forEach((body, id) => {
          if (!body.isStatic) {
            const existing = next.get(id) || [];
            const newPoint = new THREE.Vector3(
              body.position.x,
              body.position.y,
              body.position.z
            );
            
            if (existing.length === 0 || 
                existing[existing.length - 1].distanceTo(newPoint) > 0.01) {
              const updated = [...existing, newPoint].slice(-1000);
              next.set(id, updated);
            }
          }
        });
        return next;
      });
    }
  });

  return (
    <>
      {Array.from(objects.entries()).map(([id, obj]) => {
        const body = bodies?.get(id);
        const position = body ? [body.position.x, body.position.y, body.position.z] : [obj.position.x, obj.position.y, obj.position.z];
        const rotation = body ? [body.rotation.x, body.rotation.y, body.rotation.z] : [obj.rotation.x, obj.rotation.y, obj.rotation.z];
        
        return (
          <group key={id}>
            <PhysicsObjectMesh
              obj={obj}
              position={position as [number, number, number]}
              rotation={rotation as [number, number, number]}
              isSelected={selectedObjectId === id}
            />
            {showForces && body && (
              <ForceArrow
                position={position as [number, number, number]}
                force={[body.force.x, body.force.y, body.force.z]}
              />
            )}
            {showTrajectories && trajectories.get(id) && trajectories.get(id)!.length > 1 && (
              <TrajectoryLine
                points={trajectories.get(id)!}
                color={selectedObjectId === id ? '#ff6b6b' : '#4ecdc4'}
              />
            )}
          </group>
        );
      })}
    </>
  );
}

function Viewport3D() {
  const {
    engine,
    isRunning,
    isPaused,
    currentTime,
    selectedObjectId,
    showGrid,
    showAxes,
    showForces,
    showTrajectories,
    cameraPosition,
    cameraTarget,
    stepSimulation,
    setCameraPosition,
    setCameraTarget,
  } = useLegacySimulationStore();

  const animationRef = useRef<number>();
  const lastTimeRef = useRef<number>(0);

  useEffect(() => {
    if (isRunning && !isPaused && engine) {
      const animate = (time: number) => {
        if (lastTimeRef.current === 0) {
          lastTimeRef.current = time;
        }
        
        const deltaTime = Math.min((time - lastTimeRef.current) / 1000, 0.1);
        lastTimeRef.current = time;
        
        stepSimulation(deltaTime);
        
        animationRef.current = requestAnimationFrame(animate);
      };
      
      animationRef.current = requestAnimationFrame(animate);
    }
    
    return () => {
      if (animationRef.current) {
        cancelAnimationFrame(animationRef.current);
      }
      lastTimeRef.current = 0;
    };
  }, [isRunning, isPaused, engine, stepSimulation]);

  const state = engine?.getState();
  const objects = state?.objects || new Map();
  const { lastStepResult } = useLegacySimulationStore();
  const mechanicsResult = lastStepResult?.mechanics;
  const bodies = mechanicsResult?.bodies;
  const fields = state?.fields || new Map();

  return (
    <div style={{ width: '100%', height: '100%', position: 'relative' }}>
      <Canvas
        shadows
        camera={{ position: [cameraPosition.x, cameraPosition.y, cameraPosition.z], fov: 60 }}
      >
        <PerspectiveCamera
          makeDefault
          position={[cameraPosition.x, cameraPosition.y, cameraPosition.z]}
          fov={60}
        />
        
        <ambientLight intensity={0.5} />
        <directionalLight
          position={[10, 10, 5]}
          intensity={1}
          castShadow
          shadow-mapSize-width={2048}
          shadow-mapSize-height={2048}
        />
        <pointLight position={[-10, -10, -10]} intensity={0.5} />
        
        {showGrid && (
          <Grid
            args={[20, 20]}
            cellSize={1}
            cellThickness={0.5}
            cellColor="#6b6b6b"
            sectionSize={5}
            sectionThickness={1}
            sectionColor="#9b9b9b"
            fadeDistance={50}
            fadeStrength={1}
            followCamera={false}
            infiniteGrid
          />
        )}
        
        {showAxes && (
          <primitive object={new THREE.AxesHelper(5)} />
        )}
        
        <SceneContent
          objects={objects}
          bodies={bodies}
          selectedObjectId={selectedObjectId}
          showForces={showForces}
          showTrajectories={showTrajectories}
        />
        
        {Array.from(fields.entries()).map(([id, field]) => (
          <FieldVisualization key={id} field={field} />
        ))}
        
        <OrbitControls
          makeDefault
          target={[cameraTarget.x, cameraTarget.y, cameraTarget.z]}
          enableDamping
          dampingFactor={0.05}
          minDistance={1}
          maxDistance={100}
          onChange={(e) => {
            if (e && (e as any).target) {
              const pos = (e as any).target.object.position;
              const target = (e as any).target.target;
              setCameraPosition({ x: pos.x, y: pos.y, z: pos.z });
              setCameraTarget({ x: target.x, y: target.y, z: target.z });
            }
          }}
        />
      </Canvas>
      
      <div style={{
        position: 'absolute',
        top: 10,
        right: 10,
        background: 'rgba(0, 0, 0, 0.7)',
        color: 'white',
        padding: '10px 15px',
        borderRadius: 8,
        fontFamily: 'monospace',
        fontSize: 14,
      }}>
        <div>时间: {currentTime.toFixed(2)}s</div>
        <div>状态: {isRunning ? (isPaused ? '已暂停' : '运行中') : '已停止'}</div>
        <div>物体数: {objects.size}</div>
      </div>
    </div>
  );
}

export default Viewport3D;
