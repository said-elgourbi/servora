FROM node:24-alpine AS build
WORKDIR /app/web
COPY web/package.json web/package-lock.json ./
COPY packages/shared-types /app/packages/shared-types
COPY packages/shared-config /app/packages/shared-config
RUN npm ci
COPY web/ .
RUN npm run build

FROM nginx:stable-alpine AS runtime
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/web/dist/servora-web/browser /usr/share/nginx/html
EXPOSE 80

