import asyncio
import json
import os
from datetime import datetime
import backoff
from pathlib import Path
from crawl4ai import AsyncWebCrawler, CacheMode
from crawl4ai.extraction_strategy import JsonCssExtractionStrategy
from dotenv import load_dotenv

from config import Config
from insert_article import insert_multiple_articles

URLS = [
    ["정치", "https://news.naver.com/section/100"],
    ["경제", "https://news.naver.com/section/101"],
    ["사회", "https://news.naver.com/section/102"],
    ["생활/문화", "https://news.naver.com/section/103"],
    ["세계", "https://news.naver.com/section/104"],
    ["IT/과학", "https://news.naver.com/section/105"]
]


async def test_db_connection():
    """데이터베이스 연결 테스트"""
    try:
        # 임시 데이터로 DB 연결 테스트
        test_data = [{
            "title": "테스트",
            "content": "테스트 내용",
            "link": "http://test.com",
            "stored_date": datetime.now().strftime("%Y%m%d"),
            "category": "테스트"
        }]

        insert_multiple_articles(test_data)
        print("DB 연결 성공!")
        return True
    except Exception as e:
        print(f"DB 연결 실패: {str(e)}")
        return False

async def get_article_content(url, crawler):
    article_schema = {
        "name": "Article Content",
        "baseSelector": "body",
        "fields": [
            {
                "name": "content",
                "selector": "#dic_area",
                "type": "text"
            }
        ]
    }

    content_strategy = JsonCssExtractionStrategy(article_schema, verbose=True)

    try:
        result = await crawler.arun(
            url=url,
            extraction_strategy=content_strategy,
            cache_mode=CacheMode.BYPASS,
        )

        content = json.loads(result.extracted_content)
        if content and len(content) > 0:
            return content[0].get("content", "")
        return None

    except Exception as e:
        print(f"기사 내용 크롤링 실패 ({url}): {str(e)}")
        return None


async def test_single_article():
    test_url = "https://n.news.naver.com/mnews/article/028/0002725769"  # 테스트할 URL
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    # DB 연결 테스트
    db_connected = await test_db_connection()

    print("테스트 크롤링 시작...")
    async with AsyncWebCrawler(headless=False, verbose=True) as crawler:
        content = await get_article_content(test_url, crawler)

    if content:
        print("\n=== 크롤링 결과 ===")
        # 단일 기사에 대한 딕셔너리 생성
        article_to_save = {
            "title": "테스트 기사",  # 실제 기사 제목이 필요하다면 크롤링 로직 추가 필요
            "content": content,  # 크롤링한 내용
            "link": test_url,  # 테스트 URL
            "stored_date": timestamp[:8],
            "category": "정치"
        }

        articles_to_save = [article_to_save]  # 리스트로 감싸기

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
            print(f"카테고리 {len(articles_to_save)}개 기사 DB 저장 완료")
        except Exception as e:
            print(f"DB 저장 실패 (3회 재시도 후): {str(e)}")

        # 크롤링된 내용 출력
        print("\n=== 크롤링된 내용 ===")
        print(content[:200] + "..." if len(content) > 200 else content)
    else:
        print("크롤링 실패")


def print_env_vars():
    # .env 파일 로드
    load_dotenv()

    # DB 관련 환경 변수 출력
    db_vars = ['DB_NAME', 'DB_USER', 'DB_PASSWORD', 'DB_HOST', 'DB_PORT']

    print("=== 현재 설정된 DB 환경 변수 ===")
    for var in db_vars:
        value = os.getenv(var)
        print(f"{var}: {value}")

if __name__ == "__main__":
    import sys

    print_env_vars()
    Config.validate()
    if len(sys.argv) > 1 and sys.argv[1] == "test":
        # python script.py test 로 실행시 테스트 모드
        asyncio.run(test_single_article())