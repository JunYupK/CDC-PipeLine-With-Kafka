from datetime import datetime
import asyncio
import json
import os
from pathlib import Path
from typing import Dict, List, Optional, Any, Union, cast

from crawl4ai import AsyncWebCrawler, CacheMode
from crawl4ai.extraction_strategy import JsonCssExtractionStrategy


# 설정 상수
TEMP_FILE = 'naver_it_news.json'
BATCH_SIZE = 10
DEFAULT_DELAY = 1.0  # 초


async def get_article_content(url: str, crawler: AsyncWebCrawler) -> Optional[Dict[str, Any]]:
    """
    특정 URL에서 기사 내용과 이미지를 크롤링합니다.
    
    Args:
        url: 크롤링할 기사 URL
        crawler: 재사용할 AsyncWebCrawler 인스턴스
        
    Returns:
        Optional[Dict[str, Any]]: 크롤링된 기사 내용과 이미지 딕셔너리, 실패 시 None
    """
    article_schema = {
        "name": "Article Content",
        "baseSelector": "body",
        "fields": [
            {
                "name": "content",
                "selector": "#dic_area",
                "type": "text"
            },
            {
                "name": "article_images",
                "selector": "#dic_area img",
                "type": "attribute",
                "attribute": "src"
            }
        ]
    }

    content_strategy = JsonCssExtractionStrategy(article_schema, verbose=True)

    try:
        result = await crawler.arun(
            url=url,
            extraction_strategy=content_strategy,
            cache_mode=CacheMode.BYPASS,
        )

        content = json.loads(result.extracted_content)
        if content and len(content) > 0:
            return {
                "content": content[0].get("content", ""),
                "images": content[0].get("article_images", [])
            }
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
        async with AsyncWebCrawler(headless=True, verbose=True) as crawler:
            # 현재 태스크 확인 및 취소 여부 확인
            current_task = asyncio.current_task()
            if current_task is not None and current_task.cancelled():
                print("크롤링이 취소되었습니다.")
                return None

            for j, article in enumerate(batch, 1):
                result = await get_article_content(article['link'], crawler)
                if result:
                    article['content'] = result['content'].strip()
                    article['stored_date'] = datetime.now().strftime("%Y%m%d")
                    article['category'] = category
                    article['img'] = result['images'][0] if result.get('images') else None
                    print(f"기사 {i + j}/{len(articles)} 내용 수집 완료")
                await asyncio.sleep(DEFAULT_DELAY)

        print(f"배치 {i // BATCH_SIZE + 1} 완료")

    data_dir = Path('data')
    news_file = data_dir / f'{category}_naver_news_with_contents_{timestamp}.json'

    data_dir.mkdir(parents=True, exist_ok=True)
    with open(news_file, 'w', encoding='utf-8') as f:
        json.dump(articles, f, ensure_ascii=False, indent=2)

    print("\n모든 기사 내용 수집 완료")
    return articles