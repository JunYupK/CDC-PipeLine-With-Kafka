import asyncio
import sys
import threading

if sys.platform.startswith('win'):
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

from fastapi import FastAPI, BackgroundTasks, HTTPException
from contextlib import asynccontextmanager
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from prometheus_client import start_http_server, make_wsgi_app
from typing import Optional
from wsgiref.simple_server import make_server

from services.crawler_service import CrawlerService
from config import Config

scheduler = AsyncIOScheduler()
crawler_service = CrawlerService()


@asynccontextmanager
async def lifespan(app: FastAPI):
    # startup 이벤트
    Config.validate()

    # Prometheus 메트릭 서버 시작 (크롤러 서비스의 레지스트리 사용)
    metrics_app = make_wsgi_app(registry=crawler_service.get_registry())
    httpd = make_server('', 8000, metrics_app)
    metrics_server = threading.Thread(target=httpd.serve_forever)
    metrics_server.daemon = True
    metrics_server.start()

    scheduler.add_job(crawler_service.crawling_job, 'interval', hours=3)
    scheduler.start()

    yield

    # shutdown 이벤트
    scheduler.shutdown()
    httpd.shutdown()


app = FastAPI(title="News Crawler API", lifespan=lifespan)


# ... (나머지 라우트 핸들러들은 그대로 유지)


@app.post("/api/v1/crawl")
async def trigger_crawl(background_tasks: BackgroundTasks, category: Optional[str] = None):
    """수동으로 크롤링 작업 트리거"""
    background_tasks.add_task(crawler_service.crawling_job, category)
    return {"message": "Crawling job started", "category": category or "all"}


@app.get("/api/v1/status")
async def get_status():
    """크롤링 상태 조회"""
    return {
        "last_execution": crawler_service.get_last_execution(),
        "articles_processed": crawler_service.get_articles_processed(),
        "success_rate": crawler_service.get_success_rate()
    }
@app.post("/api/v1/crawl/stop")
async def stop_crawl():
    """실행 중인 크롤링 작업 중지"""
    try:
        return crawler_service.stop_crawling()
    except RuntimeError as e:
        raise HTTPException(status_code=400, detail=str(e))

@app.get("/api/v1/metrics")
async def get_metrics():
    """Prometheus 메트릭 조회"""
    return crawler_service.get_metrics()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8080,
        reload=True,
        loop='asyncio'  # asyncio 이벤트 루프 사용
    )