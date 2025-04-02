# run_simple_crawl.py

import asyncio
import aiohttp
import time
import json
from typing import List, Dict, Any

# --- 테스트 대상 모듈 임포트 (프로젝트 루트 기준 경로) ---
# crawl4ai_helper.py 와 contents_crawler.py가 services/crawlers/ 안에 있다고 가정
from services.crawlers.crawl4ai_helper import _call_crawl4ai_api
from services.crawlers.contents_crawler import fetch_article_content

# --- 설정 (테스트 코드와 동일하게) ---
API_BASE_URL = "http://localhost:11235" # 실제 Crawl4AI 서버 주소
API_TOKEN = "home" # 실제 API 토큰
TEST_URLS = [
    "https://example.com/",
    "https://httpbin.org/html",
    # 필요하다면 다른 URL 추가
]
SIMPLE_CONTENT_SCHEMA = {
    "name": "Simple Page Title",
    "baseSelector": "body",
    "fields": [
        {"name": "content", "selector": "title", "type": "text"},
        {"name": "images", "selector": "img", "type": "attribute", "attribute": "src", "multiple": True}
    ]
}

async def run_crawl_tasks():
    """ 여러 URL에 대한 크롤링 작업을 비동기로 실행 """
    print("--- Starting Simple Crawl ---")
    # 세션 생성 (테스트 fixture 처럼 타임아웃 비활성화 불필요)
    async with aiohttp.ClientSession() as session:
        tasks = []
        print(f"Creating tasks for {len(TEST_URLS)} URLs...")
        for url in TEST_URLS:
            # 각 URL에 대해 fetch_article_content 태스크 생성
            # _call_crawl4ai_api 내부에 asyncio.wait_for가 있다고 가정
            task = asyncio.create_task(fetch_article_content(
                session=session,
                api_url_base=API_BASE_URL,
                api_token=API_TOKEN,
                article_url=url,
                schema=SIMPLE_CONTENT_SCHEMA
            ))
            tasks.append((url, task)) # URL과 태스크 튜플 저장
            print(f"  - Task created for: {url}")

        print("\nRunning tasks concurrently using asyncio.gather...")
        # asyncio.gather를 사용하여 모든 태스크 동시 실행 및 결과 대기
        # *[task for _, task in tasks] 는 태스크 객체만 추출하여 gather에 전달
        results = await asyncio.gather(*[task for _, task in tasks], return_exceptions=True)
        # return_exceptions=True : 개별 태스크에서 예외 발생 시 gather가 중단되지 않고 예외 객체를 결과로 포함

        print("\n--- Results ---")
        successful_fetches = 0
        for (url, _), result in zip(tasks, results):
            print(f"\n* URL: {url}")
            if isinstance(result, Exception):
                print(f"  Status: FAILED (Exception)")
                print(f"  Error Type: {type(result).__name__}")
                print(f"  Error Message: {result}")
                # 필요 시 더 자세한 traceback 출력
                # import traceback
                # traceback.print_exception(type(result), result, result.__traceback__)
            elif result is not None:
                print(f"  Status: SUCCESS")
                print(f"  Content: {result.get('content', 'N/A')[:100]}...") # 내용 일부만 출력
                print(f"  Images: {result.get('images', [])}")
                successful_fetches += 1
            else:
                print(f"  Status: FAILED (Returned None)")

        print("\n--- Summary ---")
        print(f"Successfully fetched: {successful_fetches} / {len(TEST_URLS)}")

if __name__ == "__main__":
    start_time = time.time()
    # asyncio.run()으로 메인 비동기 함수 실행
    asyncio.run(run_crawl_tasks())
    end_time = time.time()
    print(f"\nTotal execution time: {end_time - start_time:.2f} seconds.")