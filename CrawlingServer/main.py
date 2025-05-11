import asyncio
import sys
import threading
import os
os.environ['DISABLE_COLORAMA'] = 'TRUE'
if sys.platform.startswith('win'):
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

from fastapi import FastAPI, BackgroundTasks, HTTPException
from contextlib import asynccontextmanager
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from prometheus_client import make_wsgi_app
from typing import Optional
from wsgiref.simple_server import make_server

from crawler_service import CrawlerService
from services.db.postgres import get_db_connection

from config import Config

scheduler = AsyncIOScheduler()
crawler_service = CrawlerService()


@asynccontextmanager
async def lifespan(app: FastAPI):
    # startup 이벤트
    Config.validate()

    # Prometheus 메트릭 서버 시작 (크롤러 서비스의 레지스트리 사용)
    metrics_app = make_wsgi_app(registry=crawler_service.get_registry())
    httpd = make_server('', Config.METRICS_PORT, metrics_app)
    metrics_server = threading.Thread(target=httpd.serve_forever)
    metrics_server.daemon = True
    metrics_server.start()

    # 크롤링 스케줄 설정
    scheduler.add_job(crawler_service.crawling_job, 'interval', hours=3)

    # 10초마다 시스템 메트릭 업데이트
    scheduler.add_job(crawler_service.update_system_metrics, 'interval', seconds=10)

    scheduler.start()

    yield

    # shutdown 이벤트
    scheduler.shutdown()
    httpd.shutdown()


# FastAPI 앱 객체 생성 - 라우트 정의 전에 위치해야 함
app = FastAPI(title="News Crawler API", lifespan=lifespan)


# 이제 라우트 정의
@app.post("/api/v1/crawl")
async def trigger_crawl(background_tasks: BackgroundTasks, category: Optional[str] = None):
    """수동으로 크롤링 작업 트리거"""
    background_tasks.add_task(crawler_service.crawling_job, category)
    return {"message": "Crawling job started", "category": category or "all"}


@app.get("/api/v1/status")
async def get_status():
    """크롤링 상태 조회"""
    return {
        "crawling_status": crawler_service.get_crawling_status(),
        "last_execution": crawler_service.get_last_execution(),
        "articles_processed": crawler_service.get_articles_processed(),
        "success_rate": crawler_service.get_success_rate()
    }


@app.post("/api/v1/crawl/stop")
async def stop_crawl():
    """실행 중인 크롤링 작업 중지"""
    try:
        result = crawler_service.stop_crawling()
        # 실제로 태스크가 취소될 때까지 짧게 대기
        await asyncio.sleep(1)
        return result
    except RuntimeError as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.get("/api/v1/metrics")
async def get_metrics():
    """Prometheus 메트릭 조회"""
    return crawler_service.get_metrics()


@app.get("/api/v1/health/db")
async def check_database():
    """DB 연결 상태 확인"""
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cur:
                cur.execute('SELECT 1')
                result = cur.fetchone()

        return {
            "status": "healthy",
            "message": "Database connection successful"
        }
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Database connection failed: {str(e)}"
        )


@app.get("/api/v1/articles/stats")
async def get_article_stats():
    """DB에 저장된 기사 통계 조회"""
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cur:
                # 전체 통계
                cur.execute("""
                    SELECT 
                        COUNT(*) as total_articles,
                        COUNT(DISTINCT category) as category_count,
                        COUNT(DISTINCT stored_date) as date_count
                    FROM articles
                """)
                total_stats = cur.fetchone()

                # 카테고리별 통계
                cur.execute("""
                    SELECT 
                        category,
                        COUNT(*) as article_count,
                        MIN(stored_date) as earliest_date,
                        MAX(stored_date) as latest_date
                    FROM articles
                    GROUP BY category
                """)
                category_stats = cur.fetchall()

                # 최근 날짜별 통계
                cur.execute("""
                    SELECT 
                        stored_date,
                        COUNT(*) as article_count
                    FROM articles
                    GROUP BY stored_date
                    ORDER BY stored_date DESC
                    LIMIT 7
                """)
                daily_stats = cur.fetchall()

        return {
            "overall": {
                "total_articles": total_stats[0],
                "total_categories": total_stats[1],
                "total_dates": total_stats[2]
            },
            "by_category": [
                {
                    "category": cat[0],
                    "article_count": cat[1],
                    "earliest_date": cat[2],
                    "latest_date": cat[3]
                }
                for cat in category_stats
            ],
            "recent_daily": [
                {
                    "date": date[0],
                    "article_count": date[1]
                }
                for date in daily_stats
            ]
        }
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Failed to get article stats: {str(e)}"
        )


@app.get("/health")
async def health_check():
    return {"status": "healthy"}


# 메인 실행 코드는 파일 끝에 위치
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=Config.FASTAPI_PORT,
        reload=True,
        loop='asyncio'  # asyncio 이벤트 루프 사용
    )