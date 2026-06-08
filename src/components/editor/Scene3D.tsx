import React, { useRef, useEffect, useMemo, useState } from 'react';
import { Canvas, useThree, useFrame } from '@react-three/fiber';
import { OrbitControls, Grid, Environment } from '@react-three/drei';
import { EffectComposer, SSAO, Bloom } from '@react-three/postprocessing';
import * as THREE from 'three';
import { useFloorPlanStore } from '@/store/useFloorPlanStore';
import { useUIStore } from '@/store/useUIStore';
import { WallBuilder } from '@/engine/scene/WallBuilder';
import { LightManager } from '@/engine/lighting/LightManager';
import { GlobalIlluminationManager } from '@/engine/lighting/GlobalIlluminationManager';
import { DrawingAnnotationManager } from '@/engine/annotations/DrawingAnnotationManager';
import type { Wall, Room, Opening, FurnitureItem, LightSource } from '@/types/floorplan';
import { PBRMaterialFactory } from '@/engine/materials/PBRMaterialFactory';
import { DrawingToolbar } from './DrawingToolbar';

interface SceneContentProps {
  wallBuilder: WallBuilder;
  lightManager: LightManager;
  giManager: GlobalIlluminationManager;
  materialFactory: PBRMaterialFactory;
  drawingManager: DrawingAnnotationManager;
}

const SceneContent: React.FC<SceneContentProps> = ({
  wallBuilder,
  lightManager,
  giManager,
  materialFactory,
  drawingManager,
}) => {
  const { floorPlan, selectedIds, hoveredId, updateAnnotation, currentTool } = useFloorPlanStore();
  const { showHelpers, giSettings, drawingSession, setDrawingSession, addNotification } = useUIStore();
  const { scene, camera, gl } = useThree();
  const wallGroupsRef = useRef<Map<string, THREE.Group>>(new Map());
  const furnitureMeshesRef = useRef<Map<string, THREE.Group>>(new Map());
  const initializedRef = useRef(false);
  const containerRef = useRef<HTMLDivElement | null>(null);

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

  useEffect(() => {
    giManager.updateSettings(giSettings);
    if (gl) {
      gl.toneMappingExposure = giSettings.exposure;
    }
  }, [giSettings, giManager, gl]);

  useEffect(() => {
    drawingManager.setSession(drawingSession);
  }, [drawingSession, drawingManager]);

  useEffect(() => {
    floorPlan.annotations.forEach((annotation) => {
      if (annotation.drawings) {
        annotation.drawings.forEach((primitive) => {
          drawingManager.renderPrimitive(primitive);
        });
      }
    });
  }, [floorPlan.annotations, drawingManager]);

  useFrame(() => {
    lightManager.updateHelpers();
  });

  const handlePointerDown = (e: any) => {
    if (currentTool !== 'annotation-draw') return;
    e.stopPropagation();
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const vertex = drawingManager.startDrawing(e.clientX, e.clientY, rect);
    if (vertex) {
      setDrawingSession({ active: true });
    }
  };

  const handlePointerMove = (e: any) => {
    if (currentTool !== 'annotation-draw' || !drawingSession.active) return;
    e.stopPropagation();
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    drawingManager.continueDrawing(e.clientX, e.clientY, rect);
  };

  const handlePointerUp = () => {
    if (currentTool !== 'annotation-draw' || !drawingSession.active) return;
    const primitive = drawingManager.endDrawing();
    if (primitive) {
      const activeAnnotation = floorPlan.annotations.find(
        (a) => selectedIds.includes(a.id)
      );
      if (activeAnnotation) {
        const existingDrawings = activeAnnotation.drawings || [];
        updateAnnotation(activeAnnotation.id, {
          drawings: [...existingDrawings, primitive],
        });
        addNotification({ type: 'success', message: '批注绘图已添加' });
      } else {
        addNotification({
          type: 'warning',
          message: '请先选择或创建一个批注',
          timeout: 3000,
        });
      }
    }
    setDrawingSession({ active: false });
  };

  const handleClearDrawings = () => {
    drawingManager.clearAllDrawings();
    addNotification({ type: 'info', message: '已清除所有绘图' });
  };

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
        drawingManager={drawingManager}
      />
    ));
  }, [
    floorPlan.walls,
    floorPlan.openings,
    floorPlan.materials,
    selectedIds,
    hoveredId,
    wallBuilder,
    materialFactory,
    drawingManager,
  ]);

  const renderRooms = useMemo(() => {
    return floorPlan.rooms.map((room) => (
      <Room3D
        key={room.id}
        room={room}
        materials={floorPlan.materials}
        wallBuilder={wallBuilder}
        wallHeight={floorPlan.project.settings.wallHeight}
        drawingManager={drawingManager}
      />
    ));
  }, [floorPlan.rooms, floorPlan.materials, wallBuilder, floorPlan.project.settings.wallHeight, drawingManager]);

  const renderFurniture = useMemo(() => {
    return floorPlan.furniture.map((item) => (
      <Furniture3D
        key={item.id}
        item={item}
        isSelected={selectedIds.includes(item.id)}
        isHovered={hoveredId === item.id}
        materialFactory={materialFactory}
        drawingManager={drawingManager}
      />
    ));
  }, [floorPlan.furniture, selectedIds, hoveredId, materialFactory, drawingManager]);

  const ssaoConfig = giManager.getSSAOConfig();

  return (
    <>
      <group
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerLeave={handlePointerUp}
      >
        {renderRooms}
        {renderWalls}
        {renderFurniture}
      </group>
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
      {ssaoConfig.enabled && (
        <EffectComposer>
          <SSAO
            intensity={ssaoConfig.intensity}
            radius={ssaoConfig.radius}
            luminanceInfluence={ssaoConfig.luminanceInfluence}
            worldDistanceThreshold={ssaoConfig.worldDistanceThreshold}
            worldDistanceFalloff={ssaoConfig.worldDistanceFalloff}
            worldProximityThreshold={ssaoConfig.worldProximityThreshold}
            worldProximityFalloff={ssaoConfig.worldProximityFalloff}
          />
          <Bloom luminanceThreshold={0.8} luminanceSmoothing={0.9} intensity={0.3} />
        </EffectComposer>
      )}
      {currentTool === 'annotation-draw' && (
        <DrawingToolbar
          session={drawingSession}
          onChange={setDrawingSession}
          onClear={handleClearDrawings}
          enabled={true}
        />
      )}
    </>
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
  drawingManager: DrawingAnnotationManager;
}

const Wall3D: React.FC<Wall3DProps> = ({
  wall,
  openings,
  materials,
  wallBuilder,
  isSelected,
  isHovered,
  materialFactory,
  drawingManager,
}) => {
  const groupRef = useRef<THREE.Group>(null);

  useEffect(() => {
    if (groupRef.current) {
      drawingManager.registerSurfaceMesh(groupRef.current as unknown as THREE.Mesh, `wall-${wall.id}`);
      groupRef.current.userData = { ...groupRef.current.userData, type: 'wall' };
    }
    return () => {
      drawingManager.unregisterSurfaceMesh(`wall-${wall.id}`);
    };
  }, [wall.id, drawingManager]);

  const mesh = useMemo(() => {
    const group = wallBuilder.buildWall(wall, openings, materials);
    group.userData = { wallId: wall.id, type: 'wall' };
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
  drawingManager: DrawingAnnotationManager;
}

const Room3D: React.FC<Room3DProps> = ({ room, materials, wallBuilder, wallHeight, drawingManager }) => {
  const floorRef = useRef<THREE.Mesh>(null);
  const ceilingRef = useRef<THREE.Mesh>(null);

  useEffect(() => {
    if (floorRef.current) {
      drawingManager.registerSurfaceMesh(floorRef.current, `floor-${room.id}`);
      floorRef.current.userData = { type: 'floor' };
    }
    if (ceilingRef.current) {
      drawingManager.registerSurfaceMesh(ceilingRef.current, `ceiling-${room.id}`);
      ceilingRef.current.userData = { type: 'ceiling' };
    }
    return () => {
      drawingManager.unregisterSurfaceMesh(`floor-${room.id}`);
      drawingManager.unregisterSurfaceMesh(`ceiling-${room.id}`);
    };
  }, [room.id, drawingManager]);

  const floorMesh = useMemo(() => {
    return wallBuilder.buildFloor(room.boundary, room.floorMaterialId, materials);
  }, [room, materials, wallBuilder]);

  const ceilingMesh = useMemo(() => {
    return wallBuilder.buildCeiling(room.boundary, wallHeight, room.ceilingMaterialId, materials);
  }, [room, materials, wallBuilder, wallHeight]);

  return (
    <group>
      <primitive object={floorMesh} ref={floorRef} />
      <primitive object={ceilingMesh} ref={ceilingRef} />
    </group>
  );
};

interface Furniture3DProps {
  item: FurnitureItem;
  isSelected: boolean;
  isHovered: boolean;
  materialFactory: PBRMaterialFactory;
  drawingManager: DrawingAnnotationManager;
}

const Furniture3D: React.FC<Furniture3DProps> = ({ item, isSelected, isHovered, materialFactory, drawingManager }) => {
  const groupRef = useRef<THREE.Group>(null);

  useEffect(() => {
    if (groupRef.current) {
      drawingManager.registerSurfaceMesh(groupRef.current as unknown as THREE.Mesh, `furniture-${item.id}`);
      groupRef.current.userData = { ...groupRef.current.userData, type: 'furniture' };
    }
    return () => {
      drawingManager.unregisterSurfaceMesh(`furniture-${item.id}`);
    };
  }, [item.id, drawingManager]);

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
  const giManagerRef = useRef<GlobalIlluminationManager | null>(null) as React.MutableRefObject<GlobalIlluminationManager | null>;
  const drawingManagerRef = useRef<DrawingAnnotationManager | null>(null) as React.MutableRefObject<DrawingAnnotationManager | null>;
  const cameraRef = useRef<THREE.PerspectiveCamera | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);

  const handleSceneCreated = (state: any) => {
    sceneRef.current = state.scene;
    cameraRef.current = state.camera;
    if (!lightManagerRef.current) {
      lightManagerRef.current = new LightManager(state.scene);
    }
    if (!giManagerRef.current) {
      giManagerRef.current = new GlobalIlluminationManager(state.scene);
    }
    if (!drawingManagerRef.current && state.camera) {
      drawingManagerRef.current = new DrawingAnnotationManager(state.scene, state.camera);
    }
  };

  return (
    <div className="w-full h-full bg-canvas-bg relative" ref={containerRef}>
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
        {lightManagerRef.current && giManagerRef.current && drawingManagerRef.current && (
          <SceneContent
            wallBuilder={wallBuilder}
            lightManager={lightManagerRef.current}
            giManager={giManagerRef.current}
            materialFactory={materialFactory}
            drawingManager={drawingManagerRef.current}
          />
        )}
        <OrbitControls
          enableDamping
          dampingFactor={0.05}
          minDistance={2}
          maxDistance={30}
          maxPolarAngle={Math.PI / 2 - 0.01}
          enabled={useFloorPlanStore.getState().currentTool !== 'annotation-draw'}
        />
      </Canvas>
    </div>
  );
};
