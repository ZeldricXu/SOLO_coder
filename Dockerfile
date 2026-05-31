FROM node:20-alpine AS base

WORKDIR /app

RUN apk add --no-cache curl tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

FROM base AS deps

COPY package.json package-lock.json* ./
RUN npm ci --omit=dev

FROM base AS builder

COPY package.json package-lock.json* tsconfig.json ./
COPY --from=deps /app/node_modules ./node_modules
COPY src ./src

RUN npm run build

FROM base AS production

ENV NODE_ENV=production
ENV PORT=3000
ENV HOST=0.0.0.0

COPY --from=deps /app/node_modules ./node_modules
COPY --from=builder /app/dist ./dist
COPY package.json ./
COPY .env.production ./.env

RUN addgroup -S app && adduser -S app -G app
USER app

EXPOSE 3000 9090

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:3000/health || exit 1

CMD ["node", "dist/app.js"]
