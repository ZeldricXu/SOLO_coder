#!/usr/bin/env node

import { Command } from 'commander';
import * as fs from 'fs';
import * as path from 'path';
import { FigmaClient, transformToTokens } from './figmaClient';
import { generateCSSVariables, generateTypeScriptTypes, generateTokenObjects, generateIndexFile } from './generator';

const program = new Command();

program
  .name('df1-57-tokens')
  .description('Design tokens synchronization CLI for DF1-57 Design System')
  .version('1.0.0');

program
  .command('sync')
  .description('Sync design tokens from Figma')
  .requiredOption('--api-key <key>', 'Figma API key')
  .requiredOption('--file-id <id>', 'Figma file ID')
  .option('--output <path>', 'Output directory for tokens', './src/theme')
  .option('--light-mode <name>', 'Name of the light mode in Figma', 'Light')
  .option('--dark-mode <name>', 'Name of the dark mode in Figma', 'Dark')
  .option('--dry-run', 'Show changes without writing files', false)
  .action(async (options) => {
    try {
      console.log('🚀 Starting Figma token sync...');
      console.log(`📁 File ID: ${options.fileId}`);
      console.log(`📂 Output: ${options.output}`);

      const client = new FigmaClient(options.apiKey, options.fileId);
      console.log('📡 Fetching variables from Figma API...');
      const response = await client.fetchVariables();

      console.log('🔄 Transforming tokens...');
      const tokens = transformToTokens(response, options.lightMode, options.darkMode);

      const outputPath = path.resolve(process.cwd(), options.output);
      if (!fs.existsSync(outputPath)) {
        fs.mkdirSync(outputPath, { recursive: true });
      }

      if (options.dryRun) {
        console.log('📋 Dry run - would generate:');
        console.log('  - variables.generated.css');
        console.log('  - types.generated.ts');
        console.log('  - tokens.generated.ts');
        console.log('  - index.generated.ts');
        console.log('\nTokens summary:');
        console.log(`  Light mode tokens: ${Object.keys(tokens.light).length} categories`);
        console.log(`  Dark mode tokens: ${Object.keys(tokens.dark).length} categories`);
        return;
      }

      console.log('📝 Generating CSS variables...');
      generateCSSVariables(tokens, outputPath);

      console.log('📝 Generating TypeScript types...');
      generateTypeScriptTypes(tokens, outputPath);

      console.log('📝 Generating token objects...');
      generateTokenObjects(tokens, outputPath);

      console.log('📝 Generating index file...');
      generateIndexFile(outputPath);

      console.log('\n✅ Token sync completed successfully!');
      console.log(`📦 Generated files in: ${outputPath}`);
    } catch (error) {
      console.error('❌ Error syncing tokens:', error);
      process.exit(1);
    }
  });

program
  .command('init')
  .description('Initialize token sync configuration')
  .option('--output <path>', 'Configuration file path', './tokens.config.json')
  .action((options) => {
    const config = {
      apiKey: 'YOUR_FIGMA_API_KEY',
      fileId: 'YOUR_FIGMA_FILE_ID',
      output: './src/theme',
      lightMode: 'Light',
      darkMode: 'Dark',
    };

    const configPath = path.resolve(process.cwd(), options.output);
    fs.writeFileSync(configPath, JSON.stringify(config, null, 2));

    console.log(`✅ Configuration file created at: ${configPath}`);
    console.log('\n⚠️  Please update the configuration with your Figma API key and file ID.');
    console.log('\nThen run:');
    console.log('  df1-57-tokens sync --api-key <key> --file-id <id>');
  });

program
  .command('list')
  .description('List available token collections from Figma')
  .requiredOption('--api-key <key>', 'Figma API key')
  .requiredOption('--file-id <id>', 'Figma file ID')
  .action(async (options) => {
    try {
      const client = new FigmaClient(options.apiKey, options.fileId);
      const response = await client.fetchVariables();

      console.log('📚 Available token collections:');
      console.log('');

      Object.values(response.meta.variableCollections).forEach((collection) => {
        console.log(`📦 ${collection.name}`);
        console.log(`   ID: ${collection.id}`);
        console.log(`   Modes:`);
        collection.modes.forEach((mode) => {
          console.log(`     - ${mode.name} (${mode.modeId})`);
        });
        console.log(`   Variables: ${collection.variableIds.length}`);
        console.log('');
      });
    } catch (error) {
      console.error('❌ Error listing collections:', error);
      process.exit(1);
    }
  });

program.parseAsync(process.argv);
