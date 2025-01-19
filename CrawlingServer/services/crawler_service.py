from prometheus_client import Counter, Histogram, Gauge, Summary, CollectorRegistry
from datetime import datetime
from pathlib import Path
import asyncio
import json
import os
from typing import Optional, Dict, Any, List

from services.db import save_to_db_with_retry
from services.crawlers import crawl_news, crawl_content


class CrawlerService:
    _instance = None
    _registry = CollectorRegistry()

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(CrawlerService, cls).__new__(cls)
        return cls._instance

    def __init__(self):
        if not hasattr(self, 'initialized'):
            # 크롤링할 URL 및 카테고리 정의
            self.URLS = [
                ["정치", "https://news.naver.com/section/100"],
                ["경제", "https://news.naver.com/section/101"],
                ["사회", "https://news.naver.com/section/102"],
                ["생활문화", "https://news.naver.com/section/103"],
                ["세계", "https://news.naver.com/section/104"],
                ["IT과학", "https://news.naver.com/section/105"]
            ]

            # 크롤링 상태 관리 변수
            self.is_crawling: bool = False
            self.current_category: Optional[str] = None
            self.crawl_start_time: Optional[datetime] = None
            self.error_count: Dict[str, int] = {category: 0 for category, _ in self.URLS}

            # Prometheus 메트릭 초기화
            self._init_metrics()

            self.initialized = True

    def _init_metrics(self) -> None:
        """Prometheus 메트릭 초기화"""
        self.CRAWL_TIME = Histogram(
            'crawler_crawl_duration_seconds',
            'Time spent crawling',
            ['category'],
            registry=self._registry
        )

        self.CRAWL_SUCCESS = Counter(
            'crawler_crawl_success_total',
            'Number of successful crawls',
            ['category'],
            registry=self._registry
        )

        self.CRAWL_FAILURE = Counter(
            'crawler_crawl_failure_total',
            'Number of failed crawls',
            ['category'],
            registry=self._registry
        )

        self.ARTICLES_PROCESSED = Counter(
            'crawler_articles_processed_total',
            'Number of articles processed',
            ['category'],
            registry=self._registry
        )

        self.DB_OPERATION_TIME = Summary(
            'crawler_db_operation_duration_seconds',
            'Time spent on database operations',
            ['operation_type', 'category'],
            registry=self._registry
        )

        self.LAST_EXECUTION_TIME = Gauge(
            'crawler_last_execution_timestamp',
            'Last execution timestamp of the crawler',
            ['category'],
            registry=self._registry
        )

        self.CRAWL_STATUS = Gauge(
            'crawler_is_running',
            'Whether the crawler is currently running',
            ['category'],
            registry=self._registry
        )

    async def crawling_job(self, target_category: Optional[str] = None) -> None:
        """
        주어진 카테고리 또는 모든 카테고리의 뉴스를 크롤링

        Args:
            target_category: 크롤링할 특정 카테고리. None인 경우 모든 카테고리 크롤링

        Raises:
            RuntimeError: 이미 크롤링이 진행 중인 경우
        """
        if self.is_crawling:
            raise RuntimeError("Crawling is already in progress")

        self.is_crawling = True
        self.current_category = target_category
        self.crawl_start_time = datetime.now()
        timestamp = self.crawl_start_time.strftime("%Y%m%d_%H%M%S")

        try:
            data_dir = Path(os.path.dirname(os.path.abspath(__file__))).parent / 'data'
            data_dir.mkdir(parents=True, exist_ok=True)

            urls = [url for url in self.URLS if target_category is None or url[0] == target_category]

            for category, url in urls:
                try:
                    print(f"\n크롤링 시작: {category} - {timestamp}")
                    self.CRAWL_STATUS.labels(category=category).set(1)

                    with self.CRAWL_TIME.labels(category=category).time():
                        articles = await crawl_news(url)

                        if articles:
                            # 메타데이터 저장
                            news_file = data_dir / f'{category}_naver_it_news_{timestamp}.json'
                            with open(news_file, 'w', encoding='utf-8') as f:
                                json.dump(articles, f, ensure_ascii=False, indent=2)

                            # 상세 내용 크롤링
                            result = await crawl_content(timestamp, category)
                            if result:
                                articles_to_save = [{
                                    "title": article["title"],
                                    "content": article["content"],
                                    "link": article["link"],
                                    "stored_date": timestamp[:8],
                                    "category": category
                                } for article in result]

                                # DB 저장
                                with self.DB_OPERATION_TIME.labels(
                                        operation_type='insert',
                                        category=category
                                ).time():
                                    try:
                                        save_to_db_with_retry(articles_to_save)
                                        self.ARTICLES_PROCESSED.labels(
                                            category=category
                                        ).inc(len(articles_to_save))
                                        print(f"{category} 카테고리 {len(articles_to_save)}개 기사 DB 저장 완료")
                                    except Exception as e:
                                        print(f"DB 저장 실패: {str(e)}")
                                        raise

                    self.CRAWL_SUCCESS.labels(category=category).inc()
                    self.LAST_EXECUTION_TIME.labels(category=category).set_to_current_time()
                    self.error_count[category] = 0

                except Exception as e:
                    self.CRAWL_FAILURE.labels(category=category).inc()
                    self.error_count[category] += 1
                    print(f"크롤링 중 오류 발생 ({category}): {str(e)}")

                finally:
                    self.CRAWL_STATUS.labels(category=category).set(0)

        finally:
            self.is_crawling = False
            self.current_category = None
            self.crawl_start_time = None

    def get_crawling_status(self) -> Dict[str, Any]:
        """현재 크롤링 상태 조회"""
        return {
            "is_crawling": self.is_crawling,
            "current_category": self.current_category,
            "crawl_start_time": self.crawl_start_time.isoformat() if self.crawl_start_time else None,
            "error_counts": self.error_count
        }

    def get_last_execution(self) -> Dict[str, float]:
        """각 카테고리별 마지막 실행 시간 조회"""
        return {
            category: float(self.LAST_EXECUTION_TIME.labels(category=category)._value.get() or 0)
            for category, _ in self.URLS
        }

    def get_articles_processed(self) -> Dict[str, int]:
        """각 카테고리별 처리된 기사 수 조회"""
        return {
            category: int(self.ARTICLES_PROCESSED.labels(category=category)._value.get() or 0)
            for category, _ in self.URLS
        }

    def get_success_rate(self) -> Dict[str, float]:
        """각 카테고리별 크롤링 성공률 계산"""
        rates = {}
        for category, _ in self.URLS:
            try:
                success = float(self.CRAWL_SUCCESS.labels(category=category)._value.get() or 0)
                processed = float(self.ARTICLES_PROCESSED.labels(category=category)._value.get() or 1)
                rates[category] = success / processed if processed > 0 else 0.0
            except (TypeError, ZeroDivisionError):
                rates[category] = 0.0
        return rates

    def stop_crawling(self) -> Dict[str, str]:
        """현재 실행 중인 크롤링 작업 중지"""
        if not self.is_crawling:
            raise RuntimeError("No crawling job is currently running")

        self.is_crawling = False
        return {"message": "Crawling job stop requested"}

    def get_metrics(self) -> Dict[str, Any]:
        """모든 Prometheus 메트릭 수집"""
        return {
            "crawl_time": self.CRAWL_TIME._collect(),
            "success": self.CRAWL_SUCCESS._collect(),
            "failure": self.CRAWL_FAILURE._collect(),
            "processed": self.ARTICLES_PROCESSED._collect(),
            "db_operation_time": self.DB_OPERATION_TIME._collect(),
            "status": self.CRAWL_STATUS._collect()
        }

    @classmethod
    def get_registry(cls) -> CollectorRegistry:
        """Prometheus 레지스트리 반환"""
        return cls._registry