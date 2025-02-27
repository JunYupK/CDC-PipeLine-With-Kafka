#!/bin/bash
set -e

# 환경 변수 로드
source .env

# docker-compose 파일 지정
COMPOSE_FILE="docker-compose.prod.yml"

# 헬스체크 설정
MAX_RETRIES=30
RETRY_INTERVAL=10
HEALTH_ENDPOINT="http://localhost:${FASTAPI_PORT}/health"
METRICS_ENDPOINT="http://localhost:${METRICS_PORT}/metrics"

echo "Starting health checks for crawler service..."

# FastAPI 서비스 헬스체크
check_fastapi() {
    for i in $(seq 1 $MAX_RETRIES); do
        RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" $HEALTH_ENDPOINT || true)

        if [ "$RESPONSE" == "200" ]; then
            echo "✅ FastAPI health check passed on attempt $i"
            return 0
        fi

        echo "⏳ Attempt $i of $MAX_RETRIES: FastAPI service not healthy yet (Status: $RESPONSE)"
        sleep $RETRY_INTERVAL
    done

    echo "❌ FastAPI service failed to become healthy after $MAX_RETRIES attempts"
    return 1
}

# Metrics 엔드포인트 헬스체크
check_metrics() {
    for i in $(seq 1 $MAX_RETRIES); do
        RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" $METRICS_ENDPOINT || true)

        if [ "$RESPONSE" == "200" ]; then
            echo "✅ Metrics endpoint check passed on attempt $i"
            return 0
        fi

        echo "⏳ Attempt $i of $MAX_RETRIES: Metrics endpoint not available yet (Status: $RESPONSE)"
        sleep $RETRY_INTERVAL
    done

    echo "❌ Metrics endpoint failed to become available after $MAX_RETRIES attempts"
    return 1
}

# PostgreSQL 연결 확인
check_postgres() {
    for i in $(seq 1 $MAX_RETRIES); do
        if docker-compose -f $COMPOSE_FILE exec -T postgres pg_isready -U "${DB_USER}" -d "${DB_NAME}" > /dev/null 2>&1; then
            echo "✅ PostgreSQL connection verified"
            return 0
        fi

        echo "⏳ Attempt $i of $MAX_RETRIES: PostgreSQL not ready yet"
        sleep $RETRY_INTERVAL
    done

    echo "❌ PostgreSQL connection failed after $MAX_RETRIES attempts"
    return 1
}

# 모든 헬스체크 실행
check_all() {
    local failed=0

    echo "📊 Running comprehensive health checks..."
    
    check_fastapi || failed=1
    check_metrics || failed=1
    check_postgres || failed=1

    if [ $failed -eq 0 ]; then
        echo "✅ All health checks passed! Service is fully operational."
        return 0
    else
        echo "❌ Some health checks failed. Service may not be fully operational."
        return 1
    fi
}

# 체크 실행
check_all
exit $?