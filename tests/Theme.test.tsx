import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import React from 'react';
import { ThemeProvider, useTheme, useTokens, lightTokens, darkTokens } from '../src/theme';
import { Button } from '../src/components/Button';

const injectCSSVariables = (mode: 'light' | 'dark') => {
  const tokens = mode === 'light' ? lightTokens : darkTokens;
  const root = document.documentElement;

  root.style.setProperty('--color-primary', tokens.colors.primary);
  root.style.setProperty('--color-secondary', tokens.colors.secondary);
  root.style.setProperty('--color-success', tokens.colors.success);
  root.style.setProperty('--color-warning', tokens.colors.warning);
  root.style.setProperty('--color-danger', tokens.colors.danger);
  root.style.setProperty('--color-background', tokens.colors.background);
  root.style.setProperty('--color-surface', tokens.colors.surface);
  root.style.setProperty('--color-border', tokens.colors.border);
  root.style.setProperty('--color-text-primary', tokens.colors.textPrimary);
  root.style.setProperty('--color-text-secondary', tokens.colors.textSecondary);
  root.style.setProperty('--color-text-disabled', tokens.colors.textDisabled);
};

const getCSSVariableValue = (variable: string, element: HTMLElement = document.documentElement): string => {
  return getComputedStyle(element).getPropertyValue(variable).trim();
};

describe('ThemeProvider', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');

    const root = document.documentElement;
    const styles = root.style;
    for (let i = styles.length - 1; i >= 0; i--) {
      const prop = styles[i];
      if (prop.startsWith('--color-')) {
        root.style.removeProperty(prop);
      }
    }

    vi.clearAllMocks();
  });

  afterEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  describe('初始化', () => {
    it('默认使用light主题（localStorage为空且无系统偏好）', () => {
      vi.spyOn(window, 'matchMedia').mockImplementation(
        (query) =>
          ({
            matches: false,
            media: query,
            onchange: null,
            addListener: vi.fn(),
            removeListener: vi.fn(),
            addEventListener: vi.fn(),
            removeEventListener: vi.fn(),
            dispatchEvent: vi.fn(),
          }) as MediaQueryList,
      );

      render(
        <ThemeProvider>
          <div data-testid="child">测试</div>
        </ThemeProvider>,
      );

      expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    });

    it('可以指定defaultMode为dark', () => {
      render(
        <ThemeProvider defaultMode="dark">
          <div data-testid="child">测试</div>
        </ThemeProvider>,
      );

      expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    });

    it('从localStorage读取已保存的主题', () => {
      localStorage.setItem('df1-57-theme', 'dark');

      render(
        <ThemeProvider>
          <div data-testid="child">测试</div>
        </ThemeProvider>,
      );

      expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    });

    it('根据系统偏好设置主题（dark）', () => {
      vi.spyOn(window, 'matchMedia').mockImplementation(
        (query) =>
          ({
            matches: query === '(prefers-color-scheme: dark)',
            media: query,
            onchange: null,
            addListener: vi.fn(),
            removeListener: vi.fn(),
            addEventListener: vi.fn(),
            removeEventListener: vi.fn(),
            dispatchEvent: vi.fn(),
          }) as MediaQueryList,
      );

      render(
        <ThemeProvider>
          <div data-testid="child">测试</div>
        </ThemeProvider>,
      );

      expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    });
  });

  describe('主题切换', () => {
    it('setTheme切换到dark后，body上的data-theme属性变更', () => {
      let themeApi: ReturnType<typeof useTheme> | null = null;

      const TestComponent = () => {
        themeApi = useTheme();
        return <div data-testid="test">当前主题：{themeApi.mode}</div>;
      };

      vi.spyOn(window, 'matchMedia').mockImplementation(
        (query) =>
          ({
            matches: false,
            media: query,
            onchange: null,
            addListener: vi.fn(),
            removeListener: vi.fn(),
            addEventListener: vi.fn(),
            removeEventListener: vi.fn(),
            dispatchEvent: vi.fn(),
          }) as MediaQueryList,
      );

      render(
        <ThemeProvider>
          <TestComponent />
        </ThemeProvider>,
      );

      expect(document.documentElement.getAttribute('data-theme')).toBe('light');
      expect(screen.getByTestId('test')).toHaveTextContent('当前主题：light');

      act(() => {
        themeApi!.setMode('dark');
      });

      expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
      expect(screen.getByTestId('test')).toHaveTextContent('当前主题：dark');
    });

    it('toggleMode在light和dark之间切换', () => {
      let themeApi: ReturnType<typeof useTheme> | null = null;

      const TestComponent = () => {
        themeApi = useTheme();
        return null;
      };

      vi.spyOn(window, 'matchMedia').mockImplementation(
        (query) =>
          ({
            matches: false,
            media: query,
            onchange: null,
            addListener: vi.fn(),
            removeListener: vi.fn(),
            addEventListener: vi.fn(),
            removeEventListener: vi.fn(),
            dispatchEvent: vi.fn(),
          }) as MediaQueryList,
      );

      render(
        <ThemeProvider>
          <TestComponent />
        </ThemeProvider>,
      );

      expect(themeApi!.mode).toBe('light');

      act(() => {
        themeApi!.toggleMode();
      });
      expect(themeApi!.mode).toBe('dark');
      expect(document.documentElement.getAttribute('data-theme')).toBe('dark');

      act(() => {
        themeApi!.toggleMode();
      });
      expect(themeApi!.mode).toBe('light');
      expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    });

    it('主题切换后localStorage保存主题偏好', () => {
      let themeApi: ReturnType<typeof useTheme> | null = null;

      const TestComponent = () => {
        themeApi = useTheme();
        return null;
      };

      vi.spyOn(window, 'matchMedia').mockImplementation(
        (query) =>
          ({
            matches: false,
            media: query,
            onchange: null,
            addListener: vi.fn(),
            removeListener: vi.fn(),
            addEventListener: vi.fn(),
            removeEventListener: vi.fn(),
            dispatchEvent: vi.fn(),
          }) as MediaQueryList,
      );

      render(
        <ThemeProvider>
          <TestComponent />
        </ThemeProvider>,
      );

      act(() => {
        themeApi!.setMode('dark');
      });

      expect(localStorage.getItem('df1-57-theme')).toBe('dark');
    });
  });

  describe('CSS变量切换', () => {
    it('切换到dark主题后，CSS变量值更新为暗色主题值', () => {
      let themeApi: ReturnType<typeof useTheme> | null = null;

      const TestComponent = () => {
        themeApi = useTheme();
        const { mode, setMode } = useTheme();

        React.useEffect(() => {
          injectCSSVariables(mode);
        }, [mode]);

        return (
          <div>
            <Button onClick={() => setMode('dark')}>切换到暗黑模式</Button>
            <div data-testid="color-indicator" style={{ color: 'var(--color-primary)' }}>
              颜色测试
            </div>
          </div>
        );
      };

      vi.spyOn(window, 'matchMedia').mockImplementation(
        (query) =>
          ({
            matches: false,
            media: query,
            onchange: null,
            addListener: vi.fn(),
            removeListener: vi.fn(),
            addEventListener: vi.fn(),
            removeEventListener: vi.fn(),
            dispatchEvent: vi.fn(),
          }) as MediaQueryList,
      );

      render(
        <ThemeProvider>
          <TestComponent />
        </ThemeProvider>,
      );

      const lightPrimary = getCSSVariableValue('--color-primary');
      expect(lightPrimary).toBe('#2563eb');

      const button = screen.getByRole('button', { name: /切换到暗黑模式/i });
      fireEvent.click(button);

      const darkPrimary = getCSSVariableValue('--color-primary');
      expect(darkPrimary).toBe('#3b82f6');
      expect(darkPrimary).not.toBe(lightPrimary);
    });

    it('切换到dark主题后，背景色变量更新', () => {
      let themeApi: ReturnType<typeof useTheme> | null = null;

      const TestComponent = () => {
        themeApi = useTheme();
        React.useEffect(() => {
          injectCSSVariables(themeApi!.mode);
        }, [themeApi?.mode]);
        return null;
      };

      vi.spyOn(window, 'matchMedia').mockImplementation(
        (query) =>
          ({
            matches: false,
            media: query,
            onchange: null,
            addListener: vi.fn(),
            removeListener: vi.fn(),
            addEventListener: vi.fn(),
            removeEventListener: vi.fn(),
            dispatchEvent: vi.fn(),
          }) as MediaQueryList,
      );

      render(
        <ThemeProvider>
          <TestComponent />
        </ThemeProvider>,
      );

      const lightBg = getCSSVariableValue('--color-background');
      expect(lightBg).toBe('#ffffff');

      act(() => {
        themeApi!.setMode('dark');
      });

      const darkBg = getCSSVariableValue('--color-background');
      expect(darkBg).toBe('#0f172a');
    });

    it('切换到dark主题后，文本颜色变量更新', () => {
      let themeApi: ReturnType<typeof useTheme> | null = null;

      const TestComponent = () => {
        themeApi = useTheme();
        React.useEffect(() => {
          injectCSSVariables(themeApi!.mode);
        }, [themeApi?.mode]);
        return null;
      };

      vi.spyOn(window, 'matchMedia').mockImplementation(
        (query) =>
          ({
            matches: false,
            media: query,
            onchange: null,
            addListener: vi.fn(),
            removeListener: vi.fn(),
            addEventListener: vi.fn(),
            removeEventListener: vi.fn(),
            dispatchEvent: vi.fn(),
          }) as MediaQueryList,
      );

      render(
        <ThemeProvider>
          <TestComponent />
        </ThemeProvider>,
      );

      const lightText = getCSSVariableValue('--color-text-primary');
      expect(lightText).toBe('#0f172a');

      act(() => {
        themeApi!.setMode('dark');
      });

      const darkText = getCSSVariableValue('--color-text-primary');
      expect(darkText).toBe('#f8fafc');
    });
  });

  describe('设计令牌（tokens）', () => {
    it('useTokens返回light主题的tokens', () => {
      let receivedTokens: typeof lightTokens | null = null;

      const TestComponent = () => {
        receivedTokens = useTokens();
        return null;
      };

      vi.spyOn(window, 'matchMedia').mockImplementation(
        (query) =>
          ({
            matches: false,
            media: query,
            onchange: null,
            addListener: vi.fn(),
            removeListener: vi.fn(),
            addEventListener: vi.fn(),
            removeEventListener: vi.fn(),
            dispatchEvent: vi.fn(),
          }) as MediaQueryList,
      );

      render(
        <ThemeProvider defaultMode="light">
          <TestComponent />
        </ThemeProvider>,
      );

      expect(receivedTokens).toEqual(lightTokens);
    });

    it('useTokens返回dark主题的tokens', () => {
      let receivedTokens: typeof darkTokens | null = null;

      const TestComponent = () => {
        receivedTokens = useTokens();
        return null;
      };

      render(
        <ThemeProvider defaultMode="dark">
          <TestComponent />
        </ThemeProvider>,
      );

      expect(receivedTokens).toEqual(darkTokens);
    });

    it('主题切换后tokens自动更新', () => {
      let receivedTokens: typeof lightTokens | null = null;
      let themeApi: ReturnType<typeof useTheme> | null = null;

      const TestComponent = () => {
        receivedTokens = useTokens();
        themeApi = useTheme();
        return null;
      };

      vi.spyOn(window, 'matchMedia').mockImplementation(
        (query) =>
          ({
            matches: false,
            media: query,
            onchange: null,
            addListener: vi.fn(),
            removeListener: vi.fn(),
            addEventListener: vi.fn(),
            removeEventListener: vi.fn(),
            dispatchEvent: vi.fn(),
          }) as MediaQueryList,
      );

      render(
        <ThemeProvider defaultMode="light">
          <TestComponent />
        </ThemeProvider>,
      );

      expect(receivedTokens).toEqual(lightTokens);

      act(() => {
        themeApi!.setMode('dark');
      });

      expect(receivedTokens).toEqual(darkTokens);
    });

    it('lightTokens和darkTokens的颜色值不同', () => {
      expect(lightTokens.colors.primary).not.toBe(darkTokens.colors.primary);
      expect(lightTokens.colors.background).not.toBe(darkTokens.colors.background);
      expect(lightTokens.colors.textPrimary).not.toBe(darkTokens.colors.textPrimary);
    });

    it('spacing、radius等非颜色令牌在亮暗主题中保持一致', () => {
      expect(lightTokens.spacing).toEqual(darkTokens.spacing);
      expect(lightTokens.radius).toEqual(darkTokens.radius);
      expect(lightTokens.typography).toEqual(darkTokens.typography);
    });
  });

  describe('useTheme Hook', () => {
    it('在Provider外部使用会抛出错误', () => {
      const TestComponent = () => {
        expect(() => useTheme()).toThrow('useTheme must be used within a ThemeProvider');
        return null;
      };

      render(<TestComponent />);
    });

    it('返回正确的mode和tokens', () => {
      let themeApi: ReturnType<typeof useTheme> | null = null;

      const TestComponent = () => {
        themeApi = useTheme();
        return null;
      };

      render(
        <ThemeProvider defaultMode="dark">
          <TestComponent />
        </ThemeProvider>,
      );

      expect(themeApi!.mode).toBe('dark');
      expect(themeApi!.tokens).toEqual(darkTokens);
    });
  });

  describe('组件颜色自动更新', () => {
    it('Button组件在主题切换后颜色自动更新', async () => {
      const TestComponent = () => {
        const { mode, setMode } = useTheme();

        React.useEffect(() => {
          injectCSSVariables(mode);
        }, [mode]);

        return (
          <div>
            <Button variant="primary" data-testid="theme-btn">
              按钮 - {mode}
            </Button>
            <Button onClick={() => setMode(mode === 'light' ? 'dark' : 'light')} data-testid="toggle-btn">
              切换主题
            </Button>
          </div>
        );
      };

      vi.spyOn(window, 'matchMedia').mockImplementation(
        (query) =>
          ({
            matches: false,
            media: query,
            onchange: null,
            addListener: vi.fn(),
            removeListener: vi.fn(),
            addEventListener: vi.fn(),
            removeEventListener: vi.fn(),
            dispatchEvent: vi.fn(),
          }) as MediaQueryList,
      );

      render(
        <ThemeProvider>
          <TestComponent />
        </ThemeProvider>,
      );

      const themeBtn = screen.getByTestId('theme-btn');
      expect(themeBtn).toHaveTextContent('按钮 - light');

      const toggleBtn = screen.getByTestId('toggle-btn');
      fireEvent.click(toggleBtn);

      await waitFor(() => {
        expect(themeBtn).toHaveTextContent('按钮 - dark');
      });

      expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    });
  });
});
