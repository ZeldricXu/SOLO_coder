import { Template, TemplateRegistry } from './types';
import { generateId, logger } from '../utils/common';

export class TemplateManager {
  private registry: TemplateRegistry = {};

  registerTemplate(template: Omit<Template, 'id'>): Template {
    const id = generateId('tpl_');
    const fullTemplate: Template = { ...template, id } as Template;

    this.registry[id] = fullTemplate;
    logger.info(`Template registered`, { id, name: fullTemplate.name });
    return fullTemplate;
  }

  getTemplate(id: string): Template | undefined {
    return this.registry[id];
  }

  getTemplateByName(name: string): Template | undefined {
    return Object.values(this.registry).find(t => t.name === name);
  }

  updateTemplate(id: string, updates: Partial<Template>): Template | undefined {
    const template = this.registry[id];
    if (!template) return undefined;

    this.registry[id] = { ...template, ...updates };
    logger.info(`Template updated`, { id });
    return this.registry[id];
  }

  deleteTemplate(id: string): boolean {
    const deleted = delete this.registry[id];
    if (deleted) {
      logger.info(`Template deleted`, { id });
    }
    return deleted;
  }

  listTemplates(filters?: {
    language?: string;
    framework?: string;
    category?: string;
    tags?: string[];
  }): Template[] {
    let templates = Object.values(this.registry);

    if (filters?.language) {
      templates = templates.filter(t => t.language === filters.language);
    }
    if (filters?.framework) {
      templates = templates.filter(t => t.framework === filters.framework);
    }
    if (filters?.category) {
      templates = templates.filter(t => t.category === filters.category);
    }
    if (filters?.tags?.length) {
      templates = templates.filter(t => filters.tags!.some(tag => t.tags.includes(tag)));
    }

    return templates;
  }

  searchTemplates(query: string): Template[] {
    const lowerQuery = query.toLowerCase();
    return Object.values(this.registry).filter(t =>
      t.name.toLowerCase().includes(lowerQuery) ||
      t.description?.toLowerCase().includes(lowerQuery) ||
      t.tags.some(tag => tag.toLowerCase().includes(lowerQuery))
    );
  }

  registerDefaultTemplates(): void {
    this.registerTemplate({
      name: 'typescript-api',
      description: 'TypeScript REST API with Express',
      version: '1.0.0',
      language: 'typescript',
      framework: 'express',
      category: 'api',
      files: [
        { path: 'package.json', template: packageJsonTemplate, isBinary: false },
        { path: 'tsconfig.json', template: tsconfigTemplate, isBinary: false },
        { path: 'src/index.ts', template: mainIndexTemplate, isBinary: false },
        { path: 'src/server.ts', template: serverTemplate, isBinary: false },
        { path: '.gitignore', template: gitignoreTemplate, isBinary: false },
        { path: 'README.md', template: readmeTemplate, isBinary: false },
      ],
      parameters: [
        { name: 'projectName', type: 'string', required: true, defaultValue: 'my-api' },
        { name: 'description', type: 'string', required: false, defaultValue: 'A TypeScript API' },
        { name: 'port', type: 'number', required: false, defaultValue: 3000 },
        { name: 'author', type: 'string', required: false },
      ],
      dependencies: ['express', 'zod', 'winston'],
      devDependencies: ['typescript', '@types/express', '@types/node', 'ts-node'],
      scripts: {
        build: 'tsc',
        start: 'node dist/index.js',
        dev: 'ts-node src/index.ts',
      },
      tags: ['api', 'express', 'typescript', 'rest'],
    });

    this.registerTemplate({
      name: 'typescript-cli',
      description: 'TypeScript CLI application',
      version: '1.0.0',
      language: 'typescript',
      category: 'cli',
      files: [
        { path: 'package.json', template: cliPackageJsonTemplate, isBinary: false },
        { path: 'tsconfig.json', template: tsconfigTemplate, isBinary: false },
        { path: 'src/index.ts', template: cliMainTemplate, isBinary: false },
        { path: '.gitignore', template: gitignoreTemplate, isBinary: false },
        { path: 'README.md', template: readmeTemplate, isBinary: false },
      ],
      parameters: [
        { name: 'projectName', type: 'string', required: true, defaultValue: 'my-cli' },
        { name: 'commandName', type: 'string', required: true, defaultValue: 'mycli' },
      ],
      dependencies: ['commander', 'chalk'],
      devDependencies: ['typescript', '@types/node', 'ts-node'],
      scripts: {
        build: 'tsc',
        start: 'node dist/index.js',
        dev: 'ts-node src/index.ts',
      },
      tags: ['cli', 'typescript', 'command-line'],
    });

    logger.info(`Default templates registered`);
  }
}

const packageJsonTemplate = `{
  "name": "{{projectName}}",
  "version": "1.0.0",
  "description": "{{description}}",
  "main": "dist/index.js",
  "scripts": {
    "build": "tsc",
    "start": "node dist/index.js",
    "dev": "ts-node src/index.ts"
  },
  "dependencies": {
    "express": "^4.18.2",
    "zod": "^3.22.4",
    "winston": "^3.11.0"
  },
  "devDependencies": {
    "typescript": "^5.3.3",
    "@types/express": "^4.17.21",
    "@types/node": "^20.10.4",
    "ts-node": "^10.9.2"
  }
}`;

const tsconfigTemplate = `{
  "compilerOptions": {
    "target": "ES2022",
    "module": "commonjs",
    "outDir": "./dist",
    "rootDir": "./src",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true
  },
  "include": ["src/**/*"]
}`;

const mainIndexTemplate = `import { app } from './server';

const PORT = process.env.PORT || {{port}};

app.listen(PORT, () => {
  console.log(\`Server running on port \${PORT}\`);
});
`;

const serverTemplate = `import express from 'express';

export const app = express();

app.use(express.json());

app.get('/health', (req, res) => {
  res.json({ status: 'ok' });
});

app.get('/', (req, res) => {
  res.json({ message: 'Welcome to {{projectName}}!' });
});
`;

const cliPackageJsonTemplate = `{
  "name": "{{projectName}}",
  "version": "1.0.0",
  "description": "{{description}}",
  "main": "dist/index.js",
  "bin": {
    "{{commandName}}": "dist/index.js"
  },
  "scripts": {
    "build": "tsc",
    "start": "node dist/index.js",
    "dev": "ts-node src/index.ts"
  },
  "dependencies": {
    "commander": "^11.1.0",
    "chalk": "^4.1.2"
  },
  "devDependencies": {
    "typescript": "^5.3.3",
    "@types/node": "^20.10.4",
    "ts-node": "^10.9.2"
  }
}`;

const cliMainTemplate = `#!/usr/bin/env node
import { Command } from 'commander';

const program = new Command();

program
  .name('{{commandName}}')
  .description('{{description}}')
  .version('1.0.0');

program
  .command('greet')
  .description('Say hello')
  .argument('<name>', 'name to greet')
  .action((name) => {
    console.log(\`Hello, \${name}!\`);
  });

program.parse();
`;

const gitignoreTemplate = `node_modules/
dist/
.env
.DS_Store
*.log
`;

const readmeTemplate = `# {{projectName}}

{{description}}

## Installation

\`\`\`bash
npm install
\`\`\`

## Development

\`\`\`bash
npm run dev
\`\`\`

## Build

\`\`\`bash
npm run build
\`\`\`

## Start

\`\`\`bash
npm start
\`\`\`
`;

export const templateManager = new TemplateManager();
