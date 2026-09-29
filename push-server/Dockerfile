# BlockMail-Push-Server
FROM node:22-alpine

ENV NODE_ENV=production \
    DATA_DIR=/data \
    PORT=8787

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY src ./src

# Datenordner (verschlüsselte Ablage) gehört dem unprivilegierten Benutzer "node"
RUN mkdir -p /data && chown node:node /data
USER node
VOLUME ["/data"]
EXPOSE 8787

HEALTHCHECK --interval=60s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/health" >/dev/null || exit 1

CMD ["node", "src/index.js"]
