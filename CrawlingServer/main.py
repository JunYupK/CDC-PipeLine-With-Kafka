from fastapi import FastAPI
from crawl4ai import AsyncWebCrawler, CacheMode
from crawl4ai.extraction_strategy import JsonCssExtractionStrategy
import json


app = FastAPI(title="Hello World API")

@app.get("/")
async def hello_world():
    return {"message": "Hello, World!"}
@app.get("/crwal")
async def crwal_data():
    # 데이터 추출 스키마 정의
    schema = {
        "name": "Naver IT News",
        "baseSelector": "div.section_latest_article._CONTENT_LIST._PERSIST_META ul",  # 각 뉴스 항목을 감싸는 컨테이너
        "fields": [
            {
                "name": "title",
                "selector": "li div.sa_text > a > strong",
                "type": "text"
            },
            {
                "name": "link",
                "selector": "li div.sa_text > a",
                "type": "attribute",
                "attribute": "href"
            }
        ]
    }

    extraction_strategy = JsonCssExtractionStrategy(schema, verbose=True)

    async with AsyncWebCrawler(
            headless=False,
            verbose=True
    ) as crawler:

        result = await crawler.arun(
            url="https://news.naver.com/section/105",
            extraction_strategy=JsonCssExtractionStrategy(schema, verbose=True),
            cache_mode=CacheMode.BYPASS
        )
        # 결과 출력
        try:
            companies = json.loads(result.extracted_content)
            print(f"Successfully extracted {len(companies)} companies")
            print(companies)
            return companies
        except json.JSONDecodeError:
            print("Failed to parse JSON result")
            print("Raw result:", result.extracted_content)


@app.get("/health")
async def health_check():
    return {"status": "healthy"}