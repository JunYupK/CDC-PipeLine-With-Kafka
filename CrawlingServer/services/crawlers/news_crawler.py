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
        click_count: '더보기' 버튼을 클릭할 횟수
        
    Returns:
        Tuple[int, List[Dict]]: 수집된 기사 수와 기사 메타데이터 목록
    """
    # 동적 콘텐츠 로드를 위한 자바스크립트 코드
    js_code = f"""
        let totalLoadedItems = 0;
        
        for (let i = 0; i < {click_count}; i++) {{
            // 페이지 끝까지 스크롤
            window.scrollTo(0, document.body.scrollHeight);
            
            // 더보기 버튼 찾기 시도
            const moreButton = 
                document.querySelector('#newsct > div.section_latest > div > div.section_more > a') || 
                document.querySelector('.section_more > a') ||
                document.querySelector('.more_btn_inner');
                
            if (moreButton) {{
                moreButton.click();
                // 클릭 후 대기
                await new Promise(resolve => setTimeout(resolve, 1500));
            }}
        }}
    """
    
    # CSS 추출 스키마 정의
    schema = {
        "name": "Naver News",
        "baseSelector": "#section_body > ul > li, #newsct div.section_latest_article._CONTENT_LIST._PERSIST_META ul > li",
        "fields": [
            {
                "name": "title",
                "selector": "dt > a, div.sa_text > a > strong",
                "type": "text"
            },
            {
                "name": "link",
                "selector": "dt > a, div.sa_text > a",
                "type": "attribute",
                "attribute": "href"
            }
        ]
    }
    
    # Crawl4AI Docker API 요청 생성
    request_data = {
        "urls": url,
        "js_code": js_code,
        "wait_for": "css:#section_body, css:#newsct div.section_latest_article",
        "extraction_config": {
            "type": "json_css",
            "params": {
                "schema": schema
            }
        },
        "crawler_params": {
            "headless": True,
            "page_timeout": 120000,  # 2분 타임아웃
        },
        "extra": {
            "delay_before_return_html": 3.0,
        },
        "priority": 10
    }
    
    # API 토큰 설정
    api_token = "home"
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
        response.raise_for_status()  # 오류 확인
        
        task_id = response.json().get("task_id")
        if not task_id:
            print("작업 ID를 받지 못했습니다.")
            return 0, None
            
        # 결과 대기 (최대 3분)
        max_wait_time = 180  # 초
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
                            articles = json.loads(extracted_content)
                        else:
                            articles = extracted_content
                            
                        # URL 처리: 상대 경로인 경우 완전한 URL로 변환
                        for article in articles:
                            if article['link'] and not article['link'].startswith('http'):
                                article['link'] = f"https://news.naver.com{article['link']}"
                        
                        return len(articles), articles
                    except json.JSONDecodeError as e:
                        print(f"JSON 파싱 오류: {str(e)}")
                        # HTML이 반환된 경우 직접 추출 시도
                        html = result.get("html", "")
                        if html and len(html) > 10000:
                            try:
                                import re
                                title_pattern = r'<dt>\s*<a[^>]*href="([^"]+)"[^>]*>([^<]+)<'
                                matches = re.findall(title_pattern, html)
                                
                                if matches:
                                    articles = []
                                    for link, title in matches:
                                        if '/article/' in link:
                                            if not link.startswith('http'):
                                                link = 'https://news.naver.com' + link
                                            articles.append({
                                                'title': title.strip(),
                                                'link': link
                                            })
                                    
                                    if articles:
                                        print(f"{len(articles)}개의 기사를 직접 추출했습니다.")
                                        return len(articles), articles
                            except Exception as e:
                                print(f"직접 추출 오류: {str(e)}")
                        
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
