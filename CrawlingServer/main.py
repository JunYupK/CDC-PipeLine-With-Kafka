from fastapi import FastAPI, BackgroundTasks
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from prometheus_client import start_http_server
from typing import Optional
from datetime import datetime
from pathlib import Path

from services.crawler_service import CrawlerService
from config import Config

app = FastAPI(title="News Crawler API")
scheduler = AsyncIOScheduler()
crawler_service = CrawlerService()

@app.on_event("startup")
async def startup_event():
    Config.validate()
    # Prometheus 메트릭 서버 시작
    start_http_server(8000, addr='0.0.0.0')
    # 스케줄러 시작
    scheduler.add_job(crawler_service.crawling_job, 'interval', hours=3)
    scheduler.start()


@app.on_event("shutdown")
async def shutdown_event():
    scheduler.shutdown()


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

@app.get("/api/v1/metrics")
async def get_metrics():
    """Prometheus 메트릭 조회"""
    return crawler_service.get_metrics()