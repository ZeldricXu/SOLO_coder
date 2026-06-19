"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.DockerGenerator = void 0;
const fs_extra_1 = __importDefault(require("fs-extra"));
const path_1 = __importDefault(require("path"));
const yaml_1 = __importDefault(require("yaml"));
const types_js_1 = require("../types.js");
class DockerGenerator {
    config;
    dockerConfig;
    targetDir;
    constructor(config, dockerConfig = {}) {
        this.config = config;
        this.dockerConfig = { ...types_js_1.DEFAULT_DOCKER_CONFIG, ...dockerConfig };
        this.targetDir = config.targetDir;
    }
    async generate() {
        if (!this.config.useDocker)
            return;
        await Promise.all([
            this.generateDockerfile(),
            this.generateDockerCompose(),
            this.generateDockerIgnore(),
        ]);
        if (this.config.deployTarget === 'k8s') {
            await this.generateK8sManifests();
        }
    }
    async generateDockerfile() {
        const dockerfile = this.getDockerfileContent();
        await fs_extra_1.default.writeFile(path_1.default.join(this.targetDir, 'Dockerfile'), dockerfile, 'utf-8');
    }
    getDockerfileContent() {
        const nodeVersion = this.dockerConfig.nodeVersion;
        const isFrontend = this.isFrontend();
        const exposePort = this.getExposePort();
        if (isFrontend) {
            return this.getFrontendDockerfile(nodeVersion, exposePort);
        }
        return this.getBackendDockerfile(nodeVersion, exposePort);
    }
    getBackendDockerfile(nodeVersion, exposePort) {
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
    getFrontendDockerfile(nodeVersion, exposePort) {
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
    getLockFile() {
        const lockFiles = {
            npm: 'package-lock.json',
            yarn: 'yarn.lock',
            pnpm: 'pnpm-lock.yaml',
        };
        return lockFiles[this.config.packageManager] ?? 'package-lock.json';
    }
    getPmInstallCommand(pm, frozen, production = false) {
        const commands = {
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
    getPmRunCommand(pm, script) {
        const commands = {
            npm: `npm run ${script}`,
            yarn: `yarn ${script}`,
            pnpm: `pnpm ${script}`,
        };
        return commands[pm] ?? `npm run ${script}`;
    }
    getExposePort() {
        if (this.config.framework === 'node-backend')
            return 3000;
        if (this.config.framework === 'react-frontend')
            return 80;
        if (this.config.framework === 'vue-frontend')
            return 80;
        if (this.config.framework === 'cli-tool')
            return 3000;
        return 3000;
    }
    isFrontend() {
        return this.config.framework === 'react-frontend' || this.config.framework === 'vue-frontend';
    }
    async generateDockerCompose() {
        const composeConfig = this.getDockerComposeConfig();
        const yamlContent = yaml_1.default.stringify(composeConfig, {
            indent: 2,
            lineWidth: 120,
        });
        await fs_extra_1.default.writeFile(path_1.default.join(this.targetDir, 'docker-compose.yml'), yamlContent, 'utf-8');
        if (this.isFrontend()) {
            await this.generateNginxConfig();
        }
    }
    getDockerComposeConfig() {
        const isBackend = this.config.framework === 'node-backend';
        const isFrontend = this.isFrontend();
        const exposePort = this.getExposePort();
        const services = {};
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
        }
        else if (isFrontend) {
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
        }
        else {
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
        const config = {
            version: '3.8',
            services,
            networks: {
                'app-network': {
                    driver: 'bridge',
                },
            },
        };
        const volumes = {};
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
    getDependsOn() {
        if (this.config.framework !== 'node-backend')
            return undefined;
        const dependsOn = {};
        if (this.dockerConfig.usePostgres) {
            dependsOn['postgres'] = { condition: 'service_healthy' };
        }
        if (this.dockerConfig.useRedis) {
            dependsOn['redis'] = { condition: 'service_healthy' };
        }
        return Object.keys(dependsOn).length > 0 ? dependsOn : undefined;
    }
    async generateNginxConfig() {
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
        await fs_extra_1.default.writeFile(path_1.default.join(this.targetDir, 'nginx.conf'), nginxConfig, 'utf-8');
    }
    async generateDockerIgnore() {
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
        await fs_extra_1.default.writeFile(path_1.default.join(this.targetDir, '.dockerignore'), dockerignore + '\n', 'utf-8');
    }
    async generateK8sManifests() {
        const k8sDir = path_1.default.join(this.targetDir, 'k8s');
        await fs_extra_1.default.ensureDir(k8sDir);
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
        await fs_extra_1.default.writeFile(path_1.default.join(k8sDir, 'deployment.yaml'), yaml_1.default.stringify(deployment, { indent: 2 }), 'utf-8');
        await fs_extra_1.default.writeFile(path_1.default.join(k8sDir, 'service.yaml'), yaml_1.default.stringify(service, { indent: 2 }), 'utf-8');
        await fs_extra_1.default.writeFile(path_1.default.join(k8sDir, 'ingress.yaml'), yaml_1.default.stringify(ingress, { indent: 2 }), 'utf-8');
        await fs_extra_1.default.writeFile(path_1.default.join(k8sDir, 'kustomization.yaml'), yaml_1.default.stringify(kustomization, { indent: 2 }), 'utf-8');
    }
}
exports.DockerGenerator = DockerGenerator;
//# sourceMappingURL=docker-generator.js.map