import React, { useRef, useEffect, useMemo } from 'react';
import { Canvas, useThree, useFrame } from '@react-three/fiber';
import { OrbitControls, Grid, Environment } from '@react-three/drei';
import { EffectComposer, SSAO, Bloom } from '@react-three/postprocessing';
import * as THREE from 'three';
import { useFloorPlanStore } from '@/store/useFloorPlanStore';
import { useUIStore } from '@/store/useUIStore';
import { WallBuilder } from '@/engine/scene/WallBuilder';
import { LightManager } from '@/engine/lighting/LightManager';
import type { Wall, Room, Opening, FurnitureItem, LightSource } from '@/types/floorplan';
import { PBRMaterialFactory } from '@/engine/materials/PBRMaterialFactory';

interface SceneContentProps {
  wallBuilder: WallBuilder;
  lightManager: LightManager;
  materialFactory: PBRMaterialFactory;
}

const SceneContent: React.FC<SceneContentProps> = ({ wallBuilder, lightManager, materialFactory }) => {
  const { floorPlan, selectedIds, hoveredId } = useFloorPlanStore();
  const { showHelpers } = useUIStore();
  const { scene } = useThree();
  const wallGroupsRef = useRef<Map<string, THREE.Group>>(new Map());
  const furnitureMeshesRef = useRef<Map<string, THREE.Group>>(new Map());
  const initializedRef = useRef(false);

  useEffect(() => {
    if (!initializedRef.current) {
      lightManager.createDefaultLights();
      initializedRef.current = true;
    }
    lightManager.setShowHelpers(showHelpers);
  }, [lightManager, showHelpers]);

  useEffect(() => {
    floorPlan.lights.forEach((light) => {
      if (!lightManager.getLight(light.id)) {
        lightManager.addLight(light);
      } else {
        lightManager.updateLight(light);
      }
    });

    lightManager.updateHelpers();
  }, [floorPlan.lights, lightManager]);

  useFrame(() => {
    lightManager.updateHelpers();
  });

  const renderWalls = useMemo(() => {
    return floorPlan.walls.map((wall) => (
      <Wall3D
        key={wall.id}
        wall={wall}
        openings={floorPlan.openings.filter((o) => o.wallId === wall.id)}
        materials={floorPlan.materials}
        wallBuilder={wallBuilder}
        isSelected={selectedIds.includes(wall.id)}
        isHovered={hoveredId === wall.id}
        materialFactory={materialFactory}
      />
    ));
  }, [floorPlan.walls, floorPlan.openings, floorPlan.materials, selectedIds, hoveredId, wallBuilder, materialFactory]);

  const renderRooms = useMemo(() => {
    return floorPlan.rooms.map((room) => (
      <Room3D
        key={room.id}
        room={room}
        materials={floorPlan.materials}
        wallBuilder={wallBuilder}
        wallHeight={floorPlan.project.settings.wallHeight}
      />
    ));
  }, [floorPlan.rooms, floorPlan.materials, wallBuilder, floorPlan.project.settings.wallHeight]);

  const renderFurniture = useMemo(() => {
    return floorPlan.furniture.map((item) => (
      <Furniture3D
        key={item.id}
        item={item}
        isSelected={selectedIds.includes(item.id)}
        isHovered={hoveredId === item.id}
        materialFactory={materialFactory}
      />
    ));
  }, [floorPlan.furniture, selectedIds, hoveredId, materialFactory]);

  return (
    <group>
      <ambientLight intensity={0.2} />
      {renderRooms}
      {renderWalls}
      {renderFurniture}
      <Grid
        args={[50, 50]}
        cellSize={1}
        cellThickness={0.5}
        cellColor="#2a3040"
        sectionSize={10}
        sectionThickness={1}
        sectionColor="#3a4050"
        fadeDistance={30}
        fadeStrength={1}
        followCamera={false}
        infiniteGrid
      />
      <EffectComposer>
        <SSAO 
          intensity={0.5} 
          radius={0.5} 
          luminanceInfluence={0.5}
          worldDistanceThreshold={10}
          worldDistanceFalloff={1}
          worldProximityThreshold={0.5}
          worldProximityFalloff={0.1}
        />
        <Bloom luminanceThreshold={0.8} luminanceSmoothing={0.9} intensity={0.3} />
      </EffectComposer>
    </group>
  );
};

interface Wall3DProps {
  wall: Wall;
  openings: Opening[];
  materials: any[];
  wallBuilder: WallBuilder;
  isSelected: boolean;
  isHovered: boolean;
  materialFactory: PBRMaterialFactory;
}

const Wall3D: React.FC<Wall3DProps> = ({ wall, openings, materials, wallBuilder, isSelected, isHovered, materialFactory }) => {
  const groupRef = useRef<THREE.Group>(null);

  const mesh = useMemo(() => {
    const group = wallBuilder.buildWall(wall, openings, materials);
    if (isSelected || isHovered) {
      const outlineMat = materialFactory.createSelectionOutline();
      const children = group.children[0] as THREE.Mesh;
      if (children) {
        const edges = new THREE.EdgesGeometry(children.geometry);
        const line = new THREE.LineSegments(edges, materialFactory.createWireframeMaterial());
        group.add(line);
      }
    }
    return group;
  }, [wall, openings, materials, wallBuilder, isSelected, isHovered, materialFactory]);

  return <primitive object={mesh} ref={groupRef} />;
};

interface Room3DProps {
  room: Room;
  materials: any[];
  wallBuilder: WallBuilder;
  wallHeight: number;
}

const Room3D: React.FC<Room3DProps> = ({ room, materials, wallBuilder, wallHeight }) => {
  const floorMesh = useMemo(() => {
    return wallBuilder.buildFloor(room.boundary, room.floorMaterialId, materials);
  }, [room, materials, wallBuilder]);

  const ceilingMesh = useMemo(() => {
    return wallBuilder.buildCeiling(room.boundary, wallHeight, room.ceilingMaterialId, materials);
  }, [room, materials, wallBuilder, wallHeight]);

  return (
    <group>
      <primitive object={floorMesh} />
      <primitive object={ceilingMesh} />
    </group>
  );
};

interface Furniture3DProps {
  item: FurnitureItem;
  isSelected: boolean;
  isHovered: boolean;
  materialFactory: PBRMaterialFactory;
}

const Furniture3D: React.FC<Furniture3DProps> = ({ item, isSelected, isHovered, materialFactory }) => {
  const groupRef = useRef<THREE.Group>(null);

  const mesh = useMemo(() => {
    const group = new THREE.Group();

    const size = 0.8 * item.scale;
    const geometry = new THREE.BoxGeometry(size, size * 0.6, size);
    const material = new THREE.MeshStandardMaterial({
      color: isSelected ? 0xff6b35 : isHovered ? 0x00d4ff : 0x6c757d,
      roughness: 0.5,
      metalness: 0.1,
    });

    const mesh = new THREE.Mesh(geometry, material);
    mesh.position.y = (size * 0.6) / 2;
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    group.add(mesh);

    if (isSelected || isHovered) {
      const edges = new THREE.EdgesGeometry(geometry);
      const line = new THREE.LineSegments(edges, materialFactory.createWireframeMaterial());
      line.position.y = (size * 0.6) / 2;
      group.add(line);
    }

    group.position.set(item.position.x, item.position.y, item.position.z);
    group.rotation.y = item.rotation;

    group.userData = { furnitureId: item.id, type: 'furniture' };
    return group;
  }, [item, isSelected, isHovered, materialFactory]);

  return <primitive object={mesh} ref={groupRef} />;
};

export const Scene3D: React.FC = () => {
  const [wallBuilder] = React.useState(() => new WallBuilder());
  const [materialFactory] = React.useState(() => new PBRMaterialFactory());
  const sceneRef = useRef<THREE.Scene | null>(null) as React.MutableRefObject<THREE.Scene | null>;
  const lightManagerRef = useRef<LightManager | null>(null) as React.MutableRefObject<LightManager | null>;

  const handleSceneCreated = (state: any) => {
    sceneRef.current = state.scene;
    if (!lightManagerRef.current) {
      lightManagerRef.current = new LightManager(state.scene);
    }
  };

  return (
    <div className="w-full h-full bg-canvas-bg">
      <Canvas
        shadows
        camera={{ position: [8, 8, 8], fov: 50 }}
        gl={{ antialias: true, toneMapping: THREE.ACESFilmicToneMapping, toneMappingExposure: 1.0 }}
        onCreated={handleSceneCreated}
        dpr={[1, 2]}
      >
        <color attach="background" args={['#1a1f2e']} />
        <fog attach="fog" args={['#1a1f2e', 20, 50]} />
        <Environment preset="city" />
        {lightManagerRef.current && (
          <SceneContent
            wallBuilder={wallBuilder}
            lightManager={lightManagerRef.current}
            materialFactory={materialFactory}
          />
        )}
        <OrbitControls
          enableDamping
          dampingFactor={0.05}
          minDistance={2}
          maxDistance={30}
          maxPolarAngle={Math.PI / 2 - 0.01}
        />
      </Canvas>
    </div>
  );
};
