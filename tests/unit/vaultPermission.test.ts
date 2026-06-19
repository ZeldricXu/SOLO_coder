import * as path from 'path';

jest.mock('chokidar', () => {
  const mockOn = jest.fn().mockReturnThis();
  const mockClose = jest.fn();
  
  return {
    watch: jest.fn(() => ({
      on: mockOn,
      close: mockClose,
    })),
    __mockOn: mockOn,
    __mockClose: mockClose,
  };
});

jest.mock('electron', () => ({
  BrowserWindow: {
    getAllWindows: jest.fn().mockReturnValue([]),
  },
  dialog: {
    showMessageBox: jest.fn().mockResolvedValue({ response: 1 }),
  },
  shell: {
    openExternal: jest.fn(),
  },
}));

jest.mock('../../src/main/db', () => ({
  getDatabase: jest.fn().mockReturnValue({
    prepare: jest.fn().mockReturnThis(),
    run: jest.fn(),
    get: jest.fn(),
    all: jest.fn().mockReturnValue([]),
    exec: jest.fn(),
    pragma: jest.fn(),
    close: jest.fn(),
  }),
}));

jest.mock('../../src/main/db/noteService', () => ({
  NoteService: {
    getAll: jest.fn().mockReturnValue([]),
    getByPath: jest.fn().mockReturnValue(null),
    create: jest.fn().mockReturnValue({ id: '1', path: 'test.md' }),
    update: jest.fn(),
    delete: jest.fn(),
  },
}));

jest.mock('../../src/main/db/linkService', () => ({
  LinkService: {
    clearLinksForNote: jest.fn(),
    addLink: jest.fn(),
    updateTargetIdsForPath: jest.fn(),
    getAll: jest.fn().mockReturnValue([]),
  },
}));

jest.mock('../../src/main/services/searchService', () => ({
  SearchService: {
    init: jest.fn(),
    addNote: jest.fn(),
    updateNote: jest.fn(),
    removeNote: jest.fn(),
  },
}));

describe('Vault Permission - Error Detection', () => {
  let VaultService: any;
  let errorHandler: ((error: Error) => void) | null = null;
  let readyHandler: (() => void) | null = null;

  beforeEach(() => {
    jest.resetModules();
    errorHandler = null;
    readyHandler = null;
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  const setupMocks = () => {
    let errorHandlerRef: ((error: Error) => void) | null = null;
    let readyHandlerRef: (() => void) | null = null;
    
    jest.doMock('chokidar', () => ({
      watch: jest.fn(() => {
        const mockWatcher: any = {
          on: (event: string, handler: any) => {
            if (event === 'error') errorHandlerRef = handler;
            if (event === 'ready') readyHandlerRef = handler;
            return mockWatcher;
          },
          close: jest.fn(),
        };
        return mockWatcher;
      }),
    }));
    
    return {
      getErrorHandler: () => errorHandlerRef,
      getReadyHandler: () => readyHandlerRef,
    };
  };

  it('should detect EACCES as permission error', () => {
    const mocks = setupMocks();
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const error: any = new Error('Permission denied');
    error.code = 'EACCES';
    
    expect(VaultService.isPermissionError(error)).toBe(true);
  });

  it('should detect EPERM as permission error', () => {
    const mocks = setupMocks();
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const error: any = new Error('Operation not permitted');
    error.code = 'EPERM';
    
    expect(VaultService.isPermissionError(error)).toBe(true);
  });

  it('should not detect regular errors as permission errors', () => {
    const mocks = setupMocks();
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const error: any = new Error('File not found');
    error.code = 'ENOENT';
    
    expect(VaultService.isPermissionError(error)).toBe(false);
  });

  it('should handle errors without code property gracefully', () => {
    const mocks = setupMocks();
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const error = new Error('Unknown error');
    
    expect(VaultService.isPermissionError(error)).toBe(false);
  });

  it('should recognize all permission error codes', () => {
    const mocks = setupMocks();
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const codes = ['EACCES', 'EPERM', 'ELOOP', 'ENOSPC', 'EMFILE'];
    
    for (const code of codes) {
      const error: any = new Error('Test error');
      error.code = code;
      expect(VaultService.isPermissionError(error)).toBe(true);
    }
  });
});

describe('Vault Permission - Watcher Status', () => {
  beforeEach(() => {
    jest.resetModules();
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  const setupMocks = () => {
    let errorHandlerRef: ((error: Error) => void) | null = null;
    let readyHandlerRef: (() => void) | null = null;
    
    jest.doMock('chokidar', () => ({
      watch: jest.fn(() => {
        const mockWatcher: any = {
          on: (event: string, handler: any) => {
            if (event === 'error') errorHandlerRef = handler;
            if (event === 'ready') readyHandlerRef = handler;
            return mockWatcher;
          },
          close: jest.fn(),
        };
        return mockWatcher;
      }),
    }));
    
    return {
      getErrorHandler: () => errorHandlerRef,
      getReadyHandler: () => readyHandlerRef,
    };
  };

  it('should return watching=false initially', () => {
    const mocks = setupMocks();
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const status = VaultService.getWatcherStatus();
    expect(status.watching).toBe(false);
    expect(status.error).toBeUndefined();
  });

  it('should report watching=true when ready', () => {
    const mocks = setupMocks();
    const { VaultService } = require('../../src/main/services/vaultService');
    
    VaultService.init('/tmp/test-vault');
    
    const readyHandler = mocks.getReadyHandler();
    expect(readyHandler).toBeDefined();
    readyHandler!();
    
    const status = VaultService.getWatcherStatus();
    expect(status.watching).toBe(true);
    expect(status.error).toBeUndefined();
  });

  it('should report error when watcher errors', () => {
    const mocks = setupMocks();
    const { VaultService } = require('../../src/main/services/vaultService');
    
    VaultService.init('/tmp/test-vault');
    
    const readyHandler = mocks.getReadyHandler();
    readyHandler!();
    
    const errorHandler = mocks.getErrorHandler();
    expect(errorHandler).toBeDefined();
    
    const testError = new Error('Test permission error');
    (testError as any).code = 'EACCES';
    errorHandler!(testError);
    
    const status = VaultService.getWatcherStatus();
    expect(status.watching).toBe(false);
    expect(status.error).toBe('Test permission error');
  });

  it('should reset status on close', () => {
    const mocks = setupMocks();
    const { VaultService } = require('../../src/main/services/vaultService');
    
    VaultService.init('/tmp/test-vault');
    
    const readyHandler = mocks.getReadyHandler();
    readyHandler!();
    
    expect(VaultService.getWatcherStatus().watching).toBe(true);
    
    VaultService.close();
    
    const status = VaultService.getWatcherStatus();
    expect(status.watching).toBe(false);
    expect(status.error).toBeUndefined();
  });

  it('should reset status on re-init', () => {
    const mocks = setupMocks();
    const { VaultService } = require('../../src/main/services/vaultService');
    
    VaultService.init('/tmp/test-vault');
    
    const errorHandler = mocks.getErrorHandler();
    const testError = new Error('Test error');
    (testError as any).code = 'EACCES';
    errorHandler!(testError);
    
    expect(VaultService.getWatcherStatus().error).toBeDefined();
    
    VaultService.init('/tmp/other-vault');
    
    const status = VaultService.getWatcherStatus();
    expect(status.watching).toBe(false);
    expect(status.error).toBeUndefined();
  });
});

describe('Vault Permission - macOS Protected Path Detection', () => {
  const originalPlatform = process.platform;

  beforeEach(() => {
    jest.resetModules();
  });

  afterEach(() => {
    jest.clearAllMocks();
    Object.defineProperty(process, 'platform', { value: originalPlatform });
  });

  const setupMocks = (platform: string, homedir: string) => {
    Object.defineProperty(process, 'platform', { value: platform });
    
    jest.doMock('chokidar', () => ({
      watch: jest.fn(() => ({
        on: jest.fn().mockReturnThis(),
        close: jest.fn(),
      })),
    }));
    
    jest.doMock('os', () => ({
      homedir: jest.fn().mockReturnValue(homedir),
    }));
  };

  it('should return false on non-darwin platforms', () => {
    setupMocks('linux', '/home/user');
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const result = VaultService.isMacOSProtectedPath('/home/user/Documents/vault');
    expect(result).toBe(false);
  });

  it('should detect Documents as protected path on macOS', () => {
    setupMocks('darwin', '/Users/testuser');
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const result = VaultService.isMacOSProtectedPath('/Users/testuser/Documents/my-vault');
    expect(result).toBe(true);
  });

  it('should detect Downloads as protected path on macOS', () => {
    setupMocks('darwin', '/Users/testuser');
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const result = VaultService.isMacOSProtectedPath('/Users/testuser/Downloads/vault');
    expect(result).toBe(true);
  });

  it('should detect Desktop as protected path on macOS', () => {
    setupMocks('darwin', '/Users/testuser');
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const result = VaultService.isMacOSProtectedPath('/Users/testuser/Desktop/notes');
    expect(result).toBe(true);
  });

  it('should detect exact protected directory path', () => {
    setupMocks('darwin', '/Users/testuser');
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const result = VaultService.isMacOSProtectedPath('/Users/testuser/Documents');
    expect(result).toBe(true);
  });

  it('should not detect non-protected paths on macOS', () => {
    setupMocks('darwin', '/Users/testuser');
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const result = VaultService.isMacOSProtectedPath('/Users/testuser/Projects/vault');
    expect(result).toBe(false);
  });

  it('should detect all protected directory types on macOS', () => {
    setupMocks('darwin', '/Users/testuser');
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const protectedDirs = ['Documents', 'Downloads', 'Desktop', 'Movies', 'Music', 'Pictures'];
    
    for (const dir of protectedDirs) {
      const testPath = `/Users/testuser/${dir}/vault`;
      expect(VaultService.isMacOSProtectedPath(testPath)).toBe(true);
    }
  });

  it('should handle path normalization correctly', () => {
    setupMocks('darwin', '/Users/testuser');
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const result = VaultService.isMacOSProtectedPath('/Users/testuser/Documents/../Documents/vault');
    expect(result).toBe(true);
  });
});

describe('Vault Permission - Check Permissions', () => {
  const originalPlatform = process.platform;

  beforeEach(() => {
    jest.resetModules();
  });

  afterEach(() => {
    jest.clearAllMocks();
    Object.defineProperty(process, 'platform', { value: originalPlatform });
  });

  const setupMocks = (platform: string, homedir: string, accessSync: jest.Mock) => {
    Object.defineProperty(process, 'platform', { value: platform });
    
    jest.doMock('chokidar', () => ({
      watch: jest.fn(() => ({
        on: jest.fn().mockReturnThis(),
        close: jest.fn(),
      })),
    }));
    
    jest.doMock('os', () => ({
      homedir: jest.fn().mockReturnValue(homedir),
    }));
    
    jest.doMock('fs', () => ({
      ...jest.requireActual('fs'),
      accessSync: accessSync,
      constants: {
        R_OK: 4,
        W_OK: 2,
      },
    }));
  };

  it('should return accessible=true when fs.accessSync succeeds', () => {
    const mockAccessSync = jest.fn();
    setupMocks('darwin', '/Users/testuser', mockAccessSync);
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const result = VaultService.checkPermissions('/Users/testuser/Projects/vault');
    expect(result.accessible).toBe(true);
    expect(result.isProtectedPath).toBe(false);
    expect(result.error).toBeUndefined();
  });

  it('should return accessible=false when fs.accessSync throws', () => {
    const mockAccessSync = jest.fn().mockImplementation(() => {
      const err: any = new Error('EACCES: permission denied');
      err.code = 'EACCES';
      throw err;
    });
    setupMocks('darwin', '/Users/testuser', mockAccessSync);
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const result = VaultService.checkPermissions('/Users/testuser/Projects/vault');
    expect(result.accessible).toBe(false);
    expect(result.error).toBeDefined();
  });

  it('should correctly identify protected paths in checkPermissions', () => {
    const mockAccessSync = jest.fn();
    setupMocks('darwin', '/Users/testuser', mockAccessSync);
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const result = VaultService.checkPermissions('/Users/testuser/Documents/vault');
    expect(result.accessible).toBe(true);
    expect(result.isProtectedPath).toBe(true);
  });

  it('should work on non-macOS platforms', () => {
    const mockAccessSync = jest.fn();
    setupMocks('linux', '/home/user', mockAccessSync);
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const result = VaultService.checkPermissions('/home/user/Documents/vault');
    expect(result.accessible).toBe(true);
    expect(result.isProtectedPath).toBe(false);
  });
});

describe('Vault Permission - Dialog', () => {
  const originalPlatform = process.platform;

  beforeEach(() => {
    jest.resetModules();
  });

  afterEach(() => {
    jest.clearAllMocks();
    Object.defineProperty(process, 'platform', { value: originalPlatform });
  });

  const setupMocks = () => {
    jest.doMock('chokidar', () => ({
      watch: jest.fn(() => ({
        on: jest.fn().mockReturnThis(),
        close: jest.fn(),
      })),
    }));
  };

  it('should show permission dialog on permission error', async () => {
    setupMocks();
    
    let errorHandler: ((error: Error) => void) | null = null;
    
    jest.doMock('chokidar', () => ({
      watch: jest.fn(() => {
        const mockWatcher: any = {
          on: (event: string, handler: any) => {
            if (event === 'error') errorHandler = handler;
            return mockWatcher;
          },
          close: jest.fn(),
        };
        return mockWatcher;
      }),
    }));
    
    const electron = require('electron');
    const mockWindow = { webContents: { send: jest.fn() } };
    electron.BrowserWindow.getAllWindows.mockReturnValue([mockWindow]);
    
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const showDialogSpy = jest.spyOn(VaultService, 'showPermissionDialog').mockResolvedValue();
    
    VaultService.init('/tmp/test-vault');
    
    const testError: any = new Error('Permission denied');
    testError.code = 'EACCES';
    errorHandler!(testError);
    
    expect(showDialogSpy).toHaveBeenCalled();
  });

  it('should not show dialog for non-permission errors', () => {
    setupMocks();
    
    let errorHandler: ((error: Error) => void) | null = null;
    
    jest.doMock('chokidar', () => ({
      watch: jest.fn(() => {
        const mockWatcher: any = {
          on: (event: string, handler: any) => {
            if (event === 'error') errorHandler = handler;
            return mockWatcher;
          },
          close: jest.fn(),
        };
        return mockWatcher;
      }),
    }));
    
    const electron = require('electron');
    const mockWindow = { webContents: { send: jest.fn() } };
    electron.BrowserWindow.getAllWindows.mockReturnValue([mockWindow]);
    
    const { VaultService } = require('../../src/main/services/vaultService');
    
    const showDialogSpy = jest.spyOn(VaultService, 'showPermissionDialog').mockResolvedValue();
    
    VaultService.init('/tmp/test-vault');
    
    const testError: any = new Error('File not found');
    testError.code = 'ENOENT';
    errorHandler!(testError);
    
    expect(showDialogSpy).not.toHaveBeenCalled();
  });

  it('should open system settings when user clicks open settings button', async () => {
    Object.defineProperty(process, 'platform', { value: 'darwin' });
    
    setupMocks();
    
    const electron = require('electron');
    const mockWindow = { webContents: { send: jest.fn() } };
    electron.BrowserWindow.getAllWindows.mockReturnValue([mockWindow]);
    electron.dialog.showMessageBox.mockResolvedValue({ response: 0 });
    
    const { VaultService } = require('../../src/main/services/vaultService');
    
    await VaultService.showPermissionDialog();
    
    expect(electron.shell.openExternal).toHaveBeenCalled();
    expect(electron.shell.openExternal).toHaveBeenCalledWith(
      'x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles'
    );
  });

  it('should not open settings when user dismisses dialog', async () => {
    Object.defineProperty(process, 'platform', { value: 'darwin' });
    
    setupMocks();
    
    const electron = require('electron');
    const mockWindow = { webContents: { send: jest.fn() } };
    electron.BrowserWindow.getAllWindows.mockReturnValue([mockWindow]);
    electron.dialog.showMessageBox.mockResolvedValue({ response: 1 });
    
    const { VaultService } = require('../../src/main/services/vaultService');
    
    await VaultService.showPermissionDialog();
    
    expect(electron.shell.openExternal).not.toHaveBeenCalled();
  });

  it('should not open settings on non-darwin platforms', async () => {
    Object.defineProperty(process, 'platform', { value: 'linux' });
    
    setupMocks();
    
    const electron = require('electron');
    const mockWindow = { webContents: { send: jest.fn() } };
    electron.BrowserWindow.getAllWindows.mockReturnValue([mockWindow]);
    electron.dialog.showMessageBox.mockResolvedValue({ response: 0 });
    
    const { VaultService } = require('../../src/main/services/vaultService');
    
    await VaultService.showPermissionDialog();
    
    expect(electron.shell.openExternal).not.toHaveBeenCalled();
  });

  it('should not show dialog when no windows exist', async () => {
    setupMocks();
    
    const electron = require('electron');
    electron.BrowserWindow.getAllWindows.mockReturnValue([]);
    
    const { VaultService } = require('../../src/main/services/vaultService');
    
    await VaultService.showPermissionDialog();
    
    expect(electron.dialog.showMessageBox).not.toHaveBeenCalled();
  });
});
