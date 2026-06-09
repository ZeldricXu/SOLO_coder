import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import typescript from '@rollup/plugin-typescript';
import peerDepsExternal from 'rollup-plugin-peer-deps-external';
import postcss from 'rollup-plugin-postcss';
import dts from 'rollup-plugin-dts';
import { readdirSync, statSync } from 'fs';
import path from 'path';

const getComponentEntries = (dir) => {
  const entries = {};
  const items = readdirSync(dir);
  
  items.forEach((item) => {
    const fullPath = path.join(dir, item);
    if (statSync(fullPath).isDirectory()) {
      const indexPath = path.join(fullPath, 'index.tsx');
      if (statSync(indexPath).isFile()) {
        entries[item] = indexPath;
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
  'cli/index': 'src/cli/index.ts',
  ...componentEntries,
};

const plugins = [
  peerDepsExternal(),
  resolve(),
  commonjs(),
  typescript({
    tsconfig: './tsconfig.json',
    declaration: false,
    declarationMap: false,
  }),
  postcss({
    extract: 'styles.css',
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
  },
  plugins,
  external: ['react', 'react-dom', /^react-hook-form/, /^@hookform\/resolvers/, /^zod/, /^@floating-ui\/react/, /^clsx/],
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
  },
  plugins,
  external: ['react', 'react-dom', /^react-hook-form/, /^@hookform\/resolvers/, /^zod/, /^@floating-ui\/react/, /^clsx/],
};

const dtsConfig = {
  input: allEntries,
  output: {
    dir: 'dist/types',
    format: 'esm',
    preserveModules: true,
    preserveModulesRoot: 'src',
  },
  plugins: [dts()],
};

export default [esmConfig, cjsConfig, dtsConfig];
