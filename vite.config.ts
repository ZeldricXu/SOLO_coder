import { defineConfig } from 'electron-vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  main: {
    build: {
      outDir: 'dist/main',
      rollupOptions: {
        external: [
          'better-sqlite3',
          'nodejieba',
          'electron',
          'isomorphic-git',
          '@isomorphic-git/http',
          '@isomorphic-git/http/node',
        ],
      },
    },
  },
  preload: {
    build: {
      outDir: 'dist/preload',
    },
  },
  renderer: {
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src'),
        '@shared': path.resolve(__dirname, 'src/shared'),
        '@core': path.resolve(__dirname, 'src/core'),
        '@renderer': path.resolve(__dirname, 'src/renderer'),
        '@main': path.resolve(__dirname, 'src/main'),
      },
    },
    build: {
      outDir: 'dist/renderer',
      rollupOptions: {
        external: ['nodejieba'],
      },
    },
    plugins: [react()],
  },
});
