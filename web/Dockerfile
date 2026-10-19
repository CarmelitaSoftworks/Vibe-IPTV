# Vibe IPTV — imagen para servidor propio (NAS, Raspberry Pi, mini PC, VPS)
FROM node:22-alpine
RUN apk add --no-cache ffmpeg tini
WORKDIR /app
COPY . .
RUN rm -rf node_modules test \
 && npm install --omit=dev --ignore-scripts --no-audit --no-fund
ENV HOST=0.0.0.0 \
    PORT=8080 \
    FFMPEG_PATH=/usr/bin/ffmpeg \
    NODE_ENV=production
EXPOSE 8080
USER node
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- "http://127.0.0.1:${PORT}/proxy/health" >/dev/null 2>&1 || exit 1
ENTRYPOINT ["/sbin/tini", "--"]
CMD ["node", "server/server.js"]
