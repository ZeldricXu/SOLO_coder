# ============================================
# Stage 1: Base - Dependencies installation
# ============================================
FROM node:20-alpine AS base

WORKDIR /app

ENV NODE_ENV=production \
    NPM_CONFIG_LOGLEVEL=warn \
    NPM_CONFIG_FUND=false \
    NPM_CONFIG_AUDIT=false

RUN apk add --no-cache \
    curl \
    openssl \
    tini \
    && rm -rf /var/cache/apk/*

COPY package*.json ./
COPY prisma ./prisma/

RUN npm ci --only=production && \
    npm cache clean --force

# ============================================
# Stage 2: Build - TypeScript compilation
# ============================================
FROM node:20-alpine AS build

WORKDIR /app

COPY --from=base /app/node_modules ./node_modules
COPY --from=base /app/package*.json ./
COPY --from=base /app/prisma ./prisma
COPY . .

RUN npm run prisma:generate && \
    npm run build:production && \
    rm -rf node_modules && \
    npm ci --only=production && \
    npm cache clean --force

# ============================================
# Stage 3: Production - Minimal runtime
# ============================================
FROM node:20-alpine AS production

LABEL org.opencontainers.image.title="ChaosLab" \
      org.opencontainers.image.description="混沌工程实验编排平台" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.vendor="ChaosLab Team" \
      org.opencontainers.image.source="https://github.com/chaoslab/chaoslab" \
      org.opencontainers.image.licenses="MIT" \
      maintainer="ChaosLab Team <dev@chaoslab.io>"

WORKDIR /app

ENV NODE_ENV=production \
    PORT=3000 \
    HOST=0.0.0.0 \
    LOG_LEVEL=info

RUN apk add --no-cache \
    curl \
    openssl \
    tini \
    && rm -rf /var/cache/apk/* \
    && addgroup -g 1001 -S nodejs \
    && adduser -S nodejs -u 1001

COPY --from=build --chown=nodejs:nodejs /app/dist ./dist
COPY --from=build --chown=nodejs:nodejs /app/node_modules ./node_modules
COPY --from=build --chown=nodejs:nodejs /app/package*.json ./
COPY --from=build --chown=nodejs:nodejs /app/prisma ./prisma

USER nodejs

EXPOSE 3000 9229

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:3000/health || exit 1

ENTRYPOINT ["/sbin/tini", "--"]

CMD ["node", "dist/index.js"]
