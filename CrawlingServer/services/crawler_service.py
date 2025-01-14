# services/crawler_service.py
from prometheus_client import Counter, Histogram, Gauge, Summary
from datetime import datetime
from pathlib import Path
import asyncio
import json
import os

from services.db import save_to_db_with_retry  # 절대 경로로 변경
from services.crawlers import crawl_news, crawl_content


class CrawlerService:
    def __init__(self):
        self.URLS = [
            ["정치", "https://news.naver.com/section/100"],
            ["경제", "https://news.naver.com/section/101"],
            ["사회", "https://news.naver.com/section/102"],
            ["생활문화", "https://news.naver.com/section/103"],
            ["세계", "https://news.naver.com/section/104"],
            ["IT과학", "https://news.naver.com/section/105"]
        ]

        # Prometheus 메트릭 초기화
        self.CRAWL_TIME = Histogram('crawl_duration_seconds', 'Time spent crawling', ['category'])
        self.CRAWL_SUCCESS = Counter('crawl_success_total', 'Number of successful crawls', ['category'])
        self.CRAWL_FAILURE = Counter('crawl_failure_total', 'Number of failed crawls', ['category'])
        self.ARTICLES_PROCESSED = Counter('articles_processed_total', 'Number of articles processed', ['category'])
        self.DB_OPERATION_TIME = Summary('db_operation_duration_seconds', 'Time spent on database operations',
                                         ['operation_type', 'category'])
        self.LAST_EXECUTION_TIME = Gauge('last_execution_timestamp', 'Last execution timestamp of the crawler',
                                         ['category'])

    async def crawling_job(self, target_category: str = None):
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        data_dir = Path(os.path.dirname(os.path.abspath(__file__))).parent / 'data'
        data_dir.mkdir(parents=True, exist_ok=True)

        urls = [url for url in self.URLS if target_category is None or url[0] == target_category]

        for category, url in urls:
            try:
                print(f"\n크롤링 시작: {category} - {timestamp}")

                with self.CRAWL_TIME.labels(category=category).time():
                    # 1단계: 뉴스 목록 크롤링
                    articles = await crawl_news(url)

                    if articles:
                        # 타임스탬프가 포함된 파일명으로 직접 저장
                        news_file = data_dir / f'{category}_naver_it_news_{timestamp}.json'
                        with open(news_file, 'w', encoding='utf-8') as f:
                            json.dump(articles, f, ensure_ascii=False, indent=2)

                        # 2단계: 기사 내용 크롤링
                        result = await crawl_content(timestamp, category)
                        if result:
                            articles_to_save = [{
                                "title": article["title"],
                                "content": article["content"],
                                "link": article["link"],
                                "stored_date": timestamp[:8],  # YYYYMMDD 형식
                                "category": category
                            } for article in result]

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

            except Exception as e:
                self.CRAWL_FAILURE.labels(category=category).inc()
                print(f"크롤링 중 오류 발생: {str(e)}")

    def get_last_execution(self):
        return {category: self.LAST_EXECUTION_TIME.labels(category=category)._value
                for category, _ in self.URLS}

    def get_articles_processed(self):
        return {category: self.ARTICLES_PROCESSED.labels(category=category)._value
                for category, _ in self.URLS}

    def get_success_rate(self):
        return {category: self.CRAWL_SUCCESS.labels(category=category)._value /
                          (self.ARTICLES_PROCESSED.labels(category=category)._value or 1)
                for category, _ in self.URLS}

    def get_metrics(self):
        return {
            "crawl_time": self.CRAWL_TIME._collect(),
            "success": self.CRAWL_SUCCESS._collect(),
            "failure": self.CRAWL_FAILURE._collect(),
            "processed": self.ARTICLES_PROCESSED._collect(),
            "db_operation_time": self.DB_OPERATION_TIME._collect()
        }