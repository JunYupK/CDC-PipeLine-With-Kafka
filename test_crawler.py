import asyncio
from crawl4ai import AsyncWebCrawler, CrawlerRunConfig
from crawl4ai.deep_crawling import BFSDeepCrawlStrategy
from crawl4ai.content_scraping_strategy import LXMLWebScrapingStrategy
from crawl4ai.extraction_strategy import JsonCssExtractionStrategy

async def main():
    # Configure a 2-level deep crawl
    schema = {
        "name": "Naver Sports News",
        "baseSelector": "#content > div > div.today_section.type_no_da",
        "fields": [
            {
                "name": "title",
                "selector": "a",
                "type": "text"
            }
        ]
    }
    extraction_strategy=JsonCssExtractionStrategy(schema)
    config = CrawlerRunConfig(
        deep_crawl_strategy=BFSDeepCrawlStrategy(
            max_depth=1,
            include_external=False
        ),
        extraction_strategy = extraction_strategy,
        scraping_strategy=LXMLWebScrapingStrategy(),
        verbose=True
    )

    async with AsyncWebCrawler() as crawler:
        results = await crawler.arun("https://sports.news.naver.com/index", config=config)

        print(f"Crawled {len(results)} pages in total")

        # Access individual results
        for result in results[:3]:  # Show first 3 results
            print(f"URL: {result.url}")
            print(f"Depth: {result.metadata.get('depth', 0)}")
            print(result.extracted_content)

if __name__ == "__main__":
    asyncio.run(main())