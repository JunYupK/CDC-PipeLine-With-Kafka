from datetime import datetime
import asyncio
import json
import os
import random
import requests
from pathlib import Path
from typing import Dict, List, Optional, Any, Union


# 설정 상수
TEMP_FILE = 'naver_it_news.json'
BATCH_SIZE = 5  # 배치 크기 줄임
MIN_DELAY = 1.5  # 최소 딜레이 초
MAX_DELAY = 3.5  # 최대 딜레이 초


async def get_article_content(url: str) -> Optional[Dict[str, Any]]:
    """
    Docker 환경의 Crawl4AI API를 사용하여 특정 URL에서 기사 내용과 이미지를 크롤링합니다.
    
    Args:
        url: 크롤링할 기사 URL
        
    Returns:
        Optional[Dict[str, Any]]: 크롤링된 기사 내용과 이미지 딕셔너리, 실패 시 None
    """
    # CSS 추출 스키마 정의
    schema = {
        "name": "Article Content",
        "baseSelector": "body",
        "fields": [
            {
                "name": "content",
                "selector": "#dic_area, #articeBody, #newsEndContents",
                "type": "text"
            },
            {
                "name": "article_images",
                "selector": "#dic_area img, #articeBody img, #newsEndContents img",
                "type": "attribute",
                "attribute": "src"
            }
        ]
    }
    
    # Crawl4AI Docker API 요청 생성
    request_data = {
        "urls": url,
        "extraction_config": {
            "type": "json_css",
            "params": {
                "schema": schema
            }
        },
        "crawler_params": {
            "headless": True,
            "page_timeout": 60000,  # 1분 타임아웃
        },
        "extra": {
            "delay_before_return_html": 2.0,
        },
        "priority": 8
    }
    
    # API 토큰 설정
    api_token = "mysecrettoken"
    headers = {"Authorization": f"Bearer {api_token}"}
    
    try:
        # API 호출 - Docker 환경에서는 'crawl4ai-server'라는 컨테이너명으로 접근해야 함
        crawl4ai_url = "http://crawl4ai-server:11235/crawl" # Docker 컨테이너 네트워크 내부에서 접근
        # 로컬 테스트용 fallback 로직 추가
        try:
            response = requests.post(crawl4ai_url, json=request_data, headers=headers, timeout=10)
        except requests.exceptions.ConnectionError:
            # Docker 컨테이너 연결 실패 시 localhost로 시도
            print(f"Docker 컨테이너 연결 실패, localhost로 시도합니다. (URL: {url})")
            crawl4ai_url = "http://localhost:11235/crawl"
            response = requests.post(crawl4ai_url, json=request_data, headers=headers)
        response.raise_for_status()  # 오류 확인
        
        task_id = response.json().get("task_id")
        if not task_id:
            print(f"작업 ID를 받지 못했습니다: {url}")
            return None
            
        # 결과 대기 (최대 2분)
        max_wait_time = 120  # 초
        start_time = asyncio.get_event_loop().time()
        
        while (asyncio.get_event_loop().time() - start_time) < max_wait_time:
            # 작업 상태 확인 - 동일한 인증 헤더 사용
            # 작업 상태 확인 URL도 동일하게 컨테이너명 사용
            status_url = f"http://crawl4ai-server:11235/task/{task_id}"
            try:
                status_response = requests.get(status_url, headers=headers, timeout=10)  # 인증 헤더 추가
            except requests.exceptions.ConnectionError:
                # Docker 컨테이너 연결 실패 시 localhost로 시도
                status_url = f"http://localhost:11235/task/{task_id}"
                status_response = requests.get(status_url, headers=headers)  # 인증 헤더 추가
            status_data = status_response.json()
            
            # 작업이 완료되었는지 확인
            if status_data.get("status") == "completed":
                # 결과 처리
                result = status_data.get("result", {})
                extracted_content = result.get("extracted_content")
                
                if extracted_content:
                    try:
                        # 추출된 콘텐츠가 문자열이면 JSON 파싱
                        if isinstance(extracted_content, str):
                            content = json.loads(extracted_content)
                        else:
                            content = extracted_content
                        
                        if content and len(content) > 0:
                            return {
                                "content": content[0].get("content", ""),
                                "images": content[0].get("article_images", [])
                            }
                        return None
                    except json.JSONDecodeError as e:
                        print(f"JSON 파싱 오류 ({url}): {str(e)}")
                        return None
                else:
                    print(f"기사 내용을 추출할 수 없습니다: {url}")
                    return None
            
            elif status_data.get("status") == "failed":
                print(f"크롤링 작업 실패 ({url}): {status_data.get('error', '알 수 없는 오류')}")
                return None
                
            # 아직 완료되지 않았으면 대기
            await asyncio.sleep(2)
        
        # 시간 초과
        print(f"시간 초과: 기사 내용 크롤링 작업이 완료되지 않았습니다 ({url})")
        return None
        
    except requests.RequestException as e:
        print(f"API 요청 오류 ({url}): {str(e)}")
        return None
    except Exception as e:
        print(f"기사 내용 크롤링 실패 ({url}): {str(e)}")
        return None


async def get_article(timestamp: str, category: str) -> Optional[List[Dict[str, Any]]]:
    """
    메타데이터 JSON 파일을 읽어 기사 내용을 크롤링하고 결과를 저장합니다.
    
    Args:
        timestamp: 파일명에 사용할 타임스탬프
        category: 기사 카테고리
        
    Returns:
        Optional[List[Dict[str, Any]]]: 크롤링된 기사 목록, 실패 시 None
        
    Raises:
        FileNotFoundError: 메타데이터 파일이 없는 경우 예외 발생
    """
    try:
        with open(TEMP_FILE, 'r', encoding='utf-8') as f:
            articles = json.load(f)
    except FileNotFoundError:
        error_msg = f"{TEMP_FILE} 파일을 찾을 수 없습니다."
        print(error_msg)
        raise FileNotFoundError(error_msg)

    print(f"\n총 {len(articles)}개의 기사 내용 수집 시작...")

    # 배치 단위로 처리
    for i in range(0, len(articles), BATCH_SIZE):
        batch = articles[i:i + BATCH_SIZE]
        
        # 현재 태스크 확인 및 취소 여부 확인
        current_task = asyncio.current_task()
        if current_task is not None and current_task.cancelled():
            print("크롤링이 취소되었습니다.")
            return None

        for j, article in enumerate(batch, 1):
            result = await get_article_content(article['link'])
            if result:
                article['content'] = result['content'].strip()
                article['stored_date'] = datetime.now().strftime("%Y%m%d")
                article['category'] = category
                article['img'] = result['images'][0] if result.get('images') else None
                print(f"기사 {i + j}/{len(articles)} 내용 수집 완료")
            
            # 봇 감지를 피하기 위한 임의의 딜레이 사용
            random_delay = MIN_DELAY + random.random() * (MAX_DELAY - MIN_DELAY)
            await asyncio.sleep(random_delay)

        print(f"배치 {i // BATCH_SIZE + 1}/{(len(articles) + BATCH_SIZE - 1) // BATCH_SIZE} 완료")

    data_dir = Path('data')
    news_file = data_dir / f'{category}_naver_news_with_contents_{timestamp}.json'

    data_dir.mkdir(parents=True, exist_ok=True)
    with open(news_file, 'w', encoding='utf-8') as f:
        json.dump(articles, f, ensure_ascii=False, indent=2)

    print("\n모든 기사 내용 수집 완료")
    return articles
