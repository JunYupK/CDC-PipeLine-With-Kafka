import asyncio
import json
from crawl4ai import AsyncWebCrawler, CrawlerRunConfig, CacheMode
from crawl4ai.extraction_strategy import JsonCssExtractionStrategy

async def main():
    # 네이버 뉴스 CSS 선택자 테스트
    schema = {
        "name": "Naver News List",
        "baseSelector": "li", # 모든 li 요소를 선택, 필터링은 필드 선택자에서 처리
        "fields": [
            {
                "name": "title",
                "selector": "div.sa_text > a > strong, a.sa_text_title > strong.sa_text_strong",
                "type": "text"
            },
            {
                "name": "link",
                "selector": "div.sa_text > a, a.sa_text_title",
                "type": "attribute",
                "attribute": "href"
            }
        ]
    }

    # CrawlerRunConfig 객체 생성
    config = CrawlerRunConfig(
        cache_mode=CacheMode.BYPASS,
        extraction_strategy=JsonCssExtractionStrategy(schema),
        delay_before_return_html=3.0
    )

    try:
        # 크롤러 초기화 및 실행
        async with AsyncWebCrawler(verbose=True) as crawler:
            # config 객체 전달
            result = await crawler.arun(
                url="https://news.naver.com/section/105",
                config=config
            )

            # 결과 처리
            if result and result.extracted_content:
                articles = json.loads(result.extracted_content)
                print(f"추출된 기사 수: {len(articles)}")

                # 유효한 항목 필터링
                valid_articles = [a for a in articles if a.get("title") and a.get("link")]
                print(f"유효한 기사 수: {len(valid_articles)}")

                # 샘플 출력
                if valid_articles:
                    print("\n첫 번째 기사 샘플:")
                    print(f"제목: {valid_articles[0]['title']}")
                    print(f"링크: {valid_articles[0]['link']}")

                # 전체 결과 저장 (디버깅용)
                with open("naver_news_results.json", "w", encoding="utf-8") as f:
                    json.dump(articles, f, ensure_ascii=False, indent=2)
                print("\n결과가 naver_news_results.json 파일에 저장되었습니다.")
            else:
                print("추출된 콘텐츠가 없습니다.")

                # HTML 내용 확인 (디버깅용)
                if result and result.html:
                    print(f"HTML 길이: {len(result.html)}")
                    print(f"HTML 샘플: {result.html[:500]}...")

                    # HTML 저장
                    with open("naver_news_page.html", "w", encoding="utf-8") as f:
                        f.write(result.html)
                    print("HTML이 naver_news_page.html 파일에 저장되었습니다.")
                else:
                    print("HTML 내용이 없습니다.")

    except Exception as e:
        print(f"오류 발생: {type(e).__name__}, {str(e)}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    asyncio.run(main())