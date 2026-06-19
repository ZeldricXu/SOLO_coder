"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.ConfigGenerator = void 0;
const fs_extra_1 = __importDefault(require("fs-extra"));
const path_1 = __importDefault(require("path"));
class ConfigGenerator {
    config;
    targetDir;
    constructor(config) {
        this.config = config;
        this.targetDir = config.targetDir;
    }
    async generateAll() {
        await Promise.all([
            this.generateTsConfig(),
            this.generateESLintConfig(),
            this.generatePrettierConfig(),
            this.generateEditorConfig(),
            this.generateGitIgnore(),
            this.generatePackageJson(),
        ]);
    }
    async generateTsConfig() {
        const baseConfig = this.getBaseTsConfig();
        const frameworkConfig = this.getFrameworkTsConfig();
        const tsConfig = {
            ...baseConfig,
            compilerOptions: {
                ...baseConfig.compilerOptions,
                ...frameworkConfig.compilerOptions,
            },
            include: [...baseConfig.include, ...(frameworkConfig.include ?? [])],
            exclude: [...baseConfig.exclude, ...(frameworkConfig.exclude ?? [])],
        };
        await fs_extra_1.default.writeJson(path_1.default.join(this.targetDir, 'tsconfig.json'), tsConfig, { spaces: 2 });
        if (this.config.framework === 'react-frontend' || this.config.framework === 'vue-frontend') {
            await this.generateTsConfigNode();
        }
    }
    getBaseTsConfig() {
        return {
            compilerOptions: {
                target: 'ES2022',
                module: 'NodeNext',
                moduleResolution: 'NodeNext',
                lib: ['ES2022'],
                outDir: './dist',
                rootDir: './src',
                strict: true,
                noImplicitAny: true,
                strictNullChecks: true,
                strictFunctionTypes: true,
                strictBindCallApply: true,
                strictPropertyInitialization: true,
                noImplicitThis: true,
                useUnknownInCatchVariables: true,
                alwaysStrict: true,
                noUnusedLocals: true,
                noUnusedParameters: true,
                exactOptionalPropertyTypes: true,
                noImplicitReturns: true,
                noFallthroughCasesInSwitch: true,
                noUncheckedIndexedAccess: true,
                noImplicitOverride: true,
                noPropertyAccessFromIndexSignature: true,
                esModuleInterop: true,
                skipLibCheck: true,
                forceConsistentCasingInFileNames: true,
                resolveJsonModule: true,
                declaration: true,
                declarationMap: true,
                sourceMap: true,
                allowSyntheticDefaultImports: true,
                isolatedModules: true,
            },
            include: ['src/**/*'],
            exclude: ['node_modules', 'dist'],
        };
    }
    getFrameworkTsConfig() {
        const configs = {
            'node-backend': {
                compilerOptions: {
                    types: ['node', 'jest'],
                },
                include: ['tests/**/*'],
                exclude: ['coverage'],
            },
            'react-frontend': {
                compilerOptions: {
                    target: 'ES2020',
                    useDefineForClassFields: true,
                    lib: ['ES2020', 'DOM', 'DOM.Iterable'],
                    module: 'ESNext',
                    moduleResolution: 'bundler',
                    allowImportingTsExtensions: true,
                    noEmit: true,
                    jsx: 'react-jsx',
                    types: ['node', 'jest', '@testing-library/jest-dom'],
                    baseUrl: '.',
                    paths: {
                        '@/*': ['src/*'],
                    },
                },
                include: ['src/**/*', 'tests/**/*'],
                exclude: ['dist', 'coverage'],
            },
            'vue-frontend': {
                compilerOptions: {
                    target: 'ES2020',
                    useDefineForClassFields: true,
                    lib: ['ES2020', 'DOM', 'DOM.Iterable'],
                    module: 'ESNext',
                    moduleResolution: 'bundler',
                    allowImportingTsExtensions: true,
                    noEmit: true,
                    jsx: 'preserve',
                    types: ['node', 'jest'],
                    baseUrl: '.',
                    paths: {
                        '@/*': ['src/*'],
                    },
                },
                include: ['src/**/*.ts', 'src/**/*.tsx', 'src/**/*.vue', 'tests/**/*'],
                exclude: ['dist', 'coverage'],
            },
            'cli-tool': {
                compilerOptions: {
                    types: ['node', 'jest'],
                    bin: './dist/index.js',
                },
                include: ['tests/**/*'],
                exclude: ['coverage'],
            },
        };
        return configs[this.config.framework];
    }
    async generateTsConfigNode() {
        const tsConfigNode = {
            compilerOptions: {
                composite: true,
                skipLibCheck: true,
                module: 'ESNext',
                moduleResolution: 'bundler',
                allowSyntheticDefaultImports: true,
            },
            include: ['vite.config.ts'],
        };
        await fs_extra_1.default.writeJson(path_1.default.join(this.targetDir, 'tsconfig.node.json'), tsConfigNode, { spaces: 2 });
    }
    async generateESLintConfig() {
        const eslintConfig = this.getESLintConfig();
        await fs_extra_1.default.writeFile(path_1.default.join(this.targetDir, 'eslint.config.js'), `import eslint from '@eslint/js';\nimport tseslint from 'typescript-eslint';\n\n${eslintConfig}`, 'utf-8');
    }
    getESLintConfig() {
        const baseRules = `{
  files: ['**/*.{ts,tsx}'],
  languageOptions: {
    parser: tseslint.parser,
    parserOptions: {
      project: true,
      tsconfigRootDir: __dirname,
    },
  },
  plugins: {
    '@typescript-eslint': tseslint.plugin,
  },
  rules: {
    ...eslint.configs.recommended.rules,
    ...tseslint.configs.strictTypeChecked.rules,
    ...tseslint.configs.stylisticTypeChecked.rules,
    '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    '@typescript-eslint/no-explicit-any': 'error',
    '@typescript-eslint/explicit-function-return-type': ['warn', { allowExpressions: true }],
    '@typescript-eslint/consistent-type-definitions': ['error', 'interface'],
    '@typescript-eslint/prefer-nullish-coalescing': 'error',
    '@typescript-eslint/prefer-optional-chain': 'error',
    '@typescript-eslint/no-floating-promises': 'error',
    '@typescript-eslint/no-misused-promises': 'error',
    'no-console': ['warn', { allow: ['warn', 'error'] }],
  },
}`;
        const ignores = `{
  ignores: ['dist/**', 'node_modules/**', 'coverage/**', '*.config.{js,ts}'],
}`;
        return `export default tseslint.config(\n  eslint.configs.recommended,\n  ...tseslint.configs.strictTypeChecked,\n  ...tseslint.configs.stylisticTypeChecked,\n  ${baseRules},\n  ${ignores},\n);\n`;
    }
    async generatePrettierConfig() {
        const prettierConfig = {
            semi: true,
            trailingComma: 'all',
            singleQuote: true,
            printWidth: 100,
            tabWidth: 2,
            useTabs: false,
            bracketSpacing: true,
            bracketSameLine: false,
            arrowParens: 'always',
            endOfLine: 'lf',
            overrides: [
                {
                    files: '*.json',
                    options: {
                        printWidth: 120,
                    },
                },
                {
                    files: '*.md',
                    options: {
                        printWidth: 120,
                        proseWrap: 'always',
                    },
                },
            ],
        };
        await fs_extra_1.default.writeJson(path_1.default.join(this.targetDir, '.prettierrc.json'), prettierConfig, { spaces: 2 });
        const prettierIgnore = [
            'node_modules/',
            'dist/',
            'build/',
            'coverage/',
            '.git/',
            '*.min.*',
            'package-lock.json',
            'yarn.lock',
            'pnpm-lock.yaml',
        ].join('\n');
        await fs_extra_1.default.writeFile(path_1.default.join(this.targetDir, '.prettierignore'), prettierIgnore + '\n', 'utf-8');
    }
    async generateEditorConfig() {
        const editorConfig = [
            'root = true',
            '',
            '[*]',
            'charset = utf-8',
            'end_of_line = lf',
            'insert_final_newline = true',
            'indent_style = space',
            'indent_size = 2',
            'trim_trailing_whitespace = true',
            '',
            '[*.md]',
            'trim_trailing_whitespace = false',
            '',
            '[Makefile]',
            'indent_style = tab',
            '',
            '[*.{json,yml,yaml}]',
            'indent_size = 2',
        ].join('\n');
        await fs_extra_1.default.writeFile(path_1.default.join(this.targetDir, '.editorconfig'), editorConfig + '\n', 'utf-8');
    }
    async generateGitIgnore() {
        const baseIgnore = [
            '# Dependencies',
            'node_modules/',
            '',
            '# Build outputs',
            'dist/',
            'build/',
            'out/',
            '',
            '# Testing',
            'coverage/',
            '.nyc_output/',
            '',
            '# Environment',
            '.env',
            '.env.local',
            '.env.*.local',
            '',
            '# IDE',
            '.idea/',
            '.vscode/',
            '*.swp',
            '*.swo',
            '*~',
            '',
            '# OS',
            '.DS_Store',
            'Thumbs.db',
            '',
            '# Logs',
            'logs/',
            '*.log',
            'npm-debug.log*',
            'yarn-debug.log*',
            'yarn-error.log*',
            'pnpm-debug.log*',
            '',
            '# Misc',
            '.cache/',
            '.parcel-cache/',
            '.eslintcache',
        ];
        const frameworkIgnore = this.getFrameworkGitIgnore();
        const gitIgnore = [...baseIgnore, ...frameworkIgnore].join('\n');
        await fs_extra_1.default.writeFile(path_1.default.join(this.targetDir, '.gitignore'), gitIgnore + '\n', 'utf-8');
    }
    getFrameworkGitIgnore() {
        const ignores = {
            'node-backend': [
                '# Database',
                '*.sqlite',
                '*.sqlite3',
                '',
                '# Uploads',
                'uploads/',
            ],
            'react-frontend': [
                '# Vite',
                '.vite/',
                '',
                '# Build',
                'dist-ssr/',
                '',
                '# Local env files',
                '.env.development.local',
                '.env.test.local',
                '.env.production.local',
            ],
            'vue-frontend': [
                '# Vite',
                '.vite/',
                '',
                '# Build',
                'dist-ssr/',
                '',
                '# Vue',
                '*.vue~',
            ],
            'cli-tool': [
                '# NPM',
                '*.tgz',
            ],
        };
        return ignores[this.config.framework];
    }
    async generatePackageJson() {
        const packageJson = {
            name: this.config.projectName,
            version: this.config.projectVersion,
            description: this.config.description,
            author: this.config.author,
            license: 'MIT',
            type: 'module',
            engines: {
                node: '>=18.0.0',
            },
            scripts: {},
            dependencies: {},
            devDependencies: {},
        };
        if (this.config.framework === 'cli-tool') {
            packageJson.bin = {
                [this.config.projectName]: './dist/index.js',
            };
        }
        await fs_extra_1.default.writeJson(path_1.default.join(this.targetDir, 'package.json'), packageJson, { spaces: 2 });
    }
}
exports.ConfigGenerator = ConfigGenerator;
//# sourceMappingURL=config-generator.js.map