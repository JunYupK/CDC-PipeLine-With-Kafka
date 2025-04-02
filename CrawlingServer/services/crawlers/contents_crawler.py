# contents_crawler.py
import asyncio
import json
from typing import Dict, Any, Optional, List
import aiohttp

# 위에서 만든 헬퍼 함수 임포트
from .crawl4ai_helper import _call_crawl4ai_api

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