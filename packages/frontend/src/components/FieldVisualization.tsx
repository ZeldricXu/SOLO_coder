import React, { useMemo } from 'react';
import * as THREE from 'three';
import { ScalarField, VectorField } from '@physics-sim/shared/src/types/fields';

interface FieldVisualizationProps {
  field: ScalarField | VectorField;
}

const FieldVisualization: React.FC<FieldVisualizationProps> = ({ field }) => {
  const isVector = 'dataX' in field;

  const scalarFieldPoints = useMemo(() => {
    if (isVector) return null;
    
    const scalar = field as ScalarField;
    const { grid, data } = scalar;
    const { resolution, cellSize, origin } = grid;
    
    const points: [number, number, number, number][] = [];
    const step = 2;
    
    for (let i = 0; i < resolution.x; i += step) {
      for (let j = 0; j < resolution.y; j += step) {
        for (let k = 0; k < resolution.z; k += step) {
          const idx = i + j * Math.floor(resolution.x) + k * Math.floor(resolution.x) * Math.floor(resolution.y);
          const value = data[idx];
          const x = origin.x + i * cellSize.x;
          const y = origin.y + j * cellSize.y;
          const z = origin.z + k * cellSize.z;
          points.push([x, y, z, value]);
        }
      }
    }
    
    return points;
  }, [field, isVector]);

  const vectorFieldArrows = useMemo(() => {
    if (!isVector) return null;
    
    const vector = field as VectorField;
    const { grid, dataX, dataY, dataZ } = vector;
    const { resolution, cellSize, origin } = grid;
    
    const arrows: {
      position: [number, number, number];
      direction: [number, number, number];
      length: number;
    }[] = [];
    const step = 4;
    
    for (let i = 0; i < resolution.x; i += step) {
      for (let j = 0; j < resolution.y; j += step) {
        for (let k = 0; k < resolution.z; k += step) {
          const idx = i + j * Math.floor(resolution.x) + k * Math.floor(resolution.x) * Math.floor(resolution.y);
          const dx = dataX[idx];
          const dy = dataY[idx];
          const dz = dataZ[idx];
          const length = Math.sqrt(dx * dx + dy * dy + dz * dz);
          
          if (length > 1e-6) {
            const x = origin.x + i * cellSize.x;
            const y = origin.y + j * cellSize.y;
            const z = origin.z + k * cellSize.z;
            arrows.push({
              position: [x, y, z],
              direction: [dx / length, dy / length, dz / length],
              length: Math.min(length * 0.1, 0.5),
            });
          }
        }
      }
    }
    
    return arrows;
  }, [field, isVector]);

  const getColor = (value: number, min: number, max: number): string => {
    const t = (value - min) / (max - min);
    const hue = (1 - t) * 240;
    return `hsl(${hue}, 100%, 50%)`;
  };

  if (!scalarFieldPoints && !vectorFieldArrows) return null;

  return (
    <group>
      {scalarFieldPoints && (
        <points>
          <bufferGeometry>
            <bufferAttribute
              attach="attributes-position"
              count={scalarFieldPoints.length}
              array={new Float32Array(scalarFieldPoints.flatMap(([x, y, z]) => [x, y, z]))}
              itemSize={3}
            />
            <bufferAttribute
              attach="attributes-color"
              count={scalarFieldPoints.length}
              array={new Float32Array(scalarFieldPoints.flatMap(([, , , v]) => {
                const color = new THREE.Color(getColor(v, 0, 100));
                return [color.r, color.g, color.b];
              }))}
              itemSize={3}
            />
          </bufferGeometry>
          <pointsMaterial size={0.1} vertexColors transparent opacity={0.6} />
        </points>
      )}
      
      {vectorFieldArrows && vectorFieldArrows.map((arrow, i) => (
        <group key={i} position={arrow.position}>
          <mesh
            rotation={[
              Math.atan2(-arrow.direction[1], Math.sqrt(arrow.direction[0] ** 2 + arrow.direction[2] ** 2)),
              Math.atan2(arrow.direction[0], arrow.direction[2]),
              0,
            ]}
          >
            <cylinderGeometry args={[0.02, 0.02, arrow.length, 8]} />
            <meshBasicMaterial color={field.type === 'electric' ? '#ff6b6b' : '#4ecdc4'} />
          </mesh>
          <mesh
            position={[0, arrow.length / 2 + 0.05, 0]}
            rotation={[
              Math.atan2(-arrow.direction[1], Math.sqrt(arrow.direction[0] ** 2 + arrow.direction[2] ** 2)),
              Math.atan2(arrow.direction[0], arrow.direction[2]),
              0,
            ]}
          >
            <coneGeometry args={[0.05, 0.1, 8]} />
            <meshBasicMaterial color={field.type === 'electric' ? '#ff6b6b' : '#4ecdc4'} />
          </mesh>
        </group>
      ))}
    </group>
  );
};

export default FieldVisualization;
