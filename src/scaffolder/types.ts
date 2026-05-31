import { z } from 'zod';

export const TemplateSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string().optional(),
  version: z.string().default('1.0.0'),
  language: z.enum(['typescript', 'javascript', 'python', 'java', 'go', 'rust']),
  framework: z.string().optional(),
  category: z.enum(['api', 'web-app', 'cli', 'library', 'microservice']),
  files: z.array(z.object({
    path: z.string(),
    template: z.string(),
    isBinary: z.boolean().default(false),
  })),
  parameters: z.array(z.object({
    name: z.string(),
    type: z.enum(['string', 'number', 'boolean', 'select', 'multiselect']),
    description: z.string().optional(),
    required: z.boolean().default(false),
    defaultValue: z.unknown(),
    options: z.array(z.object({
      label: z.string(),
      value: z.unknown(),
    })).optional(),
    validation: z.object({
      pattern: z.string().optional(),
      minLength: z.number().optional(),
      maxLength: z.number().optional(),
    }).optional(),
  })).default([]),
  dependencies: z.array(z.string()).default([]),
  devDependencies: z.array(z.string()).default([]),
  scripts: z.record(z.string()).default({}),
  tags: z.array(z.string()).default([]),
});

export type Template = z.infer<typeof TemplateSchema>;

export const ScaffoldConfigSchema = z.object({
  templateId: z.string(),
  outputDir: z.string(),
  parameters: z.record(z.unknown()),
  overwriteExisting: z.boolean().default(false),
  installDependencies: z.boolean().default(true),
  initializeGit: z.boolean().default(false),
});

export type ScaffoldConfig = z.infer<typeof ScaffoldConfigSchema>;

export interface ScaffoldResult {
  success: boolean;
  outputDir: string;
  filesCreated: string[];
  messages: string[];
  warnings: string[];
  errors: string[];
}

export interface InteractivePrompt {
  type: 'input' | 'number' | 'confirm' | 'list' | 'checkbox';
  name: string;
  message: string;
  default?: unknown;
  choices?: Array<{ name: string; value: unknown }>;
  validate?: (value: unknown) => boolean | string;
}

export interface TemplateRegistry {
  [key: string]: Template;
}
