import { useRef, useEffect, useState } from 'react';
import { Stage, Layer, Line, Group, Text, RegularPolygon } from 'react-konva';
import type Konva from 'konva';
import {
  HexGrid,
  cubeToPixel,
  cubeKey,
  cubeToOffset,
  terrainRegistry,
  generateId,
  cubeEquals,
} from '@tactics/core';
import type { CubeCoords, HexTile, CombatUnit, Direction } from '@tactics/core';
import {
  getHexCornerPoints,
  pixelToHex,
  darkenColor,
  getFactionColor,
} from '../utils/hexRender';
import type { EditorTool, UnitTemplate } from '../types/editor';

interface HexMapCanvasProps {
  grid: HexGrid;
  units: Map<string, CombatUnit>;
  currentTool: EditorTool;
  currentTerrain: string;
  selectedTile: CubeCoords | null;
  selectedUnitTemplate: UnitTemplate | null;
  brushSize: number;
  zoom: number;
  panX: number;
  panY: number;
  setZoom: (zoom: number) => void;
  setPan: (x: number, y: number) => void;
  setSelectedTile: (tile: CubeCoords | null) => void;
  saveSnapshot: () => void;
  onUnitsChange: (units: Map<string, CombatUnit>) => void;
  onGridChange: () => void;
}

export function HexMapCanvas({
  grid,
  units,
  currentTool,
  currentTerrain,
  selectedTile,
  selectedUnitTemplate,
  brushSize,
  zoom,
  panX,
  panY,
  setZoom,
  setPan,
  setSelectedTile,
  saveSnapshot,
  onUnitsChange,
  onGridChange,
}: HexMapCanvasProps) {
  const stageRef = useRef<Konva.Stage>(null);
  const dragStartRef = useRef<{ x: number; y: number; panX: number; panY: number } | null>(null);
  const isPanningRef = useRef(false);
  const [isPainting, setIsPainting] = useState(false);
  const lastPaintedRef = useRef<Set<string>>(new Set());

  const config = grid.getConfig();
  const tileSize = config.tileSize || 40;
  const orientation = config.orientation || 'pointy';

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'z' && (e.ctrlKey || e.metaKey)) {
        if (e.shiftKey) {
        } else {
        }
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  const handleWheel = (e: Konva.KonvaEventObject<WheelEvent>) => {
    e.evt.preventDefault();
    const scaleBy = 1.1;
    const stage = stageRef.current;
    if (!stage) return;

    const oldScale = zoom;
    const pointer = stage.getPointerPosition();
    if (!pointer) return;

    const mousePointTo = {
      x: (pointer.x - panX) / oldScale,
      y: (pointer.y - panY) / oldScale,
    };

    const direction = e.evt.deltaY > 0 ? -1 : 1;
    const newScale = direction > 0 ? oldScale * scaleBy : oldScale / scaleBy;
    const clampedScale = Math.max(0.2, Math.min(5, newScale));

    setZoom(clampedScale);
    setPan(
      pointer.x - mousePointTo.x * clampedScale,
      pointer.y - mousePointTo.y * clampedScale
    );
  };

  const handleMouseDown = (e: Konva.KonvaEventObject<MouseEvent>) => {
    const stage = stageRef.current;
    if (!stage) return;

    if (e.evt.button === 1 || (e.evt.button === 0 && e.evt.altKey)) {
      isPanningRef.current = true;
      dragStartRef.current = {
        x: e.evt.clientX,
        y: e.evt.clientY,
        panX,
        panY,
      };
      stage.container().style.cursor = 'grabbing';
      return;
    }

    if (currentTool === 'select') {
      const pos = stage.getPointerPosition();
      if (!pos) return;
      const coords = pixelToHex(pos.x, pos.y, tileSize, panX, panY, zoom, orientation);
      const tile = grid.getTile(coords);
      if (tile) {
        setSelectedTile(coords);
      } else {
        setSelectedTile(null);
      }
    } else if (currentTool === 'brush' || currentTool === 'eraser') {
      setIsPainting(true);
      lastPaintedRef.current.clear();
      saveSnapshot();
      paintAtPointer();
    } else if (currentTool === 'placeUnit' && selectedUnitTemplate) {
      const pos = stage.getPointerPosition();
      if (!pos) return;
      const coords = pixelToHex(pos.x, pos.y, tileSize, panX, panY, zoom, orientation);
      const tile = grid.getTile(coords);
      if (tile) {
        placeUnitAt(coords);
      }
    }
  };

  const handleMouseMove = (e: Konva.KonvaEventObject<MouseEvent>) => {
    if (isPanningRef.current && dragStartRef.current) {
      const dx = e.evt.clientX - dragStartRef.current.x;
      const dy = e.evt.clientY - dragStartRef.current.y;
      setPan(dragStartRef.current.panX + dx, dragStartRef.current.panY + dy);
      return;
    }

    if (isPainting) {
      paintAtPointer();
    }
  };

  const handleMouseUp = () => {
    if (isPanningRef.current) {
      isPanningRef.current = false;
      dragStartRef.current = null;
      if (stageRef.current) {
        stageRef.current.container().style.cursor = 'default';
      }
    }
    if (isPainting) {
      setIsPainting(false);
      lastPaintedRef.current.clear();
    }
  };

  const paintAtPointer = () => {
    const stage = stageRef.current;
    if (!stage) return;
    const pos = stage.getPointerPosition();
    if (!pos) return;

    const centerCoords = pixelToHex(pos.x, pos.y, tileSize, panX, panY, zoom, orientation);

    for (let dq = -(brushSize - 1); dq <= brushSize - 1; dq++) {
      for (let dr = -(brushSize - 1); dr <= brushSize - 1; dr++) {
        if (Math.abs(dq + dr) > brushSize - 1) continue;
        const coords: CubeCoords = {
          q: centerCoords.q + dq,
          r: centerCoords.r + dr,
          s: -(centerCoords.q + dq) - (centerCoords.r + dr),
        };
        const key = cubeKey(coords);
        if (lastPaintedRef.current.has(key)) continue;

        const tile = grid.getTile(coords);
        if (tile) {
          lastPaintedRef.current.add(key);
          if (currentTool === 'brush') {
            grid.setTileTerrain(coords, currentTerrain as typeof tile.terrain);
          } else if (currentTool === 'eraser') {
            grid.setTileTerrain(coords, 'plain');
            tile.units = [];
            tile.objects = [];
          }
        }
      }
    }
    onGridChange();
  };

  const placeUnitAt = (coords: CubeCoords) => {
    if (!selectedUnitTemplate) return;
    const tile = grid.getTile(coords);
    if (!tile) return;

    const terrainConfig = terrainRegistry.get(tile.terrain);
    if (terrainConfig.blocksMovement) return;

    saveSnapshot();
    const unitId = generateId();
    const defaultStats = {
      maxHp: 100,
      hp: 100,
      maxMp: 50,
      mp: 50,
      attack: 30,
      defense: 20,
      magicAttack: 25,
      magicDefense: 15,
      speed: 5,
      accuracy: 80,
      evasion: 10,
      critRate: 10,
      critDamage: 150,
      armorPenetration: 0,
      moveRange: 4,
      attackRange: 1,
      visionRange: 6,
      height: 1,
    };

    const mergedStats = { ...defaultStats, ...selectedUnitTemplate.baseStats };
    const createAttributes = (value: number) => ({
      base: value,
      modifiers: [],
      current: value,
    });

    const unit: CombatUnit = {
      id: unitId,
      name: selectedUnitTemplate.name,
      faction: selectedUnitTemplate.faction,
      templateId: selectedUnitTemplate.id,
      coords: { ...coords },
      direction: 0 as Direction,
      stats: mergedStats,
      attributes: {
        hp: { current: mergedStats.hp, max: mergedStats.maxHp },
        mp: { current: mergedStats.mp, max: mergedStats.maxMp },
        attack: createAttributes(mergedStats.attack),
        defense: createAttributes(mergedStats.defense),
        magicAttack: createAttributes(mergedStats.magicAttack),
        magicDefense: createAttributes(mergedStats.magicDefense),
        speed: createAttributes(mergedStats.speed),
        accuracy: createAttributes(mergedStats.accuracy),
        evasion: createAttributes(mergedStats.evasion),
        critRate: createAttributes(mergedStats.critRate),
        critDamage: createAttributes(mergedStats.critDamage),
        armorPenetration: createAttributes(mergedStats.armorPenetration),
        moveRange: createAttributes(mergedStats.moveRange),
        attackRange: createAttributes(mergedStats.attackRange),
        visionRange: createAttributes(mergedStats.visionRange),
      },
      skills: [],
      passiveSkills: [],
      statusEffects: [],
      resistances: [],
      affinities: [],
      equipment: [],
      isAlive: true,
      hasActed: false,
      hasMoved: false,
      isDelaying: false,
      tags: [],
    };

    const newUnits = new Map(units);
    newUnits.set(unitId, unit);
    grid.addUnit(coords, unitId);
    onUnitsChange(newUnits);
    onGridChange();
  };

  const renderTile = (tile: HexTile) => {
    const { x, y } = cubeToPixel(tile.coords, tileSize, orientation);
    const terrainConfig = terrainRegistry.get(tile.terrain);
    const isSelected = selectedTile && cubeEquals(tile.coords, selectedTile);
    const key = cubeKey(tile.coords);

    const points = getHexCornerPoints(x, y, tileSize - 1, orientation);
    const flatPoints: number[] = [];
    for (const p of points) {
      flatPoints.push(p.x, p.y);
    }

    return (
      <Group key={key}>
        <Line
          points={flatPoints}
          closed
          fill={terrainConfig.color}
          stroke={darkenColor(terrainConfig.color, 0.3)}
          strokeWidth={1}
          listening
        />
        {tile.height > 0 && (
          <RegularPolygon
            x={x}
            y={y + 2}
            sides={6}
            radius={tileSize - 4}
            fill={darkenColor(terrainConfig.color, 0.1 * tile.height)}
            opacity={0.5}
            listening={false}
          />
        )}
        {isSelected && (
          <Line
            points={flatPoints}
            closed
            stroke="#FFEB3B"
            strokeWidth={3}
            listening={false}
          />
        )}
        {tile.units.length > 0 && (
          <Group listening={false}>
            {tile.units.map((unitId, idx) => {
              const unit = units.get(unitId);
              if (!unit) return null;
              const factionColor = getFactionColor(unit.faction);
              const offsetX = (idx % 2 === 0 ? -1 : 1) * (tileSize * 0.15);
              const offsetY = Math.floor(idx / 2) * (tileSize * 0.2);
              return (
                <Group key={unitId}>
                  <RegularPolygon
                    x={x + offsetX}
                    y={y + offsetY - tileSize * 0.1}
                    sides={6}
                    radius={tileSize * 0.3}
                    fill={factionColor}
                    stroke={darkenColor(factionColor, 0.3)}
                    strokeWidth={2}
                  />
                  <Text
                    x={x + offsetX}
                    y={y + offsetY - tileSize * 0.1}
                    text={unit.name.charAt(0)}
                    fontSize={tileSize * 0.25}
                    fill="#fff"
                    align="center"
                    verticalAlign="middle"
                    offsetX={tileSize * 0.12}
                    offsetY={tileSize * 0.12}
                  />
                </Group>
              );
            })}
          </Group>
        )}
        {tile.objects.length > 0 && (
          <Group listening={false}>
            {tile.objects.map((objId, idx) => (
              <RegularPolygon
                key={objId}
                x={x + (idx - tile.objects.length / 2) * tileSize * 0.2}
                y={y - tileSize * 0.35}
                sides={4}
                radius={tileSize * 0.15}
                rotation={45}
                fill="#795548"
                stroke="#5D4037"
                strokeWidth={1}
              />
            ))}
          </Group>
        )}
        {tile.height !== 0 && (
          <Text
            x={x}
            y={y + tileSize * 0.3}
            text={tile.height > 0 ? `+${tile.height}` : `${tile.height}`}
            fontSize={tileSize * 0.2}
            fill="#333"
            align="center"
            verticalAlign="middle"
            offsetX={tileSize * 0.1}
            listening={false}
          />
        )}
      </Group>
    );
  };

  const tiles = grid.getAllTiles();

  return (
    <Stage
      ref={stageRef}
      width={window.innerWidth}
      height={window.innerHeight}
      onWheel={handleWheel}
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseUp}
      onTouchStart={handleMouseDown as any}
      onTouchMove={handleMouseMove as any}
      onTouchEnd={handleMouseUp}
      draggable={false}
      style={{ cursor: currentTool === 'select' ? 'pointer' : 'crosshair' }}
    >
      <Layer
        scaleX={zoom}
        scaleY={zoom}
        x={panX}
        y={panY}
      >
        {tiles.map(renderTile)}
        {selectedTile && (() => {
          const offset = cubeToOffset(selectedTile, orientation);
          const { x, y } = cubeToPixel(selectedTile, tileSize, orientation);
          return (
            <Text
              x={x}
              y={y + tileSize * 0.7}
              text={`(${offset.col},${offset.row}) [${selectedTile.q},${selectedTile.r},${selectedTile.s}]`}
              fontSize={11}
              fill="#333"
              align="center"
              offsetX={30}
              listening={false}
            />
          );
        })()}
      </Layer>
    </Stage>
  );
}
