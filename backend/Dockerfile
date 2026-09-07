# syntax=docker/dockerfile:1

# Multi-stage, and the split is the point: the production image carries neither a compiler nor a
# test runner. `docker-compose.override.yml` (development only) builds the `dev` target instead.

FROM python:3.12-slim AS base
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    VIRTUAL_ENV=/opt/venv \
    PATH="/opt/venv/bin:$PATH"
WORKDIR /app

# build-essential lives ONLY here. asyncpg and argon2-cffi both publish manylinux wheels for
# cp312, so today nothing actually compiles — but a future dependency that has no wheel would
# otherwise fail the build with a compiler error rather than falling back to a source build, and
# the cost of carrying it in a discarded stage is zero bytes in the shipped image.
FROM base AS builder
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*
RUN python -m venv "$VIRTUAL_ENV"
# Requirements are copied alone, before the source: the layer that installs them is then cached
# across every build that only changes application code, which is nearly all of them.
COPY requirements.txt requirements-dev.txt ./
RUN pip install --no-cache-dir -r requirements.txt

FROM builder AS builder-dev
RUN pip install --no-cache-dir -r requirements-dev.txt

FROM base AS runtime
# A fixed uid, not just a name: the development override maps the host user onto this container so
# bind-mounted files land with the right owner, and that mapping needs a number it can predict.
RUN useradd --create-home --uid 1000 appuser
COPY --from=builder /opt/venv /opt/venv
COPY . .
USER appuser
EXPOSE 8000

# Python rather than curl, because python:3.12-slim ships no curl and installing one purely to
# probe a port would be the only apt package in the runtime image.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4).status == 200 else 1)"]

# No --reload. The reloader watches the filesystem and restarts the process on a write, which in
# production means an extra thread doing nothing and a restart path nobody wants triggered.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]

FROM base AS dev
RUN useradd --create-home --uid 1000 appuser
COPY --from=builder-dev /opt/venv /opt/venv
COPY . .
USER appuser
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--reload"]
