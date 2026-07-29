# ==============================================
# AŞAMA 1: BUILD - Maven ile derle (Debian tabanlı)
# ==============================================
FROM maven:3.9-eclipse-temurin-25-noble AS builder

WORKDIR /app

# Önce sadece pom.xml kopyala (bağımlılık cache)
COPY pom.xml .
RUN mvn dependency:go-offline -B --no-transfer-progress

# Kaynak kodu kopyala ve derle
COPY src ./src
RUN mvn package -DskipTests -B --no-transfer-progress

# ==============================================
# AŞAMA 2: RUNTIME - Debian tabanlı JRE (musl değil, glibc!)
# ==============================================
FROM eclipse-temurin:25-jre-noble

# FFmpeg, Opus ve gerekli native kütüphaneler (glibc ile tam uyumlu)
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    libopus0 \
    libopus-dev \
    libstdc++6 \
    libgcc-s1 \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Derlenmiş FAT JAR'ı kopyala
COPY --from=builder /app/target/fluir-ses-sistemi-1.0.0.jar app.jar

# Log dizini
RUN mkdir -p logs

# JVM ayarları: gc log yok, bellek sınırı, düzgün kapanma
ENTRYPOINT ["java", \
    "--enable-native-access=ALL-UNNAMED", \
    "-Xmx450m", \
    "-Xms128m", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=100", \
    "-Dfile.encoding=UTF-8", \
    "-jar", "app.jar"]
