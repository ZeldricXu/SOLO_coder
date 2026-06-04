import React, { useMemo } from 'react';
import * as THREE from 'three';

interface TrajectoryLineProps {
  points: THREE.Vector3[];
  color?: string;
}

const TrajectoryLine: React.FC<TrajectoryLineProps> = ({ points, color = '#4ecdc4' }) => {
  const lineGeometry = useMemo(() => {
    const positions = new Float32Array(points.length * 3);
    points.forEach((p, i) => {
      positions[i * 3] = p.x;
      positions[i * 3 + 1] = p.y;
      positions[i * 3 + 2] = p.z;
    });
    
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    return geometry;
  }, [points]);

  return (
    <primitive object={new THREE.Line(lineGeometry, new THREE.LineBasicMaterial({ color, linewidth: 2, transparent: true, opacity: 0.7 }))} />
  );
};

export default TrajectoryLine;
