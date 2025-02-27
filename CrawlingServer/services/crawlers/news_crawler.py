import asyncio
import json
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
        "baseSelector": "#newsct div.section_latest_article._CONTENT_LIST._PERSIST_META ul > li",
        "fields": [
            {
                "name": "title",
                "selector": "div.sa_text > a > strong",
                "type": "text"
            },
            {
                "name": "link",
                "selector": "div.sa_text > a",
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
                const moreButton = document.querySelector('#newsct > div.section_latest > div > div.section_more > a');
                const currentItems = document.querySelectorAll('#newsct div.section_latest_article._CONTENT_LIST._PERSIST_META ul > li').length;

                if (moreButton) {{
                    moreButton.click();
                    setTimeout(() => {{
                        const newItems = document.querySelectorAll('#newsct div.section_latest_article._CONTENT_LIST._PERSIST_META ul > li').length;
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
        async with AsyncWebCrawler(headless=True, verbose=True) as crawler:
            result = await crawler.arun(
                url=url,
                js_code=preload_js,
                wait_for="css:#newsct div.section_latest_article",
                extraction_strategy=extraction_strategy,
                cache_mode=CacheMode.BYPASS,
                delay_before_return_html=8.0
            )

            articles = json.loads(result.extracted_content)
            return len(articles), articles

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
        print(f"\n시도 {current_retry + 1}/{max_retries}")
        article_count, articles = await naver_news_metadata_crawler(url, click_count)

        if articles and article_count >= min_articles:
            print(f"\n성공: {article_count}개의 기사를 수집했습니다.")

            # 결과 저장 (상위 data 폴더가 아닌 임시 파일로 저장)
            with open(temp_file, 'w', encoding='utf-8') as f:
                json.dump(articles, f, ensure_ascii=False, indent=2)

            return articles

        current_retry += 1
        if current_retry < max_retries:
            print(f"\n수집된 기사 수({article_count})가 목표({min_articles})에 미달됩니다.")
            print(f"{current_retry + 1}번째 재시도 시작...")
            click_count += 2
            await asyncio.sleep(3)
    
    return None