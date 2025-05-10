# contents_crawler.py
import asyncio
import json
import os
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Any, Tuple
import aiohttp

# 위에서 만든 헬퍼 함수 임포트
from .crawl4ai_helper import _call_crawl4ai_api

# 원본 세부 구현 함수 유지
async def fetch_article_content(
        session: aiohttp.ClientSession,
        api_url_base: str,
        api_token: str,
        article_url: str,
        schema: Dict[str, Any],
        crawler_params: Optional[Dict[str, Any]] = None,
        extra_params: Optional[Dict[str, Any]] = None
) -> Optional[Dict[str, Any]]:
    """
    주어진 기사 URL과 스키마를 사용하여 본문과 이미지 URL 목록을 크롤링합니다.

    Args:
        session: aiohttp 클라이언트 세션.
        api_url_base: Crawl4AI API 기본 URL.
        api_token: API 인증 토큰.
        article_url: 크롤링할 기사 URL.
        schema: Crawl4AI의 'json_css' 타입 추출 스키마 (보통 content, images 필드 포함).
        crawler_params: API 요청의 'crawler_params' 필드 (옵션).
        extra_params: API 요청의 'extra' 필드 (옵션).

    Returns:
        추출된 콘텐츠 딕셔너리 (예: {'content': '...', 'images': [...]}), 실패 시 None.
    """
    default_crawler_params = {
        "headless": True,
        "page_timeout": 60000, # 1분 타임아웃
    }
    if crawler_params:
        default_crawler_params.update(crawler_params)

    request_data = {
        "urls": article_url,
        "extraction_config": {
            "type": "json_css",
            "params": {"schema": schema}
        },
        "crawler_params": default_crawler_params,
        "priority": 8 # 개별 기사는 우선순위 보통
    }
    if extra_params:
        request_data["extra"] = extra_params

    # 코어 API 호출 함수 사용
    result_data = await _call_crawl4ai_api(session, api_url_base, api_token, request_data)

    if result_data:
        extracted_content = result_data.get("extracted_content")
        if extracted_content:
            try:
                # 추출된 콘텐츠가 문자열이면 JSON 파싱
                if isinstance(extracted_content, str):
                    content_list = json.loads(extracted_content)
                else:
                    content_list = extracted_content

                # 스키마가 보통 리스트 속 딕셔너리 형태로 반환하므로 첫 번째 아이템 사용
                if content_list and isinstance(content_list, list) and len(content_list) > 0:
                    first_item = content_list[0]
                    # 이미지 URL이 문자열 하나로 반환될 경우 리스트로 감싸기 (스키마 설계에 따라 다름)
                    images = first_item.get("images", []) # 스키마 필드명 가정
                    if isinstance(images, str):
                        images = [images] if images else []

                    processed_content = {
                        "content": first_item.get("content", ""), # 스키마 필드명 가정
                        "images": images
                    }
                    print(f"Successfully extracted content from {article_url}")
                    return processed_content
                else:
                    print(f"Warning: Extracted content format is unexpected or empty for {article_url}. Content: {content_list}")
                    return {"content": "", "images": []} # 빈 결과 반환 또는 None 처리 선택
            except json.JSONDecodeError as e:
                print(f"Error: JSON parsing failed for {article_url}. Content: {extracted_content[:200]}... Error: {e}")
                return None
            except Exception as e:
                print(f"Error processing extracted content for {article_url}: {e}")
                return None
        else:
            print(f"Warning: No content extracted from {article_url}. Result: {result_data}")
            return {"content": "", "images": []} # 빈 결과 반환 또는 None 처리 선택
    else:
        print(f"Error: Failed to get result for {article_url}")
        return None

# 서비스에서 호출되는 인터페이스 함수 추가
async def crawl_content(timestamp: str, category: str) -> Optional[List[Dict[str, Any]]]:
    """
    메타데이터 JSON 파일을 읽어 기사 내용을 크롤링하고 결과를 저장합니다.

    Args:
        timestamp: 파일명에 사용할 타임스탬프
        category: 기사 카테고리

    Returns:
        Optional[List[Dict[str, Any]]]: 크롤링된 기사 목록, 실패 시 None
    """
    TEMP_FILE = 'naver_it_news.json'  # 메타데이터 임시 저장 파일
    MAX_CONCURRENT_CONTENT_FETCHES = 5  # 동시 크롤링 수
    API_URL_BASE = "http://crawl4ai-server:11235"  # Docker 내부 URL
    API_TOKEN = "home"  # API 토큰

    # 본문 추출 스키마
    CONTENT_SCHEMA = {
        "name": "Naver Article Content",
        "baseSelector": "body",  # 전체 body에서 찾기
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
                "multiple": True  # 여러 이미지를 가져오도록 설정
            }
        ]
    }

    try:
        # 메타데이터 파일 읽기
        with open(TEMP_FILE, 'r', encoding='utf-8') as f:
            articles = json.load(f)
    except FileNotFoundError:
        error_msg = f"{TEMP_FILE} 파일을 찾을 수 없습니다."
        print(error_msg)
        return None

    print(f"\n총 {len(articles)}개의 기사 내용 수집 시작...")
    final_articles = []

    # aiohttp 세션 생성 (모든 요청에서 공유)
    async with aiohttp.ClientSession() as session:
        # 배치 처리를 위한 작업 생성
        tasks = []
        for article in articles:
            article_url = article.get("link")
            if not article_url:
                print(f"Warning: Article has no link: {article}")
                continue

            task = asyncio.create_task(fetch_article_content(
                session=session,
                api_url_base=API_URL_BASE,
                api_token=API_TOKEN,
                article_url=article_url,
                schema=CONTENT_SCHEMA
            ))
            tasks.append((article, task))

            # 작업이 MAX_CONCURRENT_CONTENT_FETCHES에 도달하면 처리
            if len(tasks) >= MAX_CONCURRENT_CONTENT_FETCHES:
                await process_tasks(tasks, final_articles, category)
                tasks = []  # 작업 목록 초기화

        # 남은 작업 처리
        if tasks:
            await process_tasks(tasks, final_articles, category)

    # 결과 저장
    if final_articles:
        data_dir = Path('data')
        data_dir.mkdir(parents=True, exist_ok=True)
        result_file = data_dir / f'{category}_naver_news_with_contents_{timestamp}.json'

        with open(result_file, 'w', encoding='utf-8') as f:
            json.dump(final_articles, f, ensure_ascii=False, indent=2)

        print(f"\n총 {len(final_articles)}개 기사 내용 수집 및 저장 완료: {result_file}")
    else:
        print("\n수집된 기사가 없습니다.")

    return final_articles

async def process_tasks(tasks, final_articles, category):
    """작업 배치를 처리하고 결과를 final_articles에 추가"""
    results = await asyncio.gather(*[task for _, task in tasks], return_exceptions=True)

    for (article, _), result in zip(tasks, results):
        if isinstance(result, Exception):
            print(f"Error fetching content for {article.get('link')}: {result}")
            continue

        if result:
            # 기존 메타데이터와 콘텐츠 정보 병합
            processed_article = {
                **article,  # 기존 title, link 등
                "content": result.get("content", ""),
                "images": result.get("images", []),
                "stored_date": datetime.now().strftime("%Y%m%d"),
                "category": category
            }
            final_articles.append(processed_article)
            print(f"Processed content for: {article.get('title', 'Unknown title')[:30]}...")
        else:
            print(f"Failed to fetch content for: {article.get('title', 'Unknown title')[:30]}...")

    # 작업 간 약간의 대기 시간
    await asyncio.sleep(1)