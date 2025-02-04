#!/bin/bash

# 환경 변수 로드
source .env

# 헬스체크 설정
MAX_RETRIES=30
RETRY_INTERVAL=10
HEALTH_ENDPOINT="http://localhost:${FASTAPI_PORT}/health"

echo "Starting health checks for crawler service..."

for i in $(seq 1 $MAX_RETRIES); do
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" $HEALTH_ENDPOINT || true)

    if [ "$RESPONSE" == "200" ]; then
        echo "Health check passed on attempt $i"

        # Redis 연결 확인
        REDIS_HEALTH=$(docker-compose exec -T redis redis-cli -a "${REDIS_PASSWORD}" ping)
        if [ "$REDIS_HEALTH" == "PONG" ]; then
            echo "Redis connection verified"
            exit 0
        else
            echo "Redis connection failed"
        fi
    fi

    echo "Attempt $i of $MAX_RETRIES: Service not healthy yet (Status: $RESPONSE)"
    sleep $RETRY_INTERVAL
done

echo "Service failed to become healthy after $MAX_RETRIES attempts"
exit 1