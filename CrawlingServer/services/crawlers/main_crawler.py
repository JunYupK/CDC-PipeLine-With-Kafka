# main_crawler.py
import asyncio
import aiohttp
import json
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Any

# 리팩토링된 크롤러 함수 임포트
from urls_crawler import fetch_urls_from_list_page
from contents_crawler import fetch_article_content

# --- 설정 ---
# API 엔드포인트 및 토큰 (환경 변수나 설정 파일에서 읽어오는 것이 더 좋음)
CRAWL4AI_API_BASE_URL = "http://crawl4ai-server:11235" # Docker 내부 컨테이너 이름 사용
# CRAWL4AI_API_BASE_URL = "http://localhost:11235" # 로컬 테스트 시
CRAWL4AI_API_TOKEN = "home" # 실제 사용하는 토큰으로 변경

# 크롤링 대상 및 카테고리
TARGET_URL_LIST_PAGE = "https://news.naver.com/main/list.naver?mode=LSD&mid=sec&sid1=105" # 예시: 네이버 IT 뉴스
TARGET_CATEGORY = "IT"

# 결과 저장 경로
OUTPUT_DIR = Path("data")
TIMESTAMP = datetime.now().strftime("%Y%m%d_%H%M%S")
OUTPUT_FILE = OUTPUT_DIR / f'{TARGET_CATEGORY}_articles_{TIMESTAMP}.json'

# 동시 작업 수 제어 (선택 사항, API 서버 부하 고려)
MAX_CONCURRENT_CONTENT_FETCHES = 10 # 동시에 가져올 기사 본문 수

# --- 추출 스키마 정의 ---
# 이 스키마들은 별도 파일(json, yaml)로 분리하거나 설정 관리 라이브러리를 사용하는 것이 더 좋음

URL_LIST_SCHEMA = {
    "name": "Naver News URL List",
    # 네이버 뉴스 목록 구조에 맞는 선택자 (실제 페이지 구조 확인 필요)
    "baseSelector": "#main_content > div.list_body.newsflash_body > ul.type06_headline > li, #main_content > div.list_body.newsflash_body > ul.type06 > li",
    "fields": [
        {
            "name": "title",
            # 제목 선택자 (실제 페이지 구조 확인 필요)
            "selector": "dl > dt:not(.photo) > a",
            "type": "text"
        },
        {
            "name": "link",
            # 링크 선택자 (실제 페이지 구조 확인 필요)
            "selector": "dl > dt:not(.photo) > a",
            "type": "attribute",
            "attribute": "href"
        }
    ]
}

CONTENT_SCHEMA = {
    "name": "Naver Article Content",
    "baseSelector": "body", # 전체 body에서 찾는 것이 일반적
    "fields": [
        {
            "name": "content",
            # 본문 내용 선택자 (여러 가능성 고려)
            "selector": "#dic_area, #articeBody, #newsEndContents, .article_body",
            "type": "text"
        },
        {
            "name": "images",
            # 이미지 URL 선택자 (여러 가능성 고려)
            "selector": "#dic_area img, #articeBody img, #newsEndContents img, .article_body img",
            "type": "attribute",
            "attribute": "src",
            "multiple": True # 여러 이미지를 가져오도록 설정 (Crawl4AI 기능 확인 필요, 안되면 첫번째 이미지만 가져옴)
        }
    ]
}

# --- 메인 실행 로직 ---

async def run_crawl():
    """전체 크롤링 파이프라인 실행"""
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    final_articles: List[Dict[str, Any]] = []

    # aiohttp 세션 생성
    async with aiohttp.ClientSession() as session:
        # 1. URL 목록 가져오기
        print(f"Fetching URL list from: {TARGET_URL_LIST_PAGE}")
        article_metadatas = await fetch_urls_from_list_page(
            session,
            CRAWL4AI_API_BASE_URL,
            CRAWL4AI_API_TOKEN,
            TARGET_URL_LIST_PAGE,
            URL_LIST_SCHEMA
        )

        if not article_metadatas:
            print("Failed to fetch article URLs. Exiting.")
            return

        print(f"Fetched {len(article_metadatas)} article links.")

        # 2. 각 URL에 대해 본문 내용 가져오기 (동시 처리)
        tasks = []
        for metadata in article_metadatas:
            article_url = metadata.get("link")
            if not article_url:
                print(f"Warning: Skipping item with missing link: {metadata}")
                continue

            # 작업 생성 (아직 실행은 아님)
            task = asyncio.create_task(fetch_article_content(
                session,
                CRAWL4AI_API_BASE_URL,
                CRAWL4AI_API_TOKEN,
                article_url,
                CONTENT_SCHEMA
            ))
            tasks.append((metadata, task)) # 원본 메타데이터와 태스크 튜플 저장

            # MAX_CONCURRENT_CONTENT_FETCHES 제한 (선택 사항)
            if len(tasks) >= MAX_CONCURRENT_CONTENT_FETCHES:
                 # 일정 개수만큼 작업이 쌓이면 실행하고 결과 처리
                await process_completed_tasks(tasks, final_articles)
                tasks = [] # 처리된 작업 목록 비우기

        # 남은 작업 처리
        if tasks:
            await process_completed_tasks(tasks, final_articles)


    # 3. 최종 결과 저장
    if final_articles:
        print(f"\nSaving {len(final_articles)} processed articles to {OUTPUT_FILE}")
        with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
            json.dump(final_articles, f, ensure_ascii=False, indent=2)
        print("Save complete.")
    else:
        print("\nNo articles were successfully processed.")


async def process_completed_tasks(tasks, final_articles):
     """ 생성된 태스크를 실행하고 결과를 final_articles 리스트에 추가 """
     # gather를 사용하여 생성된 태스크들을 동시에 실행하고 결과 기다림
     results = await asyncio.gather(*[task for _, task in tasks])

     # 결과 처리
     for (metadata, _), content_result in zip(tasks, results):
         if content_result:
             # 성공적으로 콘텐츠를 가져왔으면 메타데이터와 합치기
             merged_article = {
                 **metadata, # title, link 등 기존 정보
                 "content": content_result.get("content", ""),
                 "images": content_result.get("images", []),
                 "category": TARGET_CATEGORY,
                 "crawled_at": TIMESTAMP
             }
             final_articles.append(merged_article)
             print(f"Processed content for: {metadata.get('link')}")
         else:
             # 콘텐츠 가져오기 실패
             print(f"Failed to process content for: {metadata.get('link')}")
             # 실패한 경우도 저장하고 싶다면 별도 처리
             # failed_article = {**metadata, "status": "failed", ...}
             # final_articles.append(failed_article)


if __name__ == "__main__":
    print("Starting the crawl process...")
    start_time = time.time()
    asyncio.run(run_crawl())
    end_time = time.time()
    print(f"Crawling finished in {end_time - start_time:.2f} seconds.")