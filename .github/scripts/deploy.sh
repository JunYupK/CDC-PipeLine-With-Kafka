#!/bin/bash
set -e

# 환경 변수 파일 로드
source .env

# 배포 시작 로그
echo "🚀 Starting deployment process at $(date)"

# docker-compose 파일 지정
COMPOSE_FILE="docker-compose.prod.yml"

# Artifact Registry 인증 설정
if [ -f "$HOME/gcp-key.json" ]; then
    echo "🔑 Setting up Google Cloud authentication..."
    gcloud auth activate-service-account --key-file=$HOME/gcp-key.json
    gcloud auth configure-docker ${REGION}-docker.pkg.dev --quiet
else
    echo "⚠️ Warning: GCP service account key not found at $HOME/gcp-key.json"
    echo "⚠️ Proceeding without authentication, pulling might fail"
fi

# 컨테이너 상태 확인
echo "🔍 Checking current container status..."
docker ps -a

# 현재 실행 중인 서비스 확인
RUNNING_CONTAINERS=$(docker ps -q)
if [ -n "$RUNNING_CONTAINERS" ]; then
    echo "🔄 Stopping previous containers..."
    docker-compose -f $COMPOSE_FILE down --remove-orphans
else
    echo "ℹ️ No running containers found"
fi

# 새 이미지 pull
echo "📥 Pulling new images..."
docker-compose -f $COMPOSE_FILE pull crawler

# 이미지 정보 출력
echo "📋 Image details:"
docker images | grep crawler

# 백업 생성
TIMESTAMP=$(date +%Y%m%d%H%M%S)
if [ -d "data" ]; then
    echo "💾 Creating backup of data directory..."
    tar -czf "data_backup_${TIMESTAMP}.tar.gz" data
fi

# 컨테이너 시작
echo "🔄 Starting new containers..."
docker-compose -f $COMPOSE_FILE up -d

# 초기 대기 시간
echo "⏳ Waiting for initial startup (30s)..."
sleep 30

# 배포 로그 출력
echo "📝 Deployment logs:"
docker-compose -f $COMPOSE_FILE logs --tail=50 crawler

# 헬스체크 실행
echo "🔄 Running health checks..."
../.github/scripts/health_check.sh
HEALTH_CHECK_RESULT=$?

# 이미지 정리
echo "🧹 Cleaning up old images..."
docker image prune -f

# 배포 결과 출력
if [ $HEALTH_CHECK_RESULT -eq 0 ]; then
    echo "✅ Deployment completed successfully at $(date)!"

    # 서비스 상태 출력
    echo "📊 Current service status:"
    docker-compose -f $COMPOSE_FILE ps

    exit 0
else
    echo "❌ Deployment completed with issues at $(date). Health checks failed."
    echo "⚠️ Please check service logs for more details."

    # 최근 로그 출력
    echo "📝 Recent logs:"
    docker-compose -f $COMPOSE_FILE logs --tail=100 crawler

    exit 1
fi