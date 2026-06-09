import type { Preview } from '@storybook/react';
import React from 'react';
import { ThemeProvider } from '../src/theme/ThemeContext';
import { ToastProvider } from '../src/components/Toast';
import '../src/theme/variables.css';

const preview: Preview = {
  parameters: {
    controls: {
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i,
      },
    },
    actions: { argTypesRegex: '^on[A-Z].*' },
    a11y: {
      config: {},
      options: {
        checks: { 'color-contrast': { options: { noScroll: true } } },
        restoreScroll: true,
      },
    },
  },
  globalTypes: {
    theme: {
      name: 'Theme',
      description: 'Global theme for components',
      defaultValue: 'light',
      toolbar: {
        icon: 'circlehollow',
        items: [
          { value: 'light', icon: 'sun', title: 'Light' },
          { value: 'dark', icon: 'moon', title: 'Dark' },
        ],
        showName: true,
      },
    },
  },
  decorators: [
    (Story, context) => {
      const theme = context.globals.theme || 'light';
      return (
        <ThemeProvider defaultTheme={theme}>
          <ToastProvider>
            <div style={{ padding: '2rem' }}>
              <Story />
            </div>
          </ToastProvider>
        </ThemeProvider>
      );
    },
  ],
};

export default preview;
