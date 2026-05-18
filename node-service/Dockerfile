# =====================================================
# Dockerfile - Nodo del Algoritmo Bully
# Build multi-etapa para imagen mínima en producción
# =====================================================

# --- ETAPA 1: Compilación con Maven ---
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copiar POM primero para cachear dependencias
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copiar fuentes y compilar
COPY src ./src
RUN mvn package -DskipTests -q

# --- ETAPA 2: Imagen de ejecución mínima ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Instalar curl para health checks
RUN apk add --no-cache curl

# Usuario no-root por seguridad
RUN addgroup -S bully && adduser -S bully -G bully
USER bully

# Copiar JAR desde etapa de build
COPY --from=builder /build/target/bully-node-*.jar app.jar

# Puerto del servicio REST
EXPOSE 8080

# Health check para Docker
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/bully/health || exit 1

# JVM tuning para contenedores
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
