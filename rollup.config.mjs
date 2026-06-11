import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import typescript from '@rollup/plugin-typescript';
import peerDepsExternal from 'rollup-plugin-peer-deps-external';
import postcss from 'rollup-plugin-postcss';
import dts from 'rollup-plugin-dts';
import alias from '@rollup/plugin-alias';
import { readdirSync, statSync } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const aliasEntries = [
  { find: '@', replacement: path.resolve(__dirname, 'src') },
  { find: '@components', replacement: path.resolve(__dirname, 'src/components') },
  { find: '@hooks', replacement: path.resolve(__dirname, 'src/hooks') },
  { find: '@utils', replacement: path.resolve(__dirname, 'src/utils') },
  { find: '@theme', replacement: path.resolve(__dirname, 'src/theme') },
  { find: '@types', replacement: path.resolve(__dirname, 'src/types') },
  { find: '@a11y', replacement: path.resolve(__dirname, 'src/a11y') },
  { find: '@validation', replacement: path.resolve(__dirname, 'src/validation') },
];

const getComponentEntries = (dir) => {
  const entries = {};
  const items = readdirSync(dir);
  
  items.forEach((item) => {
    const fullPath = path.join(dir, item);
    if (statSync(fullPath).isDirectory()) {
      const indexFiles = ['index.tsx', 'index.ts'];
      for (const indexFile of indexFiles) {
        const indexPath = path.join(fullPath, indexFile);
        try {
          if (statSync(indexPath).isFile()) {
            entries[item] = indexPath;
            break;
          }
        } catch {
        }
      }
    }
  });
  
  return entries;
};

const componentsDir = 'src/components';
const componentEntries = getComponentEntries(componentsDir);

const allEntries = {
  index: 'src/index.ts',
  theme: 'src/theme/index.ts',
  validation: 'src/validation/index.ts',
  'cli/index': 'src/cli/index.ts',
  ...componentEntries,
};

const externalDeps = [
  'react',
  'react-dom',
  /^react-hook-form/,
  /^@hookform\/resolvers/,
  /^zod/,
  /^@floating-ui\/react/,
  /^clsx/,
];

const sharedPlugins = [
  alias({ entries: aliasEntries }),
  peerDepsExternal(),
  resolve(),
  commonjs(),
  typescript({
    tsconfig: './tsconfig.json',
    declaration: false,
    declarationMap: false,
    outDir: undefined,
    declarationDir: undefined,
  }),
  postcss({
    extract: true,
    modules: true,
    autoModules: true,
    minimize: true,
    sourceMap: true,
  }),
];

const esmConfig = {
  input: allEntries,
  output: {
    dir: 'dist/esm',
    format: 'esm',
    sourcemap: true,
    preserveModules: true,
    preserveModulesRoot: 'src',
    entryFileNames: '[name].js',
  },
  plugins: sharedPlugins,
  external: externalDeps,
};

const cjsConfig = {
  input: allEntries,
  output: {
    dir: 'dist/cjs',
    format: 'cjs',
    sourcemap: true,
    preserveModules: true,
    preserveModulesRoot: 'src',
    exports: 'named',
    entryFileNames: '[name].cjs',
  },
  plugins: sharedPlugins,
  external: externalDeps,
};

const dtsConfig = {
  input: allEntries,
  output: {
    dir: 'dist/types',
    format: 'esm',
    preserveModules: true,
    preserveModulesRoot: 'src',
    entryFileNames: '[name].d.ts',
  },
  plugins: [
    alias({ entries: aliasEntries }),
    dts({
      tsconfig: './tsconfig.json',
      compilerOptions: {
        outDir: undefined,
        declarationDir: undefined,
      },
    }),
  ],
};

export default [esmConfig, cjsConfig];
