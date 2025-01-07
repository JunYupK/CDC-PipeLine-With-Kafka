import asyncio
import json
import os
from datetime import datetime

import backoff
import schedule
import time
from pathlib import Path
from prometheus_client import start_http_server, Counter, Histogram, Gauge, Summary

from config import Config
from insert_article import insert_multiple_articles
from crwaling_news import meta_crwaling as crawl_news
from crawling_from_url import get_article as crawl_content

# Prometheus 메트릭 정의
CRAWL_TIME = Histogram(
    'crawl_duration_seconds',
    'Time spent crawling',
    ['category']
)
CRAWL_SUCCESS = Counter(
    'crawl_success_total',
    'Number of successful crawls',
    ['category']
)
CRAWL_FAILURE = Counter(
    'crawl_failure_total',
    'Number of failed crawls',
    ['category']
)
ARTICLES_PROCESSED = Counter(
    'articles_processed_total',
    'Number of articles processed',
    ['category']
)
DB_OPERATION_TIME = Summary(
    'db_operation_duration_seconds',
    'Time spent on database operations',
    ['operation_type', 'category']
)
LAST_EXECUTION_TIME = Gauge(
    'last_execution_timestamp',
    'Last execution timestamp of the crawler',
    ['category']
)

URLS = [
    ["정치","https://news.naver.com/section/100"],
    ["경제","https://news.naver.com/section/101"],
    ["사회","https://news.naver.com/section/102"],
    ["생활문화","https://news.naver.com/section/103"],
    ["세계","https://news.naver.com/section/104"],
    ["IT과학","https://news.naver.com/section/105"]
]


async def crawling_job():
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    data_dir = Path(os.path.dirname(os.path.abspath(__file__))).parent / 'data'
    data_dir.mkdir(parents=True, exist_ok=True)

    for category, url in URLS:
        try:
            print(f"\n크롤링 시작: {category} - {timestamp}")

            with CRAWL_TIME.labels(category=category).time():
                # 1단계: 뉴스 목록 크롤링
                articles = await crawl_news(url)

                if articles:
                    # 타임스탬프가 포함된 파일명으로 직접 저장
                    news_file = data_dir / f'{category}_naver_it_news_{timestamp}.json'
                    with open(news_file, 'w', encoding='utf-8') as f:
                        json.dump(articles, f, ensure_ascii=False, indent=2)

                    # 2단계: 기사 내용 크롤링
                    result = await crawl_content(timestamp, category)
                    if result:  # 여기의 들여쓰기를 수정
                        articles_to_save = [{
                            "title": article["title"],
                            "content": article["content"],
                            "link": article["link"],
                            "stored_date": timestamp[:8],  # YYYYMMDD 형식
                            "category": category
                        } for article in result]

                        with DB_OPERATION_TIME.labels(
                                operation_type='insert',
                                category=category
                        ).time():
                            try:
                                save_to_db_with_retry(articles_to_save)
                                ARTICLES_PROCESSED.labels(
                                    category=category
                                ).inc(len(articles_to_save))
                                print(f"{category} 카테고리 {len(articles_to_save)}개 기사 DB 저장 완료")
                            except Exception as e:
                                print(f"DB 저장 실패: {str(e)}")
                                raise

            CRAWL_SUCCESS.labels(category=category).inc()
            LAST_EXECUTION_TIME.labels(category=category).set_to_current_time()

        except Exception as e:
            CRAWL_FAILURE.labels(category=category).inc()
            print(f"크롤링 중 오류 발생: {str(e)}")

        except Exception as e:
            CRAWL_FAILURE.labels(category=category).inc()
            print(f"크롤링 중 오류 발생: {str(e)}")


@backoff.on_exception(
    backoff.expo,
    Exception,
    max_tries=3,
    max_time=30
)
def save_to_db_with_retry(articles):
    insert_multiple_articles(articles)

def run_crawling():
    asyncio.run(crawling_job())

def main():
    # Prometheus 메트릭 서버 시작
    start_http_server(8000, addr='0.0.0.0')
    print("Prometheus 메트릭 서버 시작 (포트 8000)")
    print("크롤링 스케줄러 시작...")
    # 3시간마다 실행
    schedule.every(3).hours.do(run_crawling)

    # 시작하자마자 첫 실행
    run_crawling()

    # 스케줄 유지
    while True:
        schedule.run_pending()
        time.sleep(60)  # 1분마다 스케줄 체크


if __name__ == "__main__":
    Config.validate()
    main()