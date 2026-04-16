FROM eclipse-temurin:17-jre-jammy

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    VIRTUAL_ENV=/opt/venv \
    PATH=/opt/venv/bin:$PATH

WORKDIR /app

RUN printf 'Acquire::ForceIPv4 "true";\n' > /etc/apt/apt.conf.d/99force-ipv4 \
    && apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip python3-venv ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*

COPY app.jar ./app.jar
COPY scripts/requirements.txt ./requirements.txt
COPY scripts ./backend/scripts

RUN python3 -m venv "$VIRTUAL_ENV" \
    && python3 -m pip install --upgrade pip \
    && pip install -r requirements.txt \
    && pip install yt-dlp \
    && mkdir -p /app/backend/runtime/tasks

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
