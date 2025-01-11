from crawl4ai import AsyncWebCrawler, CacheMode
from crawl4ai.extraction_strategy import JsonCssExtractionStrategy
import asyncio
import json


async def test_article_crawl(url):
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
                "name": "article_images",  # 새로운 필드 추가
                "selector": "#dic_area img",  # #dic_area 내의 모든 img 태그 선택
                "type": "attribute",  # 속성을 가져오기 위해 type을 attribute로 설정
                "attribute": "src"    # src 속성을 가져옴
            }
        ]
    }

    content_strategy = JsonCssExtractionStrategy(article_schema, verbose=True)

    async with AsyncWebCrawler(headless=False, verbose=True) as crawler:
        try:
            result = await crawler.arun(
                url=url,
                extraction_strategy=content_strategy,
                cache_mode=CacheMode.BYPASS,
            )

            # 본문 내용 추출
            article_content = None
            if result.extracted_content:
                content = json.loads(result.extracted_content)
                if content and len(content) > 0:
                    article_content = content[0].get("content", "")

            # 이미지 추출 로직 수정
            images = []
            if result.media and 'images' in result.media:
                print("\n디버깅: 발견된 모든 이미지:")
                for img in result.media['images']:
                    print(f"이미지 정보: {img}")
                    if 'src' in img:  # news.naver.com 체크 제거
                        images.append({
                            'url': img['src'],
                            'alt': img.get('alt', ''),
                            'desc': img.get('desc', '')
                        })

            print("\n=== 크롤링 결과 ===")
            print("\n1. 기사 본문:")
            print("-" * 50)
            print(article_content.strip() if article_content else "본문을 찾을 수 없습니다.")
            print("-" * 50)

            print("\n2. 본문 이미지:")
            print("-" * 50)
            if images:
                for img in images:
                    print(f"이미지 URL: {img['url']}")
                    if img['alt']:
                        print(f"이미지 설명: {img['alt']}")
                    print("---")
            else:
                print("본문 이미지가 없습니다.")
            print("-" * 50)

            print("******************************************************************")
            print(result.extracted_content)

            return {
                'content': article_content,
                'images': images
            }

        except Exception as e:
            print(f"크롤링 실패: {str(e)}")
            return None


async def main():
    test_url = "https://n.news.naver.com/mnews/article/366/0001046542"

    print("크롤링 시작...")
    await test_article_crawl(test_url)
    print("\n크롤링 완료!")


if __name__ == "__main__":
    asyncio.run(main())