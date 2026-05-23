# syntax=docker/dockerfile:1

FROM node:22-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . ./

ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

RUN npm run build

FROM nginx:1.29-alpine

COPY nginx/default.http.conf.template /etc/nginx/custom-templates/default.http.conf.template
COPY nginx/default.https.conf.template /etc/nginx/custom-templates/default.https.conf.template
COPY nginx/start-nginx.sh /usr/local/bin/start-nginx.sh
COPY --from=build /app/dist /usr/share/nginx/html

RUN chmod +x /usr/local/bin/start-nginx.sh \
    && mkdir -p /var/www/certbot /etc/nginx/custom-templates

EXPOSE 80 443

CMD ["/usr/local/bin/start-nginx.sh"]
