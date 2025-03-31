import asyncio
import json
import random
import re
from typing import Tuple, List, Dict, Any, Optional

from crawl4ai import JsonCssExtractionStrategy, AsyncWebCrawler, CacheMode


async def naver_news_metadata_crawler(url: str, click_count: int = 5) -> Tuple[int, Optional[List[Dict[str, Any]]]]:
    """
    네이버 뉴스 메타데이터(제목, 링크)를 크롤링하는 함수
    
    Args:
        url: 크롤링할 URL
        click_count: '더보기' 버튼을 클릭할 횟수
        
    Returns:
        Tuple[int, List[Dict]]: 수집된 기사 수와 기사 메타데이터 목록
    """
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

    extraction_strategy = JsonCssExtractionStrategy(schema, verbose=True)

    preload_js = f"""
        new Promise((resolve) => {{
            let totalLoadedItems = 0;

            function clickMore(count) {{
                if (count <= 0) {{
                    console.log(`최종 로드된 항목 수: ${{totalLoadedItems}}`);
                    resolve();
                    return;
                }}

                window.scrollTo(0, document.body.scrollHeight);
                
                // 더보기 버튼 찾기 - 여러 선택자 시도
                const moreButton = 
                    document.querySelector('#newsct > div.section_latest > div > div.section_more > a') || 
                    document.querySelector('.section_more > a') ||
                    document.querySelector('.more_btn_inner');
                    
                const currentItems = document.querySelectorAll('#section_body > ul > li, #newsct div.section_latest_article._CONTENT_LIST._PERSIST_META ul > li').length;

                if (moreButton) {{
                    moreButton.click();
                    setTimeout(() => {{
                        const newItems = document.querySelectorAll('#section_body > ul > li, #newsct div.section_latest_article._CONTENT_LIST._PERSIST_META ul > li').length;
                        if (newItems > currentItems) {{
                            totalLoadedItems = newItems;
                            console.log(`${{count}}번 남음, 현재 ${{newItems}}개 항목 로드됨`);
                            clickMore(count - 1);
                        }} else {{
                            console.log('새로운 항목이 로드되지 않았습니다.');
                            resolve();
                        }}
                    }}, 1500);
                }} else {{
                    console.log('더보기 버튼을 찾을 수 없습니다.');
                    resolve();
                }}
            }}

            clickMore({click_count});
        }});
    """

    try:
        browser_args = ['--no-sandbox', '--disable-dev-shm-usage']
        
        # 랜덤 지연 추가 - 봇 감지 피하기
        await asyncio.sleep(1 + random.random() * 2)

        async with AsyncWebCrawler(headless=True, verbose=True, browser_args=browser_args) as crawler:
            result = await crawler.arun(
                url=url,
                js_code=preload_js,
                wait_for="css:#section_body, css:#newsct div.section_latest_article",
                extraction_strategy=extraction_strategy,
                cache_mode=CacheMode.BYPASS,
                delay_before_return_html=3.0,
                page_timeout=180000,  # 3분 타임아웃
                retry_count=3,  # 재시도 횟수
                retry_delay=2500,  # 재시도 사이 대기 시간(ms)
                wait_timeout=120000  # 셀렉터 대기 타임아웃 2분
            )

            # 결과 처리
            if not result.extracted_content:
                print(f"페이지에서 내용을 추출할 수 없습니다: {url}")
                # 빈 HTML이 반환되었을 수 있으므로 직접 확인
                print(f"HTML 내용 길이: {len(result.html or '')}")
                
                # HTML이 있지만 추출된 콘텐츠가 없는 경우 - 직접 추출 시도
                if len(result.html or '') > 10000:  # HTML이 충분히 있는 경우
                    try:
                        print("HTML이 있지만 추출된 콘텐츠가 없습니다. 직접 추출을 시도합니다.")
                        html = result.html
                        
                        # 제목과 링크 추출
                        title_pattern = r'<dt>\s*<a[^>]*href="([^"]+)"[^>]*>([^<]+)<'
                        matches = re.findall(title_pattern, html)
                        
                        if matches:
                            articles = []
                            for link, title in matches:
                                # 네이버 기사 링크만 추출
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
                        print(f"직접 추출 시도 시 오류 발생: {str(e)}")
                
                return 0, None
            
            # JSON 파싱 오류 처리
            try:
                articles = json.loads(result.extracted_content)
                if not articles:
                    return 0, []
                return len(articles), articles
            except json.JSONDecodeError as e:
                print(f"JSON 파싱 오류: {str(e)}")
                print(f"추출된 내용 일부: {result.extracted_content[:100] if result.extracted_content else 'None'}")
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