import asyncio
import json
import random
import requests
from typing import List, Dict, Any, Optional, Tuple

# Docker API 기반 크롤러 함수
async def naver_news_metadata_crawler(url: str, click_count: int = 5) -> Tuple[int, Optional[List[Dict[str, Any]]]]:
    """
    Docker 환경의 Crawl4AI API를 사용하여 네이버 뉴스 메타데이터를 크롤링하는 함수
    
    Args:
        url: 크롤링할 URL
        click_count: '더보기' 버튼을 클릭할 횟수 (더 이상 사용하지 않음)

    Returns:
        Tuple[int, List[Dict]]: 수집된 기사 수와 기사 메타데이터 목록
    """
    # CSS 추출 스키마 정의
    schema = {
        "name": "Naver News",
        "baseSelector": "li",
        "fields": [
            {
                "name": "title",
                "selector": "div.sa_text > a > strong, a.sa_text_title > strong.sa_text_strong",
                "type": "text"
            },
            {
                "name": "link",
                "selector": "div.sa_text > a, a.sa_text_title",
                "type": "attribute",
                "attribute": "href"
            }
        ]
    }

    # Crawl4AI Docker API 요청 생성
    request_data = {
        "urls": url,
        "extraction_config": {
            "type": "json_css",
            "params": {"schema": schema}
        },
        "crawler_params": {
            "headless": True,
            "page_timeout": 60000,  # 1분 타임아웃
            "wait_until": "domcontentloaded"
        },
        "extra": {
            "delay_before_return_html": 2.0,
        },
        "priority": 10
    }

    # API 토큰 설정
    api_token = "home"  # 환경에 맞게 수정
    headers = {"Authorization": f"Bearer {api_token}"}

    try:
        # API 호출 - Docker 환경에서는 'crawl4ai-server'라는 컨테이너명으로 접근해야 함
        crawl4ai_url = "http://crawl4ai-server:11235/crawl" # Docker 컨테이너 네트워크 내부에서 접근
        # 로컬 테스트용 fallback 로직 추가
        try:
            response = requests.post(crawl4ai_url, json=request_data, headers=headers, timeout=10)
        except requests.exceptions.ConnectionError:
            # Docker 컨테이너 연결 실패 시 localhost로 시도
            print("Docker 컨테이너 연결 실패, localhost로 시도합니다.")
            crawl4ai_url = "http://localhost:11235/crawl"
            response = requests.post(crawl4ai_url, json=request_data, headers=headers)

        # 응답 디버깅 정보
        print(f"API 응답 상태 코드: {response.status_code}")

        # 오류 확인
        if response.status_code != 200:
            print(f"API 오류: {response.text}")
            return 0, None

        task_id = response.json().get("task_id")
        if not task_id:
            print(f"작업 ID를 받지 못했습니다.")
            return 0, None

        # 결과 대기 (최대 3분)
        max_wait_time = 180  # 초
        start_time = asyncio.get_event_loop().time()

        while (asyncio.get_event_loop().time() - start_time) < max_wait_time:
            # 작업 상태 확인 URL
            status_url = f"http://crawl4ai-server:11235/task/{task_id}"
            try:
                status_response = requests.get(status_url, headers=headers, timeout=10)
            except requests.exceptions.ConnectionError:
                # Docker 컨테이너 연결 실패 시 localhost로 시도
                status_url = f"http://localhost:11235/task/{task_id}"
                status_response = requests.get(status_url, headers=headers)
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
                            articles = json.loads(extracted_content)
                        else:
                            articles = extracted_content

                        # 유효한 기사만 필터링
                        valid_articles = [
                            a for a in articles
                            if a.get("title") and a.get("link")
                        ]

                        # URL 처리: 상대 경로인 경우 완전한 URL로 변환
                        for article in valid_articles:
                            if article['link'] and not article['link'].startswith('http'):
                                article['link'] = f"https://news.naver.com{article['link']}"

                        print(f"{len(valid_articles)}개의 유효한 기사 메타데이터 수집 완료")
                        return len(valid_articles), valid_articles
                    except json.JSONDecodeError as e:
                        print(f"JSON 파싱 오류: {str(e)}")
                        return 0, None
                else:
                    print("추출된 콘텐츠가 없습니다.")
                    return 0, None

            elif status_data.get("status") == "failed":
                print(f"크롤링 작업 실패: {status_data.get('error', '알 수 없는 오류')}")
                return 0, None

            # 아직 완료되지 않았으면 대기
            await asyncio.sleep(3)

        # 시간 초과
        print("시간 초과: 작업이 완료되지 않았습니다.")
        return 0, None

    except requests.RequestException as e:
        print(f"API 요청 오류: {str(e)}")
        return 0, None
    except Exception as e:
        print(f"크롤링 중 오류 발생: {str(e)}")
        return 0, None


async def meta_crawling(url: str) -> Optional[List[Dict[str, Any]]]:
    """
    네이버 뉴스 메타데이터 크롤링 및 저장을 처리하는 함수
    
    Args:
        url: 크롤링할 URL
        
    Returns:
        Optional[List[Dict]]: 수집된 기사 메타데이터 목록
    """
    # 설정값
    max_retries = 5
    min_articles = 30
    current_retry = 0
    click_count = 3
    temp_file = 'naver_it_news.json'

    while current_retry < max_retries:
        try:
            print(f"\n시도 {current_retry + 1}/{max_retries}")
            article_count, articles = await naver_news_metadata_crawler(url, click_count)

            if articles and article_count >= min_articles:
                print(f"\n성공: {article_count}개의 기사를 수집했습니다.")

                # 결과 저장 (상위 data 폴더가 아닌 임시 파일로 저장)
                with open(temp_file, 'w', encoding='utf-8') as f:
                    json.dump(articles, f, ensure_ascii=False, indent=2)

                return articles
            
            # 기사가 없거나 목표보다 적은 경우
            if articles is None:
                print(f"기사 수집 실패")
            else:
                print(f"\n수집된 기사 수({article_count})가 목표({min_articles})에 미달됩니다.")
                
            current_retry += 1
            if current_retry < max_retries:
                print(f"{current_retry + 1}번째 재시도 시작...")
                click_count += 2
                # 재시도 간 대기 시간 증가 (복구 시간 확보)
                await asyncio.sleep(current_retry * 2 + 3)
        except Exception as e:
            print(f"크롤링 중 예외 발생: {str(e)}")
            current_retry += 1
            if current_retry < max_retries:
                print(f"{current_retry + 1}번째 재시도 시작...")
                await asyncio.sleep(current_retry * 2 + 3)
    
    return None
