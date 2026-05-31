FROM node:20-alpine AS base
WORKDIR /app

FROM base AS deps
COPY package*.json ./
RUN npm ci --omit=dev

FROM base AS builder
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build:prod

FROM base AS production
ENV NODE_ENV=production

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=deps /app/node_modules ./node_modules
COPY --from=builder /app/dist ./dist
COPY package*.json ./

RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD node -e "const http = require('http'); const options = {hostname: 'localhost', port: 3000, path: '/api/v1/metrics', method: 'GET', timeout: 2000}; const req = http.request(options, (res) => {process.exit(res.statusCode === 200 ? 0 : 1)}); req.on('error', () => process.exit(1)); req.end();" || exit 1

STOPSIGNAL SIGTERM

CMD ["node", "dist/index.js"]
