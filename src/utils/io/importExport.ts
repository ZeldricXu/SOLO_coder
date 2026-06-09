import DxfParser from 'dxf-parser';
import type { FloorPlan, Wall, Opening, Room } from '@/types/floorplan';
import type { Point2D } from '@/types/geometry';
import { generateId, detectRoomsFromWalls } from '../geometry';
import { createDefaultFloorPlan } from '@/store/useFloorPlanStore';
import {
  detectAndDecode,
  decodeWithEncoding,
  EncodingDetectionError,
  type EncodingName,
} from './encodingDetector';

export const EXPORT_FORMAT_VERSION = '1.0.0';

export interface ExportMetadata {
  version: string;
  exportedAt: number;
  exportedBy: string;
  software: string;
}

export interface FloorPlanExport {
  metadata: ExportMetadata;
  data: FloorPlan;
}

export const exportToJSON = (floorPlan: FloorPlan): string => {
  const exportData: FloorPlanExport = {
    metadata: {
      version: EXPORT_FORMAT_VERSION,
      exportedAt: Date.now(),
      exportedBy: 'ArchPlan Studio',
      software: 'ArchPlan Studio v1.0.0',
    },
    data: floorPlan,
  };

  return JSON.stringify(exportData, null, 2);
};

export const downloadJSON = (floorPlan: FloorPlan, filename?: string) => {
  const jsonStr = exportToJSON(floorPlan);
  const blob = new Blob([jsonStr], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename || `floorplan_${Date.now()}.json`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
};

export const importFromJSON = (jsonStr: string): FloorPlan => {
  try {
    const parsed = JSON.parse(jsonStr) as FloorPlanExport;

    if (!parsed.metadata || parsed.metadata.version !== EXPORT_FORMAT_VERSION) {
      console.warn(`版本不匹配: 期望 ${EXPORT_FORMAT_VERSION}, 实际 ${parsed.metadata?.version}`);
    }

    if (!parsed.data) {
      throw new Error('无效的户型数据格式');
    }

    const defaultPlan = createDefaultFloorPlan();
    return {
      ...defaultPlan,
      ...parsed.data,
    };
  } catch (error) {
    console.error('JSON解析失败:', error);
    if (error instanceof SyntaxError) {
      throw error;
    }
    throw new Error('户型文件解析失败，请检查文件格式');
  }
};

export interface ImportOptions {
  encoding?: EncodingName;
  onEncodingDetected?: (result: Awaited<ReturnType<typeof detectAndDecode>>) => void;
  onEncodingError?: (error: EncodingDetectionError) => EncodingName | null;
}

export const importFromFile = async (
  file: File | { name: string; text: () => Promise<string>; arrayBuffer?: () => Promise<ArrayBuffer> },
  options: ImportOptions = {}
): Promise<FloorPlan> => {
  if (file.name.endsWith('.json')) {
    const text = await file.text();
    return importFromJSON(text);
  }

  if (file.name.endsWith('.dxf')) {
    if ('arrayBuffer' in file && typeof file.arrayBuffer === 'function') {
      const buffer = await file.arrayBuffer();
      return importFromDXFBuffer(buffer, options);
    }
    const text = await file.text();
    return parseDXF(text);
  }

  throw new Error('不支持的文件格式，请上传 .json 或 .dxf 文件');
};

export const importFromDXFBuffer = async (
  buffer: ArrayBuffer,
  options: ImportOptions = {}
): Promise<FloorPlan> => {
  const detectionResult = await detectAndDecode(buffer, options.encoding);

  if (options.onEncodingDetected) {
    options.onEncodingDetected(detectionResult);
  }

  let text: string;

  if (detectionResult.confidence < 0.3 && options.onEncodingError) {
    const error = new EncodingDetectionError(
      `无法自动检测编码，已尝试 ${detectionResult.tried.join(', ')}`,
      detectionResult.tried,
      buffer
    );
    const manualEncoding = options.onEncodingError(error);
    if (manualEncoding) {
      text = decodeWithEncoding(buffer, manualEncoding);
    } else {
      throw error;
    }
  } else {
    text = decodeWithEncoding(buffer, detectionResult.encoding);
  }

  return parseDXF(text);
};

export const parseDXF = async (dxfContent: string): Promise<FloorPlan> => {
  const parser = new DxfParser();

  try {
    const dxf = parser.parseSync(dxfContent);
    const floorPlan = createDefaultFloorPlan();

    const layers: Record<string, string> = {
      'WALL': 'wall',
      'WALLS': 'wall',
      '墙体': 'wall',
      'DOOR': 'door',
      'DOORS': 'door',
      '门': 'door',
      'WINDOW': 'window',
      'WINDOWS': 'window',
      '窗': 'window',
    };

    const walls: Wall[] = [];
    const openings: Opening[] = [];
    const points: Point2D[] = [];

    if (dxf && dxf.entities) {
      for (const entity of dxf.entities as any[]) {
        const layer = entity.layer?.toUpperCase() || '';
        const layerType = layers[layer] || layers[entity.layer as string] || 'unknown';

        if (entity.type === 'LINE' && layerType === 'wall') {
          const start: Point2D = { x: entity.vertices?.[0]?.x || 0, y: entity.vertices?.[0]?.y || 0 };
          const end: Point2D = { x: entity.vertices?.[1]?.x || 0, y: entity.vertices?.[1]?.y || 0 };

          const scale = 0.001;

          walls.push({
            id: generateId(),
            type: 'straight',
            start: { x: start.x * scale, y: start.y * scale },
            end: { x: end.x * scale, y: end.y * scale },
            thickness: 0.2,
            height: 2.8,
            materialId: 'mat-wall-white',
          });

          points.push(start, end);
        }

        if (entity.type === 'LWPOLYLINE' && layerType === 'wall') {
          const vertices = entity.vertices || [];
          const scale = 0.001;

          for (let i = 0; i < vertices.length - 1; i++) {
            const start: Point2D = { x: vertices[i].x * scale, y: vertices[i].y * scale };
            const end: Point2D = { x: vertices[i + 1].x * scale, y: vertices[i + 1].y * scale };

            walls.push({
              id: generateId(),
              type: 'straight',
              start,
              end,
              thickness: 0.2,
              height: 2.8,
              materialId: 'mat-wall-white',
            });
          }
        }

        if (entity.type === 'INSERT' && (layerType === 'door' || layerType === 'window')) {
          const scale = 0.001;
          const pos: Point2D = { x: (entity.x || 0) * scale, y: (entity.y || 0) * scale };

          openings.push({
            id: generateId(),
            type: layerType as 'door' | 'window',
            wallId: '',
            positionX: 0,
            width: layerType === 'door' ? 0.9 : 1.2,
            height: layerType === 'door' ? 2.1 : 1.5,
            sillHeight: layerType === 'window' ? 0.9 : 0,
            swingAngle: layerType === 'door' ? 90 : 0,
            position: pos,
          });
        }
      }
    }

    if (walls.length > 0) {
      const rooms = detectRoomsFromWalls(walls);
      floorPlan.walls = walls;
      floorPlan.openings = openings;
      floorPlan.rooms = rooms;
    }

    return floorPlan;
  } catch (error) {
    console.error('DXF解析失败:', error);
    throw new Error('DXF文件解析失败，请检查文件格式');
  }
};


export const validateFloorPlan = (floorPlan: FloorPlan): { valid: boolean; errors: string[] } => {
  const errors: string[] = [];

  if (!floorPlan.walls || floorPlan.walls.length === 0) {
    errors.push('户型图中没有墙体');
  }

  for (let i = 0; i < floorPlan.walls.length; i++) {
    const wall = floorPlan.walls[i];
    if (!wall.start || !wall.end) {
      errors.push(`墙体 ${i + 1} 缺少起点或终点`);
    }
    if (wall.thickness <= 0) {
      errors.push(`墙体 ${i + 1} 厚度必须大于0`);
    }
    if (wall.height <= 0) {
      errors.push(`墙体 ${i + 1} 高度必须大于0`);
    }
  }

  for (let i = 0; i < floorPlan.openings.length; i++) {
    const opening = floorPlan.openings[i];
    if (opening.width <= 0) {
      errors.push(`门窗 ${i + 1} 宽度必须大于0`);
    }
    if (opening.height <= 0) {
      errors.push(`门窗 ${i + 1} 高度必须大于0`);
    }
  }

  for (let i = 0; i < floorPlan.furniture.length; i++) {
    const furniture = floorPlan.furniture[i];
    if (!furniture.position) {
      errors.push(`家具 ${i + 1} 缺少位置信息`);
    }
  }

  return {
    valid: errors.length === 0,
    errors,
  };
};

export const exportToSVG = (floorPlan: FloorPlan, width: number = 800, height: number = 600): string => {
  let minX = Infinity,
    minY = Infinity,
    maxX = -Infinity,
    maxY = -Infinity;

  for (const wall of floorPlan.walls) {
    minX = Math.min(minX, wall.start.x, wall.end.x);
    minY = Math.min(minY, wall.start.y, wall.end.y);
    maxX = Math.max(maxX, wall.start.x, wall.end.x);
    maxY = Math.max(maxY, wall.start.y, wall.end.y);
  }

  const padding = 50;
  const scaleX = (width - padding * 2) / (maxX - minX || 1);
  const scaleY = (height - padding * 2) / (maxY - minY || 1);
  const scale = Math.min(scaleX, scaleY);

  const transformX = (x: number) => padding + (x - minX) * scale;
  const transformY = (y: number) => height - padding - (y - minY) * scale;

  let svgContent = `<?xml version="1.0" encoding="UTF-8"?>
<svg width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <style>
      .wall { stroke: #ff6b35; stroke-width: 4; fill: none; }
      .room { fill: rgba(0, 212, 255, 0.1); stroke: #00d4ff; stroke-width: 1; }
      .door { fill: #666; }
      .window { fill: #88ccff; opacity: 0.5; }
      .dimension { fill: #888; font-size: 10px; font-family: sans-serif; }
    </style>
  </defs>
  <rect width="${width}" height="${height}" fill="#1a1f2e"/>
`;

  for (const room of floorPlan.rooms) {
    const points = room.boundary.map((p) => `${transformX(p.x)},${transformY(p.y)}`).join(' ');
    svgContent += `  <polygon class="room" points="${points}"/>\n`;
  }

  for (const wall of floorPlan.walls) {
    const x1 = transformX(wall.start.x);
    const y1 = transformY(wall.start.y);
    const x2 = transformX(wall.end.x);
    const y2 = transformY(wall.end.y);
    svgContent += `  <line class="wall" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}"/>\n`;
  }

  svgContent += `</svg>`;
  return svgContent;
};

export const downloadSVG = (floorPlan: FloorPlan) => {
  const svgStr = exportToSVG(floorPlan);
  const blob = new Blob([svgStr], { type: 'image/svg+xml' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `floorplan_${Date.now()}.svg`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
};
