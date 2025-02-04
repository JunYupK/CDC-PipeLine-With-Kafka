#!/bin/bash
set -e

# 환경 변수 파일 로드
source .env

# 컨테이너 상태 확인
echo "Checking current container status..."
docker ps

# 이전 컨테이너 정리
echo "Stopping previous containers..."
docker-compose down --remove-orphans

# 새 이미지 pull
echo "Pulling new images..."
docker-compose pull crawler

# 컨테이너 시작
echo "Starting new containers..."
docker-compose up -d

# 초기 대기 시간
echo "Waiting for initial startup..."
sleep 30

# 헬스체크 실행
echo "Running health checks..."
./.github/scripts/health_check.sh

# 이미지 정리
echo "Cleaning up old images..."
docker image prune -f

echo "Deployment completed successfully!"