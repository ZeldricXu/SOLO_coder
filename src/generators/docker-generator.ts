import fs from 'fs-extra';
import path from 'path';
import yaml from 'yaml';
import type { ProjectConfig, DockerConfig } from '../types.js';
import { DEFAULT_DOCKER_CONFIG } from '../types.js';

export class DockerGenerator {
  private config: ProjectConfig;
  private dockerConfig: DockerConfig;
  private targetDir: string;

  constructor(config: ProjectConfig, dockerConfig: Partial<DockerConfig> = {}) {
    this.config = config;
    this.dockerConfig = { ...DEFAULT_DOCKER_CONFIG, ...dockerConfig };
    this.targetDir = config.targetDir;
  }

  async generate(): Promise<void> {
    if (!this.config.useDocker) return;

    await Promise.all([
      this.generateDockerfile(),
      this.generateDockerCompose(),
      this.generateDockerIgnore(),
    ]);

    if (this.config.deployTarget === 'k8s') {
      await this.generateK8sManifests();
    }
  }

  private async generateDockerfile(): Promise<void> {
    const dockerfile = this.getDockerfileContent();
    await fs.writeFile(path.join(this.targetDir, 'Dockerfile'), dockerfile, 'utf-8');
  }

  private getDockerfileContent(): string {
    const nodeVersion = this.dockerConfig.nodeVersion;
    const isFrontend = this.isFrontend();
    const exposePort = this.getExposePort();

    if (isFrontend) {
      return this.getFrontendDockerfile(nodeVersion, exposePort);
    }

    return this.getBackendDockerfile(nodeVersion, exposePort);
  }

  private getBackendDockerfile(nodeVersion: string, exposePort: number): string {
    const pm = this.config.packageManager;
    const lockFile = this.getLockFile();

    return `# --- Builder Stage ---
FROM node:${nodeVersion}-alpine AS builder

WORKDIR /app

# Install build dependencies
RUN apk add --no-cache python3 make g++

# Copy package files
COPY package.json ${lockFile}* ./

# Install dependencies
RUN ${this.getPmInstallCommand(pm, true)}

# Copy source
COPY . .

# Build
RUN ${this.getPmRunCommand(pm, 'build')}

# --- Runner Stage ---
FROM node:${nodeVersion}-alpine AS runner

WORKDIR /app

# Set production environment
ENV NODE_ENV=production \
    PORT=${exposePort} \
    HOST=0.0.0.0

# Install runtime dependencies
RUN apk add --no-cache curl tini

# Create non-root user
RUN addgroup --system --gid 1001 nodejs && \
    adduser --system --uid 1001 nodejs

# Copy package files
COPY package.json ${lockFile}* ./

# Install production dependencies only
RUN ${this.getPmInstallCommand(pm, true, true)}

# Copy built artifacts from builder
COPY --from=builder --chown=nodejs:nodejs /app/dist ./dist

# Use non-root user
USER nodejs

# Expose port
EXPOSE ${exposePort}

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \\
  CMD curl -f http://localhost:${exposePort}/health || exit 1

# Start with tini for proper signal handling
ENTRYPOINT ["/sbin/tini", "--"]

# Start application
CMD ["node", "dist/index.js"]
`;
  }

  private getFrontendDockerfile(nodeVersion: string, exposePort: number): string {
    const pm = this.config.packageManager;
    const lockFile = this.getLockFile();

    return `# --- Builder Stage ---
FROM node:${nodeVersion}-alpine AS builder

WORKDIR /app

# Copy package files
COPY package.json ${lockFile}* ./

# Install dependencies
RUN ${this.getPmInstallCommand(pm, true)}

# Copy source
COPY . .

# Build
RUN ${this.getPmRunCommand(pm, 'build')}

# --- Runner Stage (Nginx) ---
FROM nginx:alpine AS runner

# Copy built artifacts
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy nginx config
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Expose port
EXPOSE ${exposePort}

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \\
  CMD wget --quiet --tries=1 --spider http://localhost:${exposePort}/ || exit 1

# Start Nginx
CMD ["nginx", "-g", "daemon off;"]
`;
  }

  private getLockFile(): string {
    const lockFiles: Record<string, string> = {
      npm: 'package-lock.json',
      yarn: 'yarn.lock',
      pnpm: 'pnpm-lock.yaml',
    };
    return lockFiles[this.config.packageManager] ?? 'package-lock.json';
  }

  private getPmInstallCommand(pm: string, frozen: boolean, production = false): string {
    const commands: Record<string, (frozen: boolean, prod: boolean) => string> = {
      npm: (f, p) => p
        ? `npm ci --omit=dev ${f ? '' : ''}`
        : `npm ${f ? 'ci' : 'install'}`,
      yarn: (f, p) => p
        ? `yarn install --production --frozen-lockfile`
        : `yarn install ${f ? '--frozen-lockfile' : ''}`,
      pnpm: (f, p) => p
        ? `pnpm install --prod --frozen-lockfile`
        : `pnpm install ${f ? '--frozen-lockfile' : ''}`,
    };
    return commands[pm]?.(frozen, production) ?? 'npm install';
  }

  private getPmRunCommand(pm: string, script: string): string {
    const commands: Record<string, string> = {
      npm: `npm run ${script}`,
      yarn: `yarn ${script}`,
      pnpm: `pnpm ${script}`,
    };
    return commands[pm] ?? `npm run ${script}`;
  }

  private getExposePort(): number {
    if (this.config.framework === 'node-backend') return 3000;
    if (this.config.framework === 'react-frontend') return 80;
    if (this.config.framework === 'vue-frontend') return 80;
    if (this.config.framework === 'cli-tool') return 3000;
    return 3000;
  }

  private isFrontend(): boolean {
    return this.config.framework === 'react-frontend' || this.config.framework === 'vue-frontend';
  }

  private async generateDockerCompose(): Promise<void> {
    const composeConfig = this.getDockerComposeConfig();
    const yamlContent = yaml.stringify(composeConfig, {
      indent: 2,
      lineWidth: 120,
    });

    await fs.writeFile(path.join(this.targetDir, 'docker-compose.yml'), yamlContent, 'utf-8');

    if (this.isFrontend()) {
      await this.generateNginxConfig();
    }
  }

  private getDockerComposeConfig(): Record<string, unknown> {
    const isBackend = this.config.framework === 'node-backend';
    const isFrontend = this.isFrontend();
    const exposePort = this.getExposePort();

    const services: Record<string, unknown> = {};

    if (isBackend) {
      services['app'] = {
        build: {
          context: '.',
          dockerfile: 'Dockerfile',
          target: 'builder',
        },
        command: 'npm run dev',
        ports: [`${exposePort}:${exposePort}`],
        volumes: [
          './src:/app/src',
          './package.json:/app/package.json',
        ],
        environment: {
          NODE_ENV: 'development',
          PORT: exposePort,
          HOST: '0.0.0.0',
          DATABASE_URL: this.dockerConfig.usePostgres
            ? 'postgresql://postgres:postgres@postgres:5432/' + this.config.projectName
            : undefined,
          REDIS_URL: this.dockerConfig.useRedis ? 'redis://redis:6379' : undefined,
        },
        depends_on: this.getDependsOn(),
        networks: ['app-network'],
      };
    } else if (isFrontend) {
      services['app'] = {
        build: {
          context: '.',
          dockerfile: 'Dockerfile',
          target: 'builder',
        },
        command: 'npm run dev',
        ports: [`${exposePort}:${exposePort}`],
        volumes: [
          './src:/app/src',
          './public:/app/public',
          './package.json:/app/package.json',
        ],
        environment: {
          NODE_ENV: 'development',
        },
        networks: ['app-network'],
      };
    } else {
      services['app'] = {
        build: {
          context: '.',
          dockerfile: 'Dockerfile',
        },
        volumes: [
          './src:/app/src',
          './package.json:/app/package.json',
        ],
        networks: ['app-network'],
      };
    }

    if (isBackend && this.dockerConfig.usePostgres) {
      services['postgres'] = {
        image: `postgres:${this.dockerConfig.postgresVersion}-alpine`,
        environment: {
          POSTGRES_USER: 'postgres',
          POSTGRES_PASSWORD: 'postgres',
          POSTGRES_DB: this.config.projectName,
        },
        ports: ['5432:5432'],
        volumes: ['postgres-data:/var/lib/postgresql/data'],
        healthcheck: {
          test: ['CMD-SHELL', 'pg_isready -U postgres'],
          interval: '10s',
          timeout: '5s',
          retries: 5,
        },
        networks: ['app-network'],
      };
    }

    if (isBackend && this.dockerConfig.useRedis) {
      services['redis'] = {
        image: `redis:${this.dockerConfig.redisVersion}-alpine`,
        ports: ['6379:6379'],
        volumes: ['redis-data:/data'],
        healthcheck: {
          test: ['CMD', 'redis-cli', 'ping'],
          interval: '10s',
          timeout: '5s',
          retries: 5,
        },
        networks: ['app-network'],
      };
    }

    const config: Record<string, unknown> = {
      version: '3.8',
      services,
      networks: {
        'app-network': {
          driver: 'bridge',
        },
      },
    };

    const volumes: Record<string, unknown> = {};
    if (isBackend && this.dockerConfig.usePostgres) {
      volumes['postgres-data'] = { driver: 'local' };
    }
    if (isBackend && this.dockerConfig.useRedis) {
      volumes['redis-data'] = { driver: 'local' };
    }
    if (Object.keys(volumes).length > 0) {
      config['volumes'] = volumes;
    }

    return config;
  }

  private getDependsOn(): Record<string, unknown> | undefined {
    if (this.config.framework !== 'node-backend') return undefined;

    const dependsOn: Record<string, { condition: string }> = {};

    if (this.dockerConfig.usePostgres) {
      dependsOn['postgres'] = { condition: 'service_healthy' };
    }
    if (this.dockerConfig.useRedis) {
      dependsOn['redis'] = { condition: 'service_healthy' };
    }

    return Object.keys(dependsOn).length > 0 ? dependsOn : undefined;
  }

  private async generateNginxConfig(): Promise<void> {
    const nginxConfig = `server {
    listen 80;
    server_name localhost;

    root /usr/share/nginx/html;
    index index.html;

    # Gzip compression
    gzip on;
    gzip_vary on;
    gzip_min_length 1024;
    gzip_types text/plain text/css text/xml text/javascript application/x-javascript application/xml+rss application/json application/javascript;

    # Main SPA route
    location / {
        try_files $$uri $$uri/ /index.html;
    }

    # Cache static assets
    location ~* \\.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # Security headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
}
`;
    await fs.writeFile(path.join(this.targetDir, 'nginx.conf'), nginxConfig, 'utf-8');
  }

  private async generateDockerIgnore(): Promise<void> {
    const dockerignore = [
      'node_modules',
      'npm-debug.log',
      'yarn-error.log',
      '.git',
      '.gitignore',
      '.env',
      '.env.*',
      'dist',
      'build',
      'coverage',
      '.nyc_output',
      '.idea',
      '.vscode',
      '*.swp',
      '*.swo',
      '.DS_Store',
      'docker-compose.yml',
      'Dockerfile',
      '.dockerignore',
      'README.md',
      '*.md',
      'tests',
    ].join('\n');

    await fs.writeFile(path.join(this.targetDir, '.dockerignore'), dockerignore + '\n', 'utf-8');
  }

  private async generateK8sManifests(): Promise<void> {
    const k8sDir = path.join(this.targetDir, 'k8s');
    await fs.ensureDir(k8sDir);

    const port = this.getExposePort();

    const deployment = {
      apiVersion: 'apps/v1',
      kind: 'Deployment',
      metadata: {
        name: this.config.projectName,
        labels: {
          app: this.config.projectName,
        },
      },
      spec: {
        replicas: 3,
        selector: {
          matchLabels: {
            app: this.config.projectName,
          },
        },
        strategy: {
          type: 'RollingUpdate',
          rollingUpdate: {
            maxSurge: 1,
            maxUnavailable: 0,
          },
        },
        template: {
          metadata: {
            labels: {
              app: this.config.projectName,
            },
          },
          spec: {
            containers: [
              {
                name: this.config.projectName,
                image: `${this.config.projectName}:latest`,
                ports: [
                  {
                    containerPort: port,
                  },
                ],
                env: [
                  {
                    name: 'NODE_ENV',
                    value: 'production',
                  },
                  {
                    name: 'PORT',
                    value: port.toString(),
                  },
                ],
                livenessProbe: {
                  httpGet: {
                    path: this.isFrontend() ? '/' : '/health',
                    port,
                  },
                  initialDelaySeconds: 30,
                  periodSeconds: 10,
                  timeoutSeconds: 5,
                  failureThreshold: 3,
                },
                readinessProbe: {
                  httpGet: {
                    path: this.isFrontend() ? '/' : '/health',
                    port,
                  },
                  initialDelaySeconds: 5,
                  periodSeconds: 5,
                  timeoutSeconds: 3,
                  failureThreshold: 3,
                },
                resources: {
                  requests: {
                    cpu: '100m',
                    memory: '128Mi',
                  },
                  limits: {
                    cpu: '500m',
                    memory: '512Mi',
                  },
                },
              },
            ],
          },
        },
      },
    };

    const service = {
      apiVersion: 'v1',
      kind: 'Service',
      metadata: {
        name: this.config.projectName,
        labels: {
          app: this.config.projectName,
        },
      },
      spec: {
        type: 'ClusterIP',
        selector: {
          app: this.config.projectName,
        },
        ports: [
          {
            port: 80,
            targetPort: port,
            protocol: 'TCP',
          },
        ],
      },
    };

    const ingress = {
      apiVersion: 'networking.k8s.io/v1',
      kind: 'Ingress',
      metadata: {
        name: this.config.projectName,
        annotations: {
          'nginx.ingress.kubernetes.io/ssl-redirect': 'true',
          'nginx.ingress.kubernetes.io/use-regex': 'true',
        },
      },
      spec: {
        ingressClassName: 'nginx',
        rules: [
          {
            host: `${this.config.projectName}.example.com`,
            http: {
              paths: [
                {
                  path: '/',
                  pathType: 'Prefix',
                  backend: {
                    service: {
                      name: this.config.projectName,
                      port: {
                        number: 80,
                      },
                    },
                  },
                },
              ],
            },
          },
        ],
        tls: [
          {
            hosts: [`${this.config.projectName}.example.com`],
            secretName: `${this.config.projectName}-tls`,
          },
        ],
      },
    };

    const kustomization = {
      apiVersion: 'kustomize.config.k8s.io/v1beta1',
      kind: 'Kustomization',
      resources: [
        'deployment.yaml',
        'service.yaml',
        'ingress.yaml',
      ],
      commonLabels: {
        app: this.config.projectName,
      },
    };

    await fs.writeFile(path.join(k8sDir, 'deployment.yaml'), yaml.stringify(deployment, { indent: 2 }), 'utf-8');
    await fs.writeFile(path.join(k8sDir, 'service.yaml'), yaml.stringify(service, { indent: 2 }), 'utf-8');
    await fs.writeFile(path.join(k8sDir, 'ingress.yaml'), yaml.stringify(ingress, { indent: 2 }), 'utf-8');
    await fs.writeFile(path.join(k8sDir, 'kustomization.yaml'), yaml.stringify(kustomization, { indent: 2 }), 'utf-8');
  }
}
