# contents_crawler.py
import asyncio
import json
import aiohttp
from typing import Dict, Any, Optional, List
from datetime import datetime
from pathlib import Path

# 위에서 만든 헬퍼 함수 임포트
from .crawl4ai_helper import _call_crawl4ai_api

# 단일 기사의 내용을 크롤링하는 함수
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
            "type": "json_css",  # 문자열 형식 사용 (API 호출용)
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


# CrawlerService에서 호출하는 인터페이스 함수 추가
async def get_article(timestamp: str, category: str) -> Optional[List[Dict[str, Any]]]:
    """
    메타데이터 JSON 파일을 읽어 기사 내용을 크롤링하고 결과를 저장합니다.

    Args:
        timestamp: 파일명에 사용할 타임스탬프
        category: 기사 카테고리

    Returns:
        Optional[List[Dict[str, Any]]]: 크롤링된 기사 목록, 실패 시 None
    """
    # 설정 상수
    TEMP_FILE = 'naver_it_news.json'
    BATCH_SIZE = 5  # 배치 크기
    MIN_DELAY = 1.5  # 최소 딜레이 초
    MAX_DELAY = 3.5  # 최대 딜레이 초
    API_BASE_URL = "http://crawl4ai-server:11235"
    API_TOKEN = "home"

    # Crawl4AI 추출 스키마 정의
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
                "multiple": True
            }
        ]
    }

    try:
        # 메타데이터 파일 로드
        try:
            with open(TEMP_FILE, 'r', encoding='utf-8') as f:
                articles = json.load(f)
        except FileNotFoundError:
            error_msg = f"{TEMP_FILE} 파일을 찾을 수 없습니다."
            print(error_msg)
            raise FileNotFoundError(error_msg)

        print(f"\n총 {len(articles)}개의 기사 내용 수집 시작...")

        # aiohttp 세션 생성 (세션 재사용으로 연결 효율성 향상)
        async with aiohttp.ClientSession() as session:
            # 배치 단위로 처리
            for i in range(0, len(articles), BATCH_SIZE):
                batch = articles[i:i + BATCH_SIZE]

                # 현재 태스크 확인 및 취소 여부 확인
                current_task = asyncio.current_task()
                if current_task is not None and current_task.cancelled():
                    print("크롤링이 취소되었습니다.")
                    return None

                for j, article in enumerate(batch, 1):
                    article_url = article.get('link')
                    if not article_url:
                        print(f"Warning: 링크 정보가 없는 기사 건너뜀 ({i+j}/{len(articles)})")
                        continue

                    # 개별 기사 내용 크롤링
                    try:
                        result = await fetch_article_content(
                            session,
                            API_BASE_URL,
                            API_TOKEN,
                            article_url,
                            CONTENT_SCHEMA
                        )

                        if result:
                            article['content'] = result['content'].strip()
                            article['stored_date'] = datetime.now().strftime("%Y%m%d")
                            article['category'] = category
                            article['img'] = result['images'][0] if result.get('images') else None
                            print(f"기사 {i + j}/{len(articles)} 내용 수집 완료")
                        else:
                            print(f"기사 {i + j}/{len(articles)} 내용 수집 실패")
                            article['content'] = ""
                            article['stored_date'] = datetime.now().strftime("%Y%m%d")
                            article['category'] = category
                            article['img'] = None
                    except Exception as e:
                        print(f"기사 {i + j}/{len(articles)} 내용 수집 중 오류 발생: {str(e)}")
                        article['content'] = ""
                        article['stored_date'] = datetime.now().strftime("%Y%m%d")
                        article['category'] = category
                        article['img'] = None

                    # 봇 감지를 피하기 위한 임의의 딜레이 사용
                    await asyncio.sleep(MIN_DELAY + (MAX_DELAY - MIN_DELAY) * 0.5)

                print(f"배치 {i // BATCH_SIZE + 1}/{(len(articles) + BATCH_SIZE - 1) // BATCH_SIZE} 완료")

        # 결과 저장
        data_dir = Path('data')
        news_file = data_dir / f'{category}_naver_news_with_contents_{timestamp}.json'

        data_dir.mkdir(parents=True, exist_ok=True)
        with open(news_file, 'w', encoding='utf-8') as f:
            json.dump(articles, f, ensure_ascii=False, indent=2)

        print(f"\n모든 기사 내용 수집 완료: {len(articles)}개")
        return articles
    except Exception as e:
        print(f"기사 내용 수집 중 예외 발생: {str(e)}")
        return None