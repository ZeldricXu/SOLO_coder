import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import wasm from 'vite-plugin-wasm';
import topLevelAwait from 'vite-plugin-top-level-await';
import compression from 'vite-plugin-compression';
import path from 'path';

export default defineConfig(({ mode }) => {
  const isProduction = mode === 'production';

  return {
    plugins: [
      react(),
      wasm(),
      topLevelAwait(),
      isProduction && compression({
        verbose: true,
        algorithm: 'gzip',
        ext: '.gz',
        threshold: 1024,
        compressionOptions: { level: 9 },
        deleteOriginFile: false,
      }),
      isProduction && compression({
        verbose: true,
        algorithm: 'brotliCompress',
        ext: '.br',
        threshold: 1024,
        compressionOptions: { level: 11 },
        deleteOriginFile: false,
      }),
    ].filter(Boolean),
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    optimizeDeps: {
      exclude: ['@/wasm'],
    },
    build: {
      target: 'es2020',
      minify: 'esbuild',
      sourcemap: !isProduction,
      chunkSizeWarningLimit: 1500,
      rollupOptions: {
        output: {
          entryFileNames: 'assets/[name].[hash].js',
          chunkFileNames: 'assets/[name].[hash].js',
          assetFileNames: 'assets/[name].[hash][extname]',
          manualChunks: {
            react: ['react', 'react-dom'],
            yjs: ['yjs', 'y-protocols'],
            zustand: ['zustand'],
          },
        },
      },
    },
    server: {
      port: 5173,
      host: true,
    },
    worker: {
      format: 'es',
      plugins: [wasm(), topLevelAwait()],
    },
  };
});
