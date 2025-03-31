import asyncio
import json
import os
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Any, Tuple

from crawl4ai import AsyncWebCrawler, CacheMode, JsonCssExtractionStrategy


async def crawl_sports_page(category: str, page_num: int = 1) -> Optional[List[Dict[str, Any]]]:
    """
    네이버 스포츠 특정 카테고리의 특정 페이지 뉴스 크롤링

    Args:
        category: 스포츠 카테고리 코드 (kbaseball, wbaseball 등)
        page_num: 크롤링할 페이지 번호 (1부터 시작)

    Returns:
        Optional[List[Dict]]: 수집된 기사 메타데이터 목록, 실패 시 None
    """
    # 페이지 번호에 따른 URL 생성
    base_url = f"https://sports.news.naver.com/{category}/news/index"
    url = base_url if page_num == 1 else f"{base_url}?page={page_num}"

    print(f"크롤링: {category} 카테고리 {page_num} 페이지 - {url}")

    # 뉴스 목록 추출 스키마
    schema = {
        "name": "Naver Sports News",
        "baseSelector": "ul > li.today_item, #_newsList > ul > li, .news_list ul.board_list > li",
        "fields": [
            {
                "name": "title",
                "selector": "a.title",
                "type": "text"
            },
            {
                "name": "link",
                "selector": "a.title",
                "type": "attribute",
                "attribute": "href"
            },
            {
                "name": "press",
                "selector": "span.press",
                "type": "text"
            },
            {
                "name": "time",
                "selector": "span.time",
                "type": "text"
            }
        ]
    }

    extraction_strategy = JsonCssExtractionStrategy(schema, verbose=True)

    try:
        async with AsyncWebCrawler(headless=True, verbose=True) as crawler:
            # 기다릴 셀렉터를 여러 개 허용하여 더 유연하게 작동하도록 설정
            wait_options = [
                "css:#_newsList"
            ]

            # 더 긴 시간을 기다리고 페이지가 로드되기를 기다림
            result = await crawler.arun(
                url=url,
                wait_for=wait_options,
                extraction_strategy=extraction_strategy,
                cache_mode=CacheMode.BYPASS,
                delay_before_return_html=5.0,  # 더 긴 딜레이
                page_timeout=90000  # 타임아웃 증가 (90초)
            )
                    
            # 결과가 있는지 확인
            if not result.extracted_content:
                print(f"페이지에서 내용을 추출할 수 없습니다: {url}")
                # 빈 HTML이 반환되었을 수 있으므로 직접 확인
                print(f"HTML 내용 길이: {len(result.html or '')}")
                return None

            articles = json.loads(result.extracted_content)

            # 빈 결과인지 확인
            if not articles or len(articles) == 0:
                print(f"페이지에서 기사를 찾을 수 없습니다: {url}")
                return None

            # URL 처리: 상대 경로인 경우 완전한 URL로 변환
            for article in articles:
                if article['link'] and not article['link'].startswith('http'):
                    article['link'] = f"https://sports.news.naver.com{article['link']}"

            return articles

    except Exception as e:
        print(f"크롤링 중 오류 발생: {str(e)}")
        return None


async def fetch_article_content(url: str, crawler: AsyncWebCrawler) -> Optional[Dict[str, Any]]:
    """
    네이버 스포츠 기사 내용과 이미지를 크롤링합니다.

    Args:
        url: 크롤링할 기사 URL
        crawler: 재사용할 AsyncWebCrawler 인스턴스

    Returns:
        Optional[Dict[str, Any]]: 크롤링된 기사 내용과 이미지 딕셔너리, 실패 시 None
    """
    article_schema = {
        "name": "Sports Article Content",
        "baseSelector": "body",
        "fields": [
            {
                "name": "content",
                "selector": "#newsEndContents",
                "type": "text"
            },
            {
                "name": "article_images",
                "selector": "#newsEndContents img",
                "type": "attribute",
                "attribute": "src"
            },
            {
                "name": "published_date",
                "selector": ".news_headline .info span:first-child",
                "type": "text"
            }
        ]
    }

    content_strategy = JsonCssExtractionStrategy(article_schema, verbose=True)

    try:
        result = await crawler.arun(
            url=url,
            extraction_strategy=content_strategy,
            cache_mode=CacheMode.BYPASS,
            page_timeout=90000,  # 1분 30초 타임아웃
            retry_count=2,  # 재시도 횟수
            retry_delay=2000,  # 재시도 사이 대기 시간(ms)
            delay_before_return_html=2.0  # 페이지 로드 후 약간의 대기 시간
        )

        content = json.loads(result.extracted_content)
        if content and len(content) > 0:
            return {
                "content": content[0].get("content", ""),
                "images": content[0].get("article_images", []),
                "published_date": content[0].get("published_date", "")
            }
        return None

    except Exception as e:
        print(f"기사 내용 크롤링 실패 ({url}): {str(e)}")
        return None


async def fetch_multiple_pages(category: str, max_pages: int = 5) -> List[Dict[str, Any]]:
    """
    여러 페이지의 스포츠 뉴스를 수집합니다.

    Args:
        category: 스포츠 카테고리 (예: kbaseball, wbaseball 등)
        max_pages: 크롤링할 최대 페이지 수

    Returns:
        List[Dict[str, Any]]: 수집된 모든 기사의 메타데이터 목록
    """
    all_articles = []

    for page in range(1, max_pages + 1):
        # 최대 3번 재시도
        for attempt in range(3):
            try:
                articles = await crawl_sports_page(category, page)

                if not articles or len(articles) == 0:
                    if attempt < 2:  # 아직 재시도 기회가 있음
                        print(f"재시도 {attempt + 1}/3: {category} 카테고리 {page} 페이지")
                        await asyncio.sleep(3)  # 잠시 대기 후 재시도
                        continue
                    else:
                        print(f"{category} 카테고리 {page} 페이지에서 기사를 찾을 수 없습니다. (3번 시도 후)")
                        break  # 이 페이지 크롤링 중단

                print(f"{category} 카테고리 {page} 페이지에서 {len(articles)}개 기사 수집")
                all_articles.extend(articles)
                break  # 성공했으므로 재시도 루프 종료

            except Exception as e:
                if attempt < 2:  # 아직 재시도 기회가 있음
                    print(f"재시도 {attempt + 1}/3: {category} 카테고리 {page} 페이지 - 오류: {str(e)}")
                    await asyncio.sleep(3)  # 잠시 대기 후 재시도
                else:
                    print(f"{category} 카테고리 {page} 페이지 크롤링 실패 (3번 시도 후): {str(e)}")

        # 서버에 과부하를 주지 않기 위한 딜레이
        await asyncio.sleep(2)

    print(f"{category} 카테고리에서 총 {len(all_articles)}개 기사 메타데이터 수집 완료")
    return all_articles


async def process_article_contents(articles: List[Dict[str, Any]], category: str, timestamp: str) -> List[
    Dict[str, Any]]:
    """
    수집된 기사 메타데이터를 바탕으로 각 기사의 내용을 크롤링합니다.

    Args:
        articles: 메타데이터가 수집된 기사 목록
        category: 스포츠 카테고리
        timestamp: 저장 파일명에 사용할 타임스탬프

    Returns:
        List[Dict[str, Any]]: 내용이 추가된 기사 목록
    """
    if not articles or len(articles) == 0:
        print(f"{category} 카테고리에서 수집된 기사가 없습니다.")
        return []

    # 배치 처리 상수
    BATCH_SIZE = 10
    DEFAULT_DELAY = 1.0  # 초

    print(f"\n총 {len(articles)}개의 기사 내용 수집 시작...")

    # 배치 단위로 처리
    for i in range(0, len(articles), BATCH_SIZE):
        batch = articles[i:i + BATCH_SIZE]
        async with AsyncWebCrawler(headless=True, verbose=True) as crawler:
            # 현재 태스크 확인 및 취소 여부 확인
            current_task = asyncio.current_task()
            if current_task is not None and current_task.cancelled():
                print("크롤링이 취소되었습니다.")
                return articles

            for j, article in enumerate(batch, 1):
                # 최대 3번 재시도
                for attempt in range(3):
                    try:
                        result = await fetch_article_content(article['link'], crawler)
                        if result:
                            article['content'] = result['content'].strip()
                            article['stored_date'] = datetime.now().strftime("%Y%m%d")
                            article['sport_category'] = category
                            article['img'] = result['images'][0] if result.get('images') else None
                            article['published_date'] = result.get('published_date', '')
                            print(f"기사 {i + j}/{len(articles)} 내용 수집 완료")
                            break  # 성공했으므로 재시도 루프 종료
                        elif attempt < 2:  # 아직 재시도 기회가 있음
                            print(f"재시도 {attempt + 1}/3: 기사 {i + j}/{len(articles)} 내용")
                            await asyncio.sleep(2)  # 잠시 대기 후 재시도
                        else:
                            print(f"기사 {i + j}/{len(articles)} 내용 수집 실패 (3번 시도 후)")
                    except Exception as e:
                        if attempt < 2:  # 아직 재시도 기회가 있음
                            print(f"재시도 {attempt + 1}/3: 기사 {i + j}/{len(articles)} - 오류: {str(e)}")
                            await asyncio.sleep(2)  # 잠시 대기 후 재시도
                        else:
                            print(f"기사 {i + j}/{len(articles)} 수집 실패 (3번 시도 후): {str(e)}")

                await asyncio.sleep(DEFAULT_DELAY)

        print(f"배치 {i // BATCH_SIZE + 1}/{(len(articles) + BATCH_SIZE - 1) // BATCH_SIZE} 완료")

    # 데이터 디렉토리 확인 및 생성
    data_dir = Path('data')
    sports_file = data_dir / f'sports_{category}_news_with_contents_{timestamp}.json'

    data_dir.mkdir(parents=True, exist_ok=True)
    with open(sports_file, 'w', encoding='utf-8') as f:
        json.dump(articles, f, ensure_ascii=False, indent=2)

    print(f"\n모든 기사 내용 수집 완료. 파일 저장: {sports_file}")
    return articles


# 카테고리 매핑 딕셔너리 - 표시명과 코드 매핑
CATEGORY_MAPPING = {
    "야구": "kbaseball",
    "해외야구": "wbaseball",
    "축구": "kfootball",
    "해외축구": "wfootball",
    "농구": "basketball",
    "배구": "volleyball",
    "골프": "golf",
    "일반": "general"
}


async def crawl_sports_news(category: str, max_pages: int = 5) -> Optional[List[Dict[str, Any]]]:
    """
    특정 스포츠 카테고리의 뉴스를 크롤링합니다.

    Args:
        category: 스포츠 카테고리 코드 (kbaseball, wbaseball 등) 또는 한글명 (야구, 해외야구 등)
        max_pages: 크롤링할 최대 페이지 수

    Returns:
        Optional[List[Dict[str, Any]]]: 크롤링된 기사 목록, 실패 시 None
    """
    # 한글 카테고리명이 입력된 경우 코드로 변환
    if category in CATEGORY_MAPPING.values():
        category_code = category
    else:
        # 한글 카테고리명 -> 코드 변환 시도
        category_code = CATEGORY_MAPPING.get(category)
        if not category_code:
            print(f"유효하지 않은 스포츠 카테고리: {category}")
            print(f"유효한 카테고리: {', '.join(CATEGORY_MAPPING.keys())} 또는 {', '.join(CATEGORY_MAPPING.values())}")
            return None

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    try:
        # 1. 여러 페이지의 메타데이터 수집
        articles = await fetch_multiple_pages(category_code, max_pages)

        if not articles or len(articles) == 0:
            print(f"{category} 메타데이터 크롤링 실패")
            return None

        # 2. 각 기사의 내용 크롤링
        result = await process_article_contents(articles, category_code, timestamp)
        return result

    except Exception as e:
        print(f"크롤링 과정에서 오류 발생: {str(e)}")
        return None


# 단독 테스트용 코드
if __name__ == "__main__":
    import sys


    async def test_single_category():
        category = sys.argv[1] if len(sys.argv) > 1 else "야구"
        pages = int(sys.argv[2]) if len(sys.argv) > 2 else 3

        print(f"{category} 카테고리 {pages} 페이지 크롤링 시작...")
        result = await crawl_sports_news(category, pages)

        if result:
            print(f"성공: {len(result)}개 기사 크롤링 완료")
        else:
            print(f"실패: {category} 카테고리 크롤링 실패")


    asyncio.run(test_single_category())