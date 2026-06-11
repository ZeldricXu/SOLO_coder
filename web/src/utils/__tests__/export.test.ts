import { describe, it, expect, vi, beforeEach } from 'vitest';
import { exportAsPNG, exportAsSVG, exportAsPDF } from '../export';
import type { Shape, Stroke, ExportOptions, Artboard } from '../../types';

describe('Export utilities', () => {
  beforeEach(() => {
    const mockLink = {
      click: vi.fn(),
      download: '',
      href: '',
    };
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      if (tag === 'a') return mockLink as unknown as HTMLAnchorElement;
      if (tag === 'canvas') {
        const canvas = {
          width: 0,
          height: 0,
          getContext: () => ({
            scale: vi.fn(),
            fillStyle: '',
            fillRect: vi.fn(),
            save: vi.fn(),
            strokeStyle: '',
            lineWidth: 0,
            globalAlpha: 1,
            lineCap: 'round',
            lineJoin: 'round',
            setLineDash: vi.fn(),
            beginPath: vi.fn(),
            moveTo: vi.fn(),
            lineTo: vi.fn(),
            stroke: vi.fn(),
            restore: vi.fn(),
            fillStyle_: '',
            translate: vi.fn(),
            rotate: vi.fn(),
            fill: vi.fn(),
            ellipse: vi.fn(),
            rect: vi.fn(),
            closePath: vi.fn(),
            measureText: () => ({ width: 0 }),
            font: '',
            textAlign: 'left' as CanvasTextAlign,
            textBaseline: 'top' as CanvasTextBaseline,
            fillText: vi.fn(),
          }),
          toDataURL: () => 'data:image/png;base64,mock',
        } as unknown as HTMLCanvasElement;
        return canvas;
      }
      return document.createElement(tag);
    });
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock-url');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
  });

  it('exportAsSVG generates valid SVG with star paths', async () => {
    const mockShapes: Shape[] = [
      {
        id: 's1',
        type: 'star',
        x: 100,
        y: 100,
        width: 100,
        height: 100,
        style: { fill: '#ff0000', stroke: '#000000', strokeWidth: 2, opacity: 1 },
        layerId: 'layer-1',
        userId: 'user-1',
        createdAt: Date.now(),
        updatedAt: Date.now(),
        starConfig: {
          outerRadius: 50,
          innerRadius: 20,
          numPoints: 5,
          rotation: 0,
        },
      },
    ];
    const mockStrokes: Stroke[] = [];
    const options: ExportOptions = { format: 'svg', width: 800, height: 600 } as ExportOptions;

    let capturedSvg = '';
    const originalCreateElement = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      if (tag === 'a') {
        return {
          click: vi.fn(),
          set href(v: string) {
            if (v.startsWith('blob:')) {
              const xhr = new XMLHttpRequest();
              xhr.open('GET', v, false);
              xhr.responseType = 'blob';
              xhr.onload = () => {
                const reader = new FileReader();
                reader.onload = () => {
                  capturedSvg = reader.result as string;
                };
                reader.readAsText(xhr.response as Blob);
              };
            }
          },
          get href() { return ''; },
          download: '',
        } as unknown as HTMLAnchorElement;
      }
      return originalCreateElement(tag);
    });

    const mockBlob = new Blob(['<svg>test</svg>'], { type: 'image/svg+xml' });
    vi.spyOn(global, 'Blob').mockImplementation(((content: BlobPart[], opts: BlobPropertyBag) => {
      if (opts.type === 'image/svg+xml') {
        capturedSvg = content.join('');
      }
      return mockBlob;
    }) as any);

    await exportAsSVG(mockStrokes, mockShapes, options);

    expect(capturedSvg).toContain('<svg');
    expect(capturedSvg).toContain('</svg>');
    expect(capturedSvg).toContain('<polygon');
    expect(capturedSvg).toContain('points=');
  });

  it('exportAsSVG generates arrow with head style', async () => {
    const mockShapes: Shape[] = [
      {
        id: 'a1',
        type: 'arrow',
        x: 0,
        y: 0,
        width: 100,
        height: 100,
        style: { stroke: '#333', strokeWidth: 2, opacity: 1 },
        layerId: 'layer-1',
        userId: 'user-1',
        createdAt: Date.now(),
        updatedAt: Date.now(),
        arrowConfig: {
          headStyle: 'triangle',
          tailStyle: 'none',
          headSize: 10,
          tailSize: 0,
        },
      },
    ];
    const mockStrokes: Stroke[] = [];
    const options: ExportOptions = { format: 'svg' } as ExportOptions;

    let capturedSvg = '';
    vi.spyOn(global, 'Blob').mockImplementation(((content: BlobPart[], opts: BlobPropertyBag) => {
      if (opts.type === 'image/svg+xml') {
        capturedSvg = content.join('');
      }
      return new Blob(['test']);
    }) as any);

    await exportAsSVG(mockStrokes, mockShapes, options);

    expect(capturedSvg).toContain('<line');
    expect(capturedSvg).toContain('<polygon');
  });

  it('exportAsPDF supports multiple pages', async () => {
    const mockSave = vi.fn();
    vi.mock('jspdf', () => ({
      default: vi.fn().mockImplementation(() => ({
        addPage: vi.fn(),
        addImage: vi.fn(),
        save: mockSave,
      })),
    }));

    const pages: Artboard[] = [
      { id: 'p1', name: 'Page 1', x: 0, y: 0, width: 800, height: 600, objectIds: ['s1'] },
      { id: 'p2', name: 'Page 2', x: 0, y: 600, width: 800, height: 600, objectIds: ['s2'] },
    ];
    const options: ExportOptions = { format: 'pdf', pages } as ExportOptions;

    const originalCreateElement = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      if (tag === 'canvas') {
        return {
          width: 100,
          height: 100,
          getContext: () => ({
            scale: vi.fn(),
            fillStyle: '',
            fillRect: vi.fn(),
            save: vi.fn(),
            strokeStyle: '',
            lineWidth: 0,
            globalAlpha: 1,
            lineCap: 'round',
            lineJoin: 'round',
            setLineDash: vi.fn(),
            beginPath: vi.fn(),
            moveTo: vi.fn(),
            lineTo: vi.fn(),
            stroke: vi.fn(),
            restore: vi.fn(),
            translate: vi.fn(),
            rotate: vi.fn(),
            fill: vi.fn(),
            ellipse: vi.fn(),
            rect: vi.fn(),
            closePath: vi.fn(),
          }),
          toDataURL: () => 'data:image/png;base64,mock',
        } as unknown as HTMLCanvasElement;
      }
      return originalCreateElement(tag);
    });

    await expect(exportAsPDF([], [], options)).resolves.not.toThrow();
  });

  it('exportAsPNG returns a promise', () => {
    const options: ExportOptions = { format: 'png', width: 100, height: 100 } as ExportOptions;
    const result = exportAsPNG([], [], options);
    expect(result).toBeInstanceOf(Promise);
  });
});
