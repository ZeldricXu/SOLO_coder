"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.DEPLOY_TARGET_NAMES = exports.CI_PROVIDER_NAMES = exports.PACKAGE_MANAGER_NAMES = exports.FRAMEWORK_NAMES = exports.DEFAULT_DOCKER_CONFIG = exports.DEFAULT_CI_CONFIG = void 0;
exports.DEFAULT_CI_CONFIG = {
    nodeVersion: '20',
    usePostgres: true,
    useRedis: true,
    postgresVersion: '15',
    redisVersion: '7',
};
exports.DEFAULT_DOCKER_CONFIG = {
    nodeVersion: '22',
    exposePort: 3000,
    usePostgres: true,
    useRedis: true,
    postgresVersion: '15',
    redisVersion: '7',
};
exports.FRAMEWORK_NAMES = {
    'node-backend': 'Node.js Backend',
    'react-frontend': 'React Frontend',
    'vue-frontend': 'Vue Frontend',
    'cli-tool': 'CLI Tool',
};
exports.PACKAGE_MANAGER_NAMES = {
    npm: 'npm',
    yarn: 'Yarn',
    pnpm: 'pnpm',
};
exports.CI_PROVIDER_NAMES = {
    github: 'GitHub Actions',
    gitlab: 'GitLab CI',
    none: 'None',
};
exports.DEPLOY_TARGET_NAMES = {
    docker: 'Docker / Docker Compose',
    k8s: 'Kubernetes',
    none: 'None',
};
//# sourceMappingURL=types.js.map