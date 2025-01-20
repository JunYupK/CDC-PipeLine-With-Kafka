from datetime import datetime
from crawl4ai import AsyncWebCrawler, CacheMode
from crawl4ai.extraction_strategy import JsonCssExtractionStrategy
import asyncio
import json
import os
from pathlib import Path

#crwaling_from_url
async def get_article_content(url, crawler):  # crawler를 매개변수로 받도록 수정
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

async def get_article(timestamp, category):
    try:
        with open('naver_it_news.json', 'r', encoding='utf-8') as f:
            articles = json.load(f)
    except FileNotFoundError:
        print("naver_it_news.json 파일을 찾을 수 없습니다.")
        return

    print(f"\n총 {len(articles)}개의 기사 내용 수집 시작...")

    # 배치 크기 설정
    BATCH_SIZE = 10

    # 배치 단위로 처리
    for i in range(0, len(articles), BATCH_SIZE):
        batch = articles[i:i + BATCH_SIZE]
        async with AsyncWebCrawler(headless=True, verbose=True) as crawler:
            if asyncio.current_task().cancelled():
                print("크롤링이 취소되었습니다.")
                return 0, None

            for j, article in enumerate(batch, 1):
                result = await get_article_content(article['link'], crawler)
                if result:
                    article['content'] = result['content'].strip()
                    article['stored_date'] = datetime.now().strftime("%Y%m%d")
                    article['category'] = category
                    article['img'] = result['images'][0] if result.get('images') else None
                    print(f"기사 {i + j}/{len(articles)} 내용 수집 완료")
                await asyncio.sleep(1)

        print(f"배치 {i // BATCH_SIZE + 1} 완료")

    data_dir = Path(os.path.dirname(os.path.abspath(__file__))).parent / 'data'
    news_file = data_dir / f'{category}_naver_news_with_contents_{timestamp}.json'
    with open(news_file, 'w', encoding='utf-8') as f:
        json.dump(articles, f, ensure_ascii=False, indent=2)

    print("\n모든 기사 내용 수집 완료")
    return articles