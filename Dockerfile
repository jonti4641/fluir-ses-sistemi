# Dockerfile - Railway için optimize edilmiş
FROM eclipse-temurin:17-jdk-alpine AS builder

# Maven yükle
RUN apk add --no-cache maven

WORKDIR /app

# Bağımlılıkları önce kopyala (Docker cache için)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Kaynak kodu kopyala ve derle
COPY src ./src
RUN mvn package -DskipTests -B

# ========================
# Runtime aşaması
# ========================
FROM eclipse-temurin:17-jre-alpine

# FFmpeg yükle (ses işleme için gerekli)
RUN apk add --no-cache ffmpeg opus-dev

WORKDIR /app

# Derlenmiş JAR'ı kopyala
COPY --from=builder /app/target/fluir-ses-sistemi-1.0.0.jar app.jar

# Logs dizini oluştur
RUN mkdir -p logs

# Bot çalıştır
CMD ["java", "-Xmx512m", "-Xms256m", "-jar", "app.jar"]
