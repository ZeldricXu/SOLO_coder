import React from 'react';
import * as THREE from 'three';

interface ForceArrowProps {
  position: [number, number, number];
  force: [number, number, number];
  scale?: number;
}

const ForceArrow: React.FC<ForceArrowProps> = ({ position, force, scale = 0.01 }) => {
  const magnitude = Math.sqrt(force[0] ** 2 + force[1] ** 2 + force[2] ** 2);
  
  if (magnitude < 1e-6) return null;
  
  const direction = [force[0] / magnitude, force[1] / magnitude, force[2] / magnitude];
  const length = Math.min(magnitude * scale, 2);
  
  const arrowRotation = [
    Math.atan2(-direction[1], Math.sqrt(direction[0] ** 2 + direction[2] ** 2)),
    Math.atan2(direction[0], direction[2]),
    0,
  ] as [number, number, number];

  return (
    <group position={position}>
      <mesh rotation={arrowRotation}>
        <cylinderGeometry args={[0.03, 0.03, length, 8]} />
        <meshBasicMaterial color="#ff0000" transparent opacity={0.8} />
      </mesh>
      <mesh
        position={[0, length / 2 + 0.08, 0]}
        rotation={arrowRotation}
      >
        <coneGeometry args={[0.08, 0.16, 8]} />
        <meshBasicMaterial color="#ff0000" transparent opacity={0.8} />
      </mesh>
    </group>
  );
};

export default ForceArrow;
