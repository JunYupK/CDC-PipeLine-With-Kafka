import asyncio
import json
import os
from datetime import datetime

import backoff
import schedule
import time
from pathlib import Path

from insert_article import insert_multiple_articles
from crwaling_news import meta_crwaling as crawl_news
from crawling_from_url import get_article as crawl_content

URLS = [
    ["정치","https://news.naver.com/section/100"]
    # ["경제","https://news.naver.com/section/101"],
    # ["사회","https://news.naver.com/section/102"],
    # ["생활/문화","https://news.naver.com/section/103"],
    # ["세계","https://news.naver.com/section/104"],
    # ["IT/과학","https://news.naver.com/section/105"]
]


async def crawling_job():
    # 시작 시간으로 파일명 생성
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    # ../data 디렉토리 경로 설정 및 생성
    data_dir = Path(os.path.dirname(os.path.abspath(__file__))).parent / 'data'
    data_dir.mkdir(parents=True, exist_ok=True)
    for category, url in URLS:
        try:
            print(f"\n크롤링 시작: {timestamp}")

            # 1단계: 뉴스 목록 크롤링
            articles = await crawl_news(url)
            if articles:
                # 타임스탬프가 포함된 파일명으로 직접 저장
                news_file = data_dir / f'{category}_naver_it_news_{timestamp}.json'
                with open(news_file, 'w', encoding='utf-8') as f:
                    json.dump(articles, f, ensure_ascii=False, indent=2)
                print(f"뉴스 목록 저장 완료: {news_file}")

                # 2단계: 기사 내용 크롤링
                result = await crawl_content(timestamp, category)
                #DB에 저장, 저장 실패시 재시도 3회
                if result:
                    # DB 저장을 위한 데이터 형식 변환
                    articles_to_save = [{
                        "title": article["title"],
                        "content": article["content"],
                        "link": article["link"],
                        "stored_date": timestamp[:8],  # YYYYMMDD 형식
                        "category": category
                    } for article in result]

                    # 재시도 로직을 포함한 DB 저장
                    @backoff.on_exception(
                        backoff.expo,
                        Exception,
                        max_tries=3,
                        max_time=30
                    )
                    def save_to_db():
                        insert_multiple_articles(articles_to_save)

                    try:
                        save_to_db()
                        print(f"{category} 카테고리 {len(articles_to_save)}개 기사 DB 저장 완료")
                    except Exception as e:
                        print(f"DB 저장 실패 (3회 재시도 후): {str(e)}")

        except Exception as e:
            print(f"크롤링 중 오류 발생: {str(e)}")





if __name__ == "__main__":
    asyncio.run(crawling_job())
