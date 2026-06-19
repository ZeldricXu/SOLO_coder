"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.BUILTIN_TEMPLATES = exports.CLI_TOOL_TEMPLATE = exports.VUE_FRONTEND_TEMPLATE = exports.REACT_FRONTEND_TEMPLATE = exports.NODE_BACKEND_TEMPLATE = void 0;
exports.getBuiltinTemplate = getBuiltinTemplate;
const path_1 = __importDefault(require("path"));
const CONTENT_DIR = path_1.default.resolve(process.cwd(), 'src', 'templates', 'content');
const BASE_DEPENDENCIES = {
    'tslib': '^2.6.3',
};
const BASE_DEV_DEPENDENCIES = {
    'typescript': '^5.4.5',
    '@types/node': '^20.14.2',
    'eslint': '^9.5.0',
    'prettier': '^3.3.2',
    '@typescript-eslint/eslint-plugin': '^8.0.0',
    '@typescript-eslint/parser': '^8.0.0',
    'globals': '^15.4.0',
    'typescript-eslint': '^8.0.0',
};
const BASE_SCRIPTS = {
    'build': 'tsc',
    'dev': 'tsc --watch',
    'start': 'node dist/index.js',
    'lint': 'eslint .',
    'lint:fix': 'eslint . --fix',
    'format': 'prettier --write .',
    'format:check': 'prettier --check .',
};
function tmpl(relativePath) {
    return path_1.default.join(CONTENT_DIR, relativePath);
}
exports.NODE_BACKEND_TEMPLATE = {
    name: 'node-backend',
    description: 'Node.js Backend with Express',
    framework: 'node-backend',
    dependencies: {
        ...BASE_DEPENDENCIES,
        'express': '^4.19.2',
        'cors': '^2.8.5',
        'helmet': '^7.1.0',
        'dotenv': '^16.4.5',
        'pino': '^9.2.0',
    },
    devDependencies: {
        ...BASE_DEV_DEPENDENCIES,
        '@types/express': '^4.17.21',
        '@types/cors': '^2.8.17',
        'jest': '^29.7.0',
        '@types/jest': '^29.5.12',
        'ts-jest': '^29.1.4',
        'supertest': '^7.0.0',
        '@types/supertest': '^6.0.2',
    },
    scripts: {
        ...BASE_SCRIPTS,
        'test': 'jest',
        'test:watch': 'jest --watch',
        'test:coverage': 'jest --coverage',
    },
    files: [
        { source: tmpl('node-backend/src/index.ts.hbs'), target: 'src/index.ts', isTemplate: true },
        { source: tmpl('node-backend/src/server.ts.hbs'), target: 'src/server.ts', isTemplate: true },
        { source: tmpl('node-backend/src/routes/health.ts.hbs'), target: 'src/routes/health.ts', isTemplate: true },
        { source: tmpl('node-backend/src/middleware/logger.ts'), target: 'src/middleware/logger.ts', isTemplate: false },
        { source: tmpl('node-backend/src/config/env.ts.hbs'), target: 'src/config/env.ts', isTemplate: true },
        { source: tmpl('node-backend/.env.example'), target: '.env.example', isTemplate: false },
        { source: tmpl('node-backend/tests/server.test.ts.hbs'), target: 'tests/server.test.ts', isTemplate: true },
        { source: tmpl('node-backend/jest.config.js'), target: 'jest.config.js', isTemplate: false },
    ],
};
exports.REACT_FRONTEND_TEMPLATE = {
    name: 'react-frontend',
    description: 'React Frontend with Vite',
    framework: 'react-frontend',
    dependencies: {
        ...BASE_DEPENDENCIES,
        'react': '^18.3.1',
        'react-dom': '^18.3.1',
        'react-router-dom': '^6.23.1',
        'axios': '^1.7.2',
    },
    devDependencies: {
        ...BASE_DEV_DEPENDENCIES,
        '@types/react': '^18.3.3',
        '@types/react-dom': '^18.3.0',
        '@vitejs/plugin-react': '^4.3.1',
        'vite': '^5.3.1',
        'jest': '^29.7.0',
        '@types/jest': '^29.5.12',
        '@testing-library/react': '^16.0.0',
        '@testing-library/jest-dom': '^6.4.6',
        'ts-jest': '^29.1.4',
        'jest-environment-jsdom': '^29.7.0',
    },
    scripts: {
        'build': 'tsc && vite build',
        'dev': 'vite',
        'preview': 'vite preview',
        'lint': 'eslint .',
        'lint:fix': 'eslint . --fix',
        'format': 'prettier --write .',
        'format:check': 'prettier --check .',
        'test': 'jest',
        'test:watch': 'jest --watch',
    },
    files: [
        { source: tmpl('react-frontend/src/main.tsx.hbs'), target: 'src/main.tsx', isTemplate: true },
        { source: tmpl('react-frontend/src/App.tsx.hbs'), target: 'src/App.tsx', isTemplate: true },
        { source: tmpl('react-frontend/src/index.css'), target: 'src/index.css', isTemplate: false },
        { source: tmpl('react-frontend/src/vite-env.d.ts'), target: 'src/vite-env.d.ts', isTemplate: false },
        { source: tmpl('react-frontend/src/pages/Home.tsx.hbs'), target: 'src/pages/Home.tsx', isTemplate: true },
        { source: tmpl('react-frontend/src/components/Header.tsx.hbs'), target: 'src/components/Header.tsx', isTemplate: true },
        { source: tmpl('react-frontend/public/index.html.hbs'), target: 'public/index.html', isTemplate: true },
        { source: tmpl('react-frontend/vite.config.ts'), target: 'vite.config.ts', isTemplate: false },
        { source: tmpl('react-frontend/jest.config.js'), target: 'jest.config.js', isTemplate: false },
        { source: tmpl('react-frontend/tests/setup.ts'), target: 'tests/setup.ts', isTemplate: false },
        { source: tmpl('react-frontend/tests/App.test.tsx'), target: 'tests/App.test.tsx', isTemplate: false },
    ],
};
exports.VUE_FRONTEND_TEMPLATE = {
    name: 'vue-frontend',
    description: 'Vue Frontend with Vite',
    framework: 'vue-frontend',
    dependencies: {
        ...BASE_DEPENDENCIES,
        'vue': '^3.4.29',
        'vue-router': '^4.3.3',
        'pinia': '^2.1.7',
        'axios': '^1.7.2',
    },
    devDependencies: {
        ...BASE_DEV_DEPENDENCIES,
        '@vitejs/plugin-vue': '^5.0.5',
        'vite': '^5.3.1',
        'vue-tsc': '^2.0.21',
        '@vue/test-utils': '^2.4.6',
        'jest': '^29.7.0',
        '@types/jest': '^29.5.12',
        'ts-jest': '^29.1.4',
        'jest-environment-jsdom': '^29.7.0',
        'vue-jest': '^5.0.0-alpha.10',
    },
    scripts: {
        'build': 'vue-tsc --noEmit && vite build',
        'dev': 'vite',
        'preview': 'vite preview',
        'type-check': 'vue-tsc --noEmit',
        'lint': 'eslint .',
        'lint:fix': 'eslint . --fix',
        'format': 'prettier --write .',
        'format:check': 'prettier --check .',
        'test': 'jest',
    },
    files: [
        { source: tmpl('vue-frontend/src/main.ts.hbs'), target: 'src/main.ts', isTemplate: true },
        { source: tmpl('vue-frontend/src/App.vue.hbs'), target: 'src/App.vue', isTemplate: true },
        { source: tmpl('vue-frontend/src/style.css'), target: 'src/style.css', isTemplate: false },
        { source: tmpl('vue-frontend/src/vite-env.d.ts'), target: 'src/vite-env.d.ts', isTemplate: false },
        { source: tmpl('vue-frontend/src/router/index.ts'), target: 'src/router/index.ts', isTemplate: false },
        { source: tmpl('vue-frontend/src/stores/counter.ts'), target: 'src/stores/counter.ts', isTemplate: false },
        { source: tmpl('vue-frontend/src/views/Home.vue.hbs'), target: 'src/views/Home.vue', isTemplate: true },
        { source: tmpl('vue-frontend/public/index.html.hbs'), target: 'public/index.html', isTemplate: true },
        { source: tmpl('vue-frontend/vite.config.ts'), target: 'vite.config.ts', isTemplate: false },
        { source: tmpl('vue-frontend/jest.config.js'), target: 'jest.config.js', isTemplate: false },
    ],
};
exports.CLI_TOOL_TEMPLATE = {
    name: 'cli-tool',
    description: 'Node.js CLI Tool',
    framework: 'cli-tool',
    dependencies: {
        ...BASE_DEPENDENCIES,
        'commander': '^12.1.0',
        'chalk': '^5.3.0',
        'ora': '^8.0.1',
        'inquirer': '^9.2.23',
    },
    devDependencies: {
        ...BASE_DEV_DEPENDENCIES,
        'jest': '^29.7.0',
        '@types/jest': '^29.5.12',
        'ts-jest': '^29.1.4',
        '@types/inquirer': '^9.0.7',
    },
    scripts: {
        ...BASE_SCRIPTS,
        'test': 'jest',
        'test:watch': 'jest --watch',
    },
    files: [
        { source: tmpl('cli-tool/src/index.ts.hbs'), target: 'src/index.ts', isTemplate: true, perm: 0o755 },
        { source: tmpl('cli-tool/src/cli.ts.hbs'), target: 'src/cli.ts', isTemplate: true },
        { source: tmpl('cli-tool/src/utils/logger.ts'), target: 'src/utils/logger.ts', isTemplate: false },
        { source: tmpl('cli-tool/src/commands/example.ts.hbs'), target: 'src/commands/example.ts', isTemplate: true },
        { source: tmpl('cli-tool/jest.config.js'), target: 'jest.config.js', isTemplate: false },
        { source: tmpl('cli-tool/tests/cli.test.ts'), target: 'tests/cli.test.ts', isTemplate: false },
    ],
};
exports.BUILTIN_TEMPLATES = {
    'node-backend': exports.NODE_BACKEND_TEMPLATE,
    'react-frontend': exports.REACT_FRONTEND_TEMPLATE,
    'vue-frontend': exports.VUE_FRONTEND_TEMPLATE,
    'cli-tool': exports.CLI_TOOL_TEMPLATE,
};
function getBuiltinTemplate(framework) {
    return exports.BUILTIN_TEMPLATES[framework];
}
//# sourceMappingURL=builtin.js.map