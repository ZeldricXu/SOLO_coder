import React, { useRef, useMemo } from 'react';
import * as THREE from 'three';
import { PhysicsObject, Material, MATERIALS } from '@physics-sim/shared';
import { useLegacySimulationStore } from '../store';

interface PhysicsObjectMeshProps {
  obj: PhysicsObject;
  position: [number, number, number];
  rotation: [number, number, number];
  isSelected: boolean;
}

const PhysicsObjectMesh: React.FC<PhysicsObjectMeshProps> = ({ obj, position, rotation, isSelected }) => {
  const meshRef = useRef<THREE.Mesh>(null);
  const { selectObject } = useLegacySimulationStore();
  const objAny = obj as any;

  const material = useMemo(() => {
    const mat = MATERIALS[obj.materialId] || MATERIALS.aluminum;
    return new THREE.MeshStandardMaterial({
      color: mat.color,
      metalness: 0.5,
      roughness: 0.3,
      transparent: obj.sensorType !== undefined || obj.isStatic || false,
      opacity: obj.sensorType !== undefined || obj.isStatic ? 0.7 : 1,
    });
  }, [obj.materialId, obj.sensorType, obj.isStatic]);

  const geometry = useMemo(() => {
    switch (obj.objectType) {
      case 'box': {
        const size = objAny.size || { x: 1, y: 1, z: 1 };
        return new THREE.BoxGeometry(size.x, size.y, size.z);
      }
      case 'sphere': {
        const radius = objAny.radius || 0.5;
        return new THREE.SphereGeometry(radius, 32, 32);
      }
      case 'cylinder': {
        const radius = objAny.radius || 0.5;
        const height = objAny.height || 1;
        return new THREE.CylinderGeometry(radius, radius, height, 32);
      }
      case 'plane': {
        const size = objAny.size || { x: 10, y: 10, z: 0 };
        return new THREE.PlaneGeometry(size.x, size.y);
      }
      case 'incline': {
        const size = objAny.size || { x: 5, y: 0.2, z: 3 };
        const angle = objAny.angle || 0;
        const geometry = new THREE.BoxGeometry(size.x, size.y, size.z);
        geometry.rotateZ(angle);
        return geometry;
      }
      case 'charge': {
        const radius = objAny.radius || 0.3;
        return new THREE.SphereGeometry(radius, 24, 24);
      }
      case 'magnet': {
        const size = objAny.size || { x: 1, y: 0.3, z: 0.5 };
        return new THREE.BoxGeometry(size.x, size.y, size.z);
      }
      case 'spring': {
        const radius = objAny.radius || 0.1;
        const height = objAny.restLength || 1;
        return new THREE.CylinderGeometry(radius, radius, height, 16, 8, true);
      }
      default:
        return new THREE.BoxGeometry(1, 1, 1);
    }
  }, [obj]);

  const handleClick = (e: any) => {
    e.stopPropagation();
    selectObject(obj.id);
  };

  return (
    <group position={position} rotation={rotation}>
      <mesh
        ref={meshRef}
        geometry={geometry}
        material={material}
        castShadow
        receiveShadow
        onClick={handleClick}
      />
      {isSelected && (
        <mesh>
          <boxGeometry args={[1.01, 1.01, 1.01]} />
          <meshBasicMaterial color="#ff6b6b" wireframe transparent opacity={0.5} />
        </mesh>
      )}
      {obj.objectType === 'charge' && (
        <mesh position={[0, 0, 0]}>
          <sphereGeometry args={[0.05, 16, 16]} />
          <meshBasicMaterial color={objAny.charge > 0 ? '#ff0000' : '#0000ff'} />
        </mesh>
      )}
    </group>
  );
};

export default PhysicsObjectMesh;
