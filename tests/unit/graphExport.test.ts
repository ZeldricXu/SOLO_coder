import { ExportService } from '@main/services/exportService';
import { dialog, BrowserWindow } from 'electron';
import fs from 'fs';

jest.mock('electron', () => ({
  dialog: {
    showSaveDialog: jest.fn(),
  },
  BrowserWindow: jest.fn(),
  app: {
    getPath: jest.fn().mockReturnValue('/mock/path'),
  },
}));

jest.mock('fs', () => ({
  writeFileSync: jest.fn(),
  existsSync: jest.fn().mockReturnValue(true),
  mkdirSync: jest.fn(),
  readFileSync: jest.fn(),
}));

jest.mock('@main/db/noteService', () => ({
  NoteService: {
    getById: jest.fn(),
  },
}));

jest.mock('@main/db/linkService', () => ({
  LinkService: {
    getGraphData: jest.fn().mockReturnValue({ nodes: [], edges: [] }),
  },
}));

describe('Graph Export - PNG Export', () => {
  const mockSvgData = `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600">
    <circle cx="100" cy="100" r="50" fill="red" />
    <line x1="0" y1="0" x2="200" y2="200" stroke="blue" />
  </svg>`;

  const mockFilePath = '/test/path/graph.png';

  let mockWebContents: any;
  let mockBrowserWindow: any;

  beforeEach(() => {
    jest.clearAllMocks();

    mockWebContents = {
      executeJavaScript: jest.fn().mockResolvedValue('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjYBSMglIQAA'),
    };

    mockBrowserWindow = {
      loadURL: jest.fn().mockResolvedValue(undefined),
      webContents: mockWebContents,
      close: jest.fn(),
    };

    (BrowserWindow as jest.Mock).mockImplementation(() => mockBrowserWindow);
  });

  it('should return empty string when dialog is canceled', async () => {
    (dialog.showSaveDialog as jest.Mock).mockResolvedValue({
      canceled: true,
      filePath: undefined,
    });

    const result = await ExportService.exportGraphPNG(mockSvgData);

    expect(result).toBe('');
    expect(dialog.showSaveDialog).toHaveBeenCalledWith(
      expect.objectContaining({
        title: '导出图谱为 PNG',
        defaultPath: 'knowledge-graph.png',
      })
    );
  });

  it('should create BrowserWindow with correct options', async () => {
    (dialog.showSaveDialog as jest.Mock).mockResolvedValue({
      canceled: false,
      filePath: mockFilePath,
    });

    await ExportService.exportGraphPNG(mockSvgData);

    expect(BrowserWindow).toHaveBeenCalledWith(
      expect.objectContaining({
        width: 1200,
        height: 800,
        show: false,
        webPreferences: expect.objectContaining({
          offscreen: true,
        }),
      })
    );
  });

  it('should load SVG data in the browser window', async () => {
    (dialog.showSaveDialog as jest.Mock).mockResolvedValue({
      canceled: false,
      filePath: mockFilePath,
    });

    await ExportService.exportGraphPNG(mockSvgData);

    expect(mockBrowserWindow.loadURL).toHaveBeenCalled();
    const loadUrlArg = (mockBrowserWindow.loadURL as jest.Mock).mock.calls[0][0];
    expect(loadUrlArg).toContain('data:text/html');
    expect(loadUrlArg).toContain(encodeURIComponent(mockSvgData));
  });

  it('should use executeJavaScript instead of capturePage for DPI consistency', async () => {
    (dialog.showSaveDialog as jest.Mock).mockResolvedValue({
      canceled: false,
      filePath: mockFilePath,
    });

    await ExportService.exportGraphPNG(mockSvgData);

    expect(mockWebContents.executeJavaScript).toHaveBeenCalled();
    const jsCode = (mockWebContents.executeJavaScript as jest.Mock).mock.calls[0][0];

    expect(jsCode).toContain('canvas');
    expect(jsCode).toContain('getContext');
    expect(jsCode).toContain('drawImage');
    expect(jsCode).toContain('toDataURL');
    expect(jsCode).toContain('Image');
    expect(jsCode).toContain('XMLSerializer');
    expect(jsCode).not.toContain('capturePage');
  });

  it('should handle SVG to canvas rendering logic', async () => {
    (dialog.showSaveDialog as jest.Mock).mockResolvedValue({
      canceled: false,
      filePath: mockFilePath,
    });

    await ExportService.exportGraphPNG(mockSvgData);

    const jsCode = (mockWebContents.executeJavaScript as jest.Mock).mock.calls[0][0];

    expect(jsCode).toContain('svg-container');
    expect(jsCode).toContain('querySelector(\'svg\')');
    expect(jsCode).toContain('getBoundingClientRect');
    expect(jsCode).toContain('Blob');
    expect(jsCode).toContain('createObjectURL');
  });

  it('should write PNG file with base64 decoded data', async () => {
    (dialog.showSaveDialog as jest.Mock).mockResolvedValue({
      canceled: false,
      filePath: mockFilePath,
    });

    await ExportService.exportGraphPNG(mockSvgData);

    expect(fs.writeFileSync).toHaveBeenCalledWith(
      mockFilePath,
      expect.any(Buffer)
    );
  });

  it('should close the browser window after export', async () => {
    (dialog.showSaveDialog as jest.Mock).mockResolvedValue({
      canceled: false,
      filePath: mockFilePath,
    });

    await ExportService.exportGraphPNG(mockSvgData);

    expect(mockBrowserWindow.close).toHaveBeenCalled();
  });

  it('should pass SVG data correctly through the export flow', async () => {
    (dialog.showSaveDialog as jest.Mock).mockResolvedValue({
      canceled: false,
      filePath: mockFilePath,
    });

    const testSvg = '<svg width="400" height="300"><rect width="100" height="100" fill="green"/></svg>';

    await ExportService.exportGraphPNG(testSvg);

    expect(mockBrowserWindow.loadURL).toHaveBeenCalled();
    const loadUrlArg = (mockBrowserWindow.loadURL as jest.Mock).mock.calls[0][0];
    expect(loadUrlArg).toContain(encodeURIComponent(testSvg));
  });

  it('should return filePath on successful export', async () => {
    (dialog.showSaveDialog as jest.Mock).mockResolvedValue({
      canceled: false,
      filePath: mockFilePath,
    });

    const result = await ExportService.exportGraphPNG(mockSvgData);

    expect(result).toBe(mockFilePath);
  });
});

describe('Graph Export - DPI Scaling Handling', () => {
  const mockSvgData = '<svg width="800" height="600"></svg>';
  const mockFilePath = '/test/dpi-test.png';

  let mockWebContents: any;
  let mockBrowserWindow: any;

  beforeEach(() => {
    jest.clearAllMocks();

    mockWebContents = {
      executeJavaScript: jest.fn().mockResolvedValue('data:image/png;base64,test'),
    };

    mockBrowserWindow = {
      loadURL: jest.fn().mockResolvedValue(undefined),
      webContents: mockWebContents,
      close: jest.fn(),
    };

    (BrowserWindow as jest.Mock).mockImplementation(() => mockBrowserWindow);
    (dialog.showSaveDialog as jest.Mock).mockResolvedValue({
      canceled: false,
      filePath: mockFilePath,
    });
  });

  it('should render SVG at actual size independent of devicePixelRatio', async () => {
    await ExportService.exportGraphPNG(mockSvgData);

    const jsCode = (mockWebContents.executeJavaScript as jest.Mock).mock.calls[0][0];

    expect(jsCode).toContain('getBoundingClientRect()');
    expect(jsCode).toContain('canvas.width = width');
    expect(jsCode).toContain('canvas.height = height');
    expect(jsCode).toContain('ctx.drawImage(img, 0, 0, width, height)');
  });

  it('should use white background fill', async () => {
    await ExportService.exportGraphPNG(mockSvgData);

    const jsCode = (mockWebContents.executeJavaScript as jest.Mock).mock.calls[0][0];

    expect(jsCode).toContain('fillStyle = \'white\'');
    expect(jsCode).toContain('fillRect(0, 0, width, height)');
  });

  it('should handle promise-based async rendering', async () => {
    await ExportService.exportGraphPNG(mockSvgData);

    const jsCode = (mockWebContents.executeJavaScript as jest.Mock).mock.calls[0][0];

    expect(jsCode).toContain('new Promise');
    expect(jsCode).toContain('resolve');
    expect(jsCode).toContain('reject');
  });

  it('should clean up object URL after rendering', async () => {
    await ExportService.exportGraphPNG(mockSvgData);

    const jsCode = (mockWebContents.executeJavaScript as jest.Mock).mock.calls[0][0];

    expect(jsCode).toContain('URL.revokeObjectURL');
  });
});

describe('Graph Export - Error Handling', () => {
  const mockSvgData = '<svg width="800" height="600"></svg>';

  let mockWebContents: any;
  let mockBrowserWindow: any;

  beforeEach(() => {
    jest.clearAllMocks();

    mockWebContents = {
      executeJavaScript: jest.fn(),
    };

    mockBrowserWindow = {
      loadURL: jest.fn().mockResolvedValue(undefined),
      webContents: mockWebContents,
      close: jest.fn(),
    };

    (BrowserWindow as jest.Mock).mockImplementation(() => mockBrowserWindow);
    (dialog.showSaveDialog as jest.Mock).mockResolvedValue({
      canceled: false,
      filePath: '/test/error.png',
    });
  });

  it('should throw when canvas context is not available', async () => {
    mockWebContents.executeJavaScript.mockRejectedValue(new Error('Canvas context not available'));

    await expect(ExportService.exportGraphPNG(mockSvgData)).rejects.toThrow('Canvas context not available');

    expect(mockBrowserWindow.close).not.toHaveBeenCalled();
  });

  it('should throw when SVG element not found', async () => {
    mockWebContents.executeJavaScript.mockRejectedValue(new Error('SVG element not found'));

    await expect(ExportService.exportGraphPNG(mockSvgData)).rejects.toThrow('SVG element not found');
  });

  it('should throw when image loading fails', async () => {
    mockWebContents.executeJavaScript.mockRejectedValue(new Error('Failed to load SVG image'));

    await expect(ExportService.exportGraphPNG(mockSvgData)).rejects.toThrow('Failed to load SVG image');
  });
});
