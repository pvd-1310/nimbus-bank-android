FROM python:3.11-slim AS dependencies
RUN apt-get update && apt-get install -y --no-install-recommends build-essential && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN python -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir "wheel==0.45.1" "Cython==0.29.37" "numpy==1.26.4" \
    && /opt/venv/bin/pip install --no-cache-dir --no-build-isolation -r requirements.txt

FROM python:3.11-slim
ENV PATH="/opt/venv/bin:$PATH" PYTHONDONTWRITEBYTECODE=1 OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 \
    NO_ALBUMENTATIONS_UPDATE=1 MPLBACKEND=Agg MPLCONFIGDIR=/tmp/matplotlib
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*
COPY --from=dependencies /opt/venv /opt/venv
WORKDIR /app
COPY main.py .
USER 65532:65532
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--limit-concurrency", "16", "--no-access-log"]
