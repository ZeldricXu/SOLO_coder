"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
const core_1 = require("@oclif/core");
const path = __importStar(require("path"));
const AppConfigLoader_1 = require("../utils/AppConfigLoader");
class InitCommand extends core_1.Command {
    static description = 'Initialize a new ConfigFlow project in the current directory';
    static examples = [
        '<%= config.bin %> <%= command.id %>',
        '<%= config.bin %> <%= command.id %> --force',
    ];
    static flags = {
        force: core_1.Flags.boolean({ char: 'f', description: 'Overwrite existing config' }),
        output: core_1.Flags.string({ char: 'o', description: 'Output directory' }),
    };
    async run() {
        const { flags } = await this.parse(InitCommand);
        const outputDir = path.resolve(flags.output || process.cwd());
        const existingConfigPath = path.join(outputDir, 'config-flow.yaml');
        const fs = await Promise.resolve().then(() => __importStar(require('fs')));
        if (!flags.force) {
            if (fs.existsSync(existingConfigPath)) {
                this.error(`Config already exists at ${existingConfigPath}. Use --force to overwrite.`);
            }
        }
        if (!fs.existsSync(outputDir)) {
            fs.mkdirSync(outputDir, { recursive: true });
        }
        const configPath = (0, AppConfigLoader_1.generateSampleConfig)(outputDir);
        const schemaPath = (0, AppConfigLoader_1.generateSampleSchema)(outputDir);
        const configDir = path.join(outputDir, '.config-flow');
        if (!fs.existsSync(configDir)) {
            fs.mkdirSync(configDir, { recursive: true });
        }
        const templatesDir = path.join(outputDir, 'templates');
        if (!fs.existsSync(templatesDir)) {
            fs.mkdirSync(templatesDir, { recursive: true });
            fs.writeFileSync(path.join(templatesDir, 'nginx.conf.hbs'), `server {
    listen {{ app.port }};
    server_name {{ app.host | default "localhost" }};

    {{#if app.debug}}
    # Debug mode enabled
    {{/if}}

    {{#each app.upstreams as |upstream|}}
    upstream {{upstream.name}} {
        {{#each upstream.servers as |server|}}
        server {{server.host}}:{{server.port}};
        {{/each}}
    }
    {{/each}}

    location / {
        proxy_pass http://{{app.upstreamName | default "app"}};
    }
}
`);
            fs.writeFileSync(path.join(templatesDir, 'application.properties.hbs'), `# Generated for environment: {{_meta.environment}} at {{_meta.renderedAt}}
server.port={{app.port}}
spring.application.name={{app.name}}
spring.datasource.url=jdbc:postgresql://{{database.host}}:{{database.port}}/{{database.name}}
spring.datasource.username={{database.username}}
spring.datasource.password={{database.password}}
{{#if rateLimit}}
# Rate limiting
rate-limit.max-requests={{rateLimit.maxRequests}}
rate-limit.window-ms={{rateLimit.windowMs}}
{{/if}}
`);
            fs.writeFileSync(path.join(templatesDir, 'docker-compose.override.yml.hbs'), `version: '3.8'
services:
  app:
    environment:
      NODE_ENV: {{app.environment}}
      DEBUG: {{app.debug}}
    ports:
      - "{{app.port}}:{{app.port}}"
    {{#if rateLimit}}
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 512M
    {{/if}}
`);
        }
        const config = (0, AppConfigLoader_1.loadConfig)(configPath);
        const errors = (0, AppConfigLoader_1.validateCliConfig)(config);
        if (errors.length > 0) {
            this.warn('Generated config has validation issues:');
            for (const err of errors) {
                this.warn(`  - ${err}`);
            }
        }
        this.log('✅ ConfigFlow project initialized!');
        this.log('');
        this.log(`📁 Config file:    ${configPath}`);
        this.log(`📋 Schema file:    ${schemaPath}`);
        this.log(`📦 Storage dir:    ${path.join(outputDir, '.config-flow')}`);
        this.log(`📝 Templates dir:  ${templatesDir}`);
        this.log('');
        this.log('Next steps:');
        this.log('  1. Edit config-flow.yaml with your environment configurations');
        this.log('  2. Update config-schema.json to define your validation rules');
        this.log('  3. Add templates to templates/ directory or use existing ones');
        this.log('  4. Run `config-flow env list` to see configured environments');
    }
}
exports.default = InitCommand;
//# sourceMappingURL=init.js.map