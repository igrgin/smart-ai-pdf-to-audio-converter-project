# syntax=docker/dockerfile:1.7
FROM node:24.18.1-alpine AS build
WORKDIR /workspace
ARG VITE_OFFLINE_AUTHORIZATION_PUBLIC_KEY
ENV VITE_OFFLINE_AUTHORIZATION_PUBLIC_KEY=${VITE_OFFLINE_AUTHORIZATION_PUBLIC_KEY}
RUN npm install --global npm@11.8.0
COPY package.json package-lock.json ./
COPY apps/web/package.json apps/web/package.json
RUN npm ci --ignore-scripts
COPY apps/web apps/web
RUN npm run build --workspace @audiobook/web

FROM nginxinc/nginx-unprivileged:1.31-alpine3.24
COPY apps/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY apps/web/security-headers.conf /etc/nginx/security-headers.conf
COPY --from=build /workspace/apps/web/dist /usr/share/nginx/html
EXPOSE 8080
