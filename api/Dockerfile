# Servora API — container image
#
# A shared build stage, an explicit local development target, and a lean default
# runtime target containing only production dependencies and compiled output.

# ---- build stage ------------------------------------------------------------
FROM node:24-alpine AS build
WORKDIR /app/api
COPY api/package.json api/package-lock.json ./
COPY packages/shared-types /app/packages/shared-types
RUN npm ci
COPY api/ .
RUN npm run build

# Local Compose uses development-mode browser cookies and logging. Keep the
# development dependencies (including pino-pretty) in that explicit target.
FROM build AS development
ENV NODE_ENV=development
USER node
EXPOSE 3000
CMD ["node", "dist/main.js"]

# ---- runtime stage ----------------------------------------------------------
FROM node:24-alpine AS runtime
ENV NODE_ENV=production
WORKDIR /app/api
COPY api/package.json api/package-lock.json ./
COPY packages/shared-types /app/packages/shared-types
RUN npm ci --omit=dev && npm cache clean --force
COPY --from=build /app/api/dist ./dist
COPY api/drizzle ./drizzle
USER node
EXPOSE 3000
CMD ["node", "dist/main.js"]
