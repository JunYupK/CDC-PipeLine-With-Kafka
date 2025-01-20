FROM python:3.9-slim

# 최소한의 시스템 패키지만 설치
RUN apt-get update && apt-get install -y \
    wget \
    gnupg \
    && rm -rf /var/lib/apt/lists/*

# 앱 종속성 설치
COPY requirements.txt .
RUN pip install -r requirements.txt && \
    python -m playwright install --with-deps chromium

COPY . .
CMD ["python", "main.py"]