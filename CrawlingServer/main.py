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
    # schema = {
    #     "name": "Naver IT News",
    #     "baseSelector": ".section_component as_section_headline _PERSIST_CONTENT > div",  # 각 뉴스 항목을 감싸는 컨테이너
    #     "fields": [
    #         {
    #             "name": "title",
    #             "selector": "#_SECTION_HEADLINE_LIST_hqxz0",
    #             "type": "text"
    #         }
    #     ]
    # }
    #
    # extraction_strategy = JsonCssExtractionStrategy(schema, verbose=True)

    async with AsyncWebCrawler(
            headless=False,
            verbose=True
    ) as crawler:

        result = await crawler.arun(
            url="https://www.naver.com",
            extraction_strategy=JsonCssExtractionStrategy(schema, verbose=True),
            cache_mode=CacheMode.BYPASS
        )
        # 결과 출력
        try:
            companies = json.loads(result.extracted_content)
            print(f"Successfully extracted {len(companies)} companies")
            print(json.dumps(companies[0], indent=2))
            return companies
        except json.JSONDecodeError:
            print("Failed to parse JSON result")
            print("Raw result:", result.extracted_content)


@app.get("/test")
async def crwal_test():
    async with AsyncWebCrawler(verbose=True) as crawler:
        result = await crawler.arun(url="https://www.nbcnews.com/business")
        # Soone will be change to result.markdown
        print(result.markdown_v2.raw_markdown)
        return  result

@app.get("/health")
async def health_check():
    return {"status": "healthy"}