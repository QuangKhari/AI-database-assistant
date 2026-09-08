# ============================================================
# STAGE 1 - BUILD REACT
# ============================================================

FROM node:22-alpine AS build

WORKDIR /app

# Copy package files trước để tận dụng Docker cache
COPY package.json package-lock.json ./

RUN npm ci

# Copy source
COPY . .

# Production build
RUN npm run build


# ============================================================
# STAGE 2 - NGINX
# ============================================================

FROM nginx:1.27-alpine AS runtime

# Xóa config mặc định
RUN rm /etc/nginx/conf.d/default.conf

# Copy config của project
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy React build
COPY --from=build /app/dist /usr/share/nginx/html

# Nginx chạy port 80
EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
