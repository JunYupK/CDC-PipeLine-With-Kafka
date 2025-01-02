from datetime import datetime

from crawl4ai import AsyncWebCrawler, CacheMode
from crawl4ai.extraction_strategy import JsonCssExtractionStrategy
import asyncio
import json
import os
from pathlib import Path

async def get_article_content(url, crawler):
    article_schema = {
        "name": "Article Content",
        "baseSelector": "body",
        "fields": [
            {
                "name": "content",
                "selector": "#dic_area",  # 직접 선택자로 지정
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
        )

        content = json.loads(result.extracted_content)
        if content and len(content) > 0:
            return content[0].get("content", "")
        return None

    except Exception as e:
        print(f"기사 내용 크롤링 실패 ({url}): {str(e)}")
        return None


async def get_article(timestamp, category):
    try:
        with open('naver_it_news.json', 'r', encoding='utf-8') as f:
            articles = json.load(f)
    except FileNotFoundError:
        print("naver_it_news.json 파일을 찾을 수 없습니다.")
        return

    async with AsyncWebCrawler(headless=False, verbose=True) as crawler:
        print(f"\n총 {len(articles)}개의 기사 내용 수집 시작...")

        for i, article in enumerate(articles, 1):
            content = await get_article_content(article['link'], crawler)
            if content:
                article['content'] = content.strip()  # 앞뒤 공백 제거
                article['stored_date'] = datetime.now().strftime("%Y%m%d")
                article['category'] = category
                article['img'] = None
                print(f"기사 {i}/{len(articles)} 내용 수집 완료")
            await asyncio.sleep(1)  # 과도한 요청 방지

        data_dir = Path(os.path.dirname(os.path.abspath(__file__))).parent / 'data'
        news_file = data_dir / f'{category}_naver_news_with_contents_{timestamp}.json'
        with open(news_file, 'w', encoding='utf-8') as f:
            json.dump(articles, f, ensure_ascii=False, indent=2)


        print("\n모든 기사 내용 수집 완료")
        return articles