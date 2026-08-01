# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-25-alpine AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY apps/pdfbox-helper/pom.xml apps/pdfbox-helper/pom.xml
COPY apps/core/pom.xml apps/core/pom.xml
RUN ./mvnw --batch-mode --no-transfer-progress -pl apps/core,apps/pdfbox-helper -am dependency:go-offline
COPY apps/core/src apps/core/src
COPY apps/pdfbox-helper/src apps/pdfbox-helper/src
RUN ./mvnw --batch-mode --no-transfer-progress -pl apps/core,apps/pdfbox-helper -am package -DskipTests

FROM eclipse-temurin:25.0.3_9-jre-noble
ARG DOCLING_VERSION=2.104.0
ARG TORCH_VERSION=2.9.1
ARG TORCHVISION_VERSION=0.24.1
ARG PILLOW_VERSION=12.3.0
ARG SETUPTOOLS_VERSION=83.0.0
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends \
        clamav ffmpeg libgl1 python3 python3-pip qpdf tesseract-ocr tesseract-ocr-eng \
    && freshclam --no-warnings \
    && rm -rf /var/lib/apt/lists/*
RUN python3 -m pip install --break-system-packages --no-cache-dir \
        --index-url https://download.pytorch.org/whl/cpu \
        "torch==${TORCH_VERSION}" "torchvision==${TORCHVISION_VERSION}"
RUN python3 -m pip install --break-system-packages --no-cache-dir "docling==${DOCLING_VERSION}" \
    && python3 -m pip install --break-system-packages --no-cache-dir \
        "pillow==${PILLOW_VERSION}" "setuptools==${SETUPTOOLS_VERSION}" \
    && python3 -m pip check
RUN docling-tools models download --output-dir /opt/docling-models layout tableformer \
    && groupadd --system platform \
    && useradd --system --gid platform --uid 10001 platform
ENV DOCLING_ARTIFACTS_PATH=/opt/docling-models \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1
WORKDIR /app
COPY --from=build --chown=platform:platform /workspace/apps/core/target/platform-core-exec.jar /app/platform-core.jar
COPY --from=build --chown=platform:platform /workspace/apps/pdfbox-helper/target/pdfbox-helper-0.1.0-SNAPSHOT-all.jar /app/pdfbox-helper.jar
COPY --chown=platform:platform scripts/docling_extract.py /app/bin/docling_extract.py
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/platform-core.jar"]
