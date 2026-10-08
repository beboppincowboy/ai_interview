FROM node:22-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Empty means same origin: the browser calls /api on this server and nginx forwards it.
ARG VITE_API_BASE_URL=
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
# "all" builds a demo image that runs on mock data; production keeps mocks off.
ARG VITE_API_MOCKS=off
ENV VITE_API_MOCKS=${VITE_API_MOCKS}
# Shows the spoken-interview card. Only the local docker-compose.voice.yml override sets it; images stay voice-off.
ARG VITE_VOICE_ENABLED=false
ENV VITE_VOICE_ENABLED=${VITE_VOICE_ENABLED}
RUN npm run build

FROM nginxinc/nginx-unprivileged:alpine AS runtime

# Where nginx forwards /api. Override per environment, e.g. a Kubernetes service FQDN.
ENV API_UPSTREAM=http://api:8080
# Lets the entrypoint fill NGINX_LOCAL_RESOLVERS from /etc/resolv.conf for the template's resolver line.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1

COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 3000
