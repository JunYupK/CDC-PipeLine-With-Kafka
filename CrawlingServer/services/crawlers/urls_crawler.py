# urls_crawler.py
import asyncio
import json
from typing import List, Dict, Any, Optional
import aiohttp
from urllib.parse import urlparse, urljoin
from .crawl4ai_helper import _call_crawl4ai_api

async def fetch_urls_from_list_page(
    session: aiohttp.ClientSession,
    api_url_base: str,
    api_token: str,
    target_url: str,
    schema: Dict[str, Any],
    crawler_params: Optional[Dict[str, Any]] = None,
    extra_params: Optional[Dict[str, Any]] = None
) -> Optional[List[Dict[str, Any]]]:
    """
    주어진 URL과 스키마를 사용하여 URL 목록을 크롤링합니다. (JS 실행 없음)

    Args:
        session: aiohttp 클라이언트 세션.
        api_url_base: Crawl4AI API 기본 URL.
        api_token: API 인증 토큰.
        target_url: 크롤링할 목록 페이지 URL.
        schema: Crawl4AI의 'json_css' 타입 추출 스키마.
        crawler_params: API 요청의 'crawler_params' 필드 (옵션).
        extra_params: API 요청의 'extra' 필드 (옵션).

    Returns:
        추출된 아이템 목록 (예: [{'title': '...', 'link': '...'}, ...]), 실패 시 None.
    """
    default_crawler_params = {
        "headless": True,
        "page_timeout": 60000, # 1분 타임아웃
    }
    if crawler_params:
        default_crawler_params.update(crawler_params)

    request_data = {
        "urls": target_url,
        "extraction_config": {
            "type": "json_css",
            "params": {"schema": schema}
        },
        "crawler_params": default_crawler_params,
        "priority": 10 # 목록 페이지는 우선순위 높게
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
                    items = json.loads(extracted_content)
                else:
                    items = extracted_content

                # 상대 URL을 절대 URL로 변환 (link 필드가 있다고 가정)
                processed_items = []
                for item in items:
                    if 'link' in item and item['link'] and not urlparse(item['link']).scheme:
                        item['link'] = urljoin(target_url, item['link'])
                    processed_items.append(item)

                print(f"Successfully extracted {len(processed_items)} items from {target_url}")
                return processed_items
            except json.JSONDecodeError as e:
                print(f"Error: JSON parsing failed for {target_url}. Content: {extracted_content[:200]}... Error: {e}")
                return None
            except Exception as e:
                 print(f"Error processing extracted content for {target_url}: {e}")
                 return None
        else:
            print(f"Warning: No content extracted from {target_url}. Result: {result_data}")
            return [] # 빈 리스트 반환 또는 None 처리 선택
    else:
        print(f"Error: Failed to get result for {target_url}")
        return None