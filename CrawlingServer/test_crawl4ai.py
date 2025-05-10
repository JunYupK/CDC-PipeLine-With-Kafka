import asyncio
import aiohttp
import json
import time
import sys
from pprint import pprint

# API 설정
API_BASE_URL = "http://localhost:11235"
API_TOKEN = "home"
HEADERS = {"Authorization": f"Bearer {API_TOKEN}"}

# 테스트할 카테고리 및 URL 정의
CATEGORIES = [
    {"name": "정치", "url": "https://news.naver.com/section/100"},
    {"name": "경제", "url": "https://news.naver.com/section/101"},
    {"name": "사회", "url": "https://news.naver.com/section/102"},
    {"name": "생활문화", "url": "https://news.naver.com/section/103"},
    {"name": "세계", "url": "https://news.naver.com/section/104"},
    {"name": "IT과학", "url": "https://news.naver.com/section/105"}
]

async def test_category(category_name, url):
    """
    특정 카테고리의 뉴스를 크롤링 테스트
    """
    print(f"\n=== {category_name} 카테고리 테스트 ===")
    print(f"URL: {url}")

    # 네이버 뉴스 현재 구조에 맞는 선택자
    schema = {
        "name": f"Naver {category_name} News",
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

    async with aiohttp.ClientSession() as session:
        print(f"API 요청 전송 중...")
        payload = {
            "urls": url,
            "extraction_config": {
                "type": "json_css",
                "params": {"schema": schema}
            },
            "crawler_params": {
                "headless": True,
                "page_timeout": 60000,
                "wait_until": "domcontentloaded"
            },
            "extra": {
                "delay_before_return_html": 2.0
            },
            "priority": 10
        }

        async with session.post(f"{API_BASE_URL}/crawl", json=payload, headers=HEADERS) as response:
            print(f"응답 상태 코드: {response.status}")

            if response.status == 200:
                data = await response.json()
                task_id = data.get("task_id")
                print(f"작업 ID: {task_id}")

                # 결과 대기
                result = await poll_for_result(session, task_id)
                if result:
                    extracted_content = result.get("extracted_content")
                    if extracted_content:
                        try:
                            articles = json.loads(extracted_content) if isinstance(extracted_content, str) else extracted_content
                            print(f"추출된 기사 수: {len(articles)}")

                            # 유효한 링크와 타이틀이 있는 기사만 필터링
                            valid_articles = [
                                a for a in articles
                                if a.get("title") and a.get("link") and ("/mnews/article/" in a.get("link", "") or "news.naver.com" in a.get("link", ""))
                            ]
                            print(f"유효한 기사 수: {len(valid_articles)}")

                            if valid_articles:
                                print("\n첫 번째 기사 샘플:")
                                print(f"제목: {valid_articles[0]['title']}")
                                print(f"링크: {valid_articles[0]['link']}")

                            return {
                                "success": True,
                                "count": len(valid_articles),
                                "articles": valid_articles
                            }
                        except Exception as e:
                            print(f"추출된 콘텐츠 파싱 오류: {str(e)}")
                    else:
                        print("추출된 콘텐츠가 없습니다.")
                        print(f"HTML 길이: {len(result.get('html', ''))}")
            else:
                error_text = await response.text()
                print(f"오류: {error_text}")

    return {"success": False}

async def poll_for_result(session, task_id, poll_interval=2, max_wait_time=90):
    """
    작업 결과를 폴링하여 가져오기
    """
    start_time = time.time()
    while (time.time() - start_time) < max_wait_time:
        async with session.get(f"{API_BASE_URL}/task/{task_id}", headers=HEADERS) as response:
            if response.status == 200:
                data = await response.json()
                status = data.get("status")
                print(f"작업 상태: {status}")

                if status == "completed":
                    print("작업 완료!")
                    return data.get("result")
                elif status == "failed":
                    print(f"작업 실패: {data.get('error')}")
                    return None
            else:
                print(f"상태 확인 오류: {response.status}")

        await asyncio.sleep(poll_interval)

    print(f"시간 초과: {max_wait_time}초 이상 대기")
    return None

async def test_all_categories():
    """
    모든 카테고리 크롤링 테스트
    """
    results = {}
    total_start_time = time.time()

    for category in CATEGORIES:
        cat_name = category["name"]
        cat_url = category["url"]

        start_time = time.time()
        result = await test_category(cat_name, cat_url)
        end_time = time.time()

        results[cat_name] = {
            "success": result.get("success", False),
            "count": result.get("count", 0),
            "duration": end_time - start_time
        }

        # 서버 부하 방지를 위한 대기
        await asyncio.sleep(2)

    # 결과 요약 출력
    print("\n====== 테스트 결과 요약 ======")
    print(f"{'카테고리':<10} | {'상태':<10} | {'기사 수':<10} | {'소요 시간':<15}")
    print("-" * 50)

    for name, result in results.items():
        status = "성공" if result["success"] else "실패"
        count = result["count"]
        duration = f"{result['duration']:.2f}초"
        print(f"{name:<10} | {status:<10} | {count:<10} | {duration:<15}")

    print("-" * 50)
    print(f"총 소요 시간: {time.time() - total_start_time:.2f}초")

if __name__ == "__main__":
    if len(sys.argv) > 1:
        # 특정 카테고리만 테스트
        category_name = sys.argv[1]
        found = False

        for category in CATEGORIES:
            if category["name"] == category_name:
                found = True
                asyncio.run(test_category(category["name"], category["url"]))
                break

        if not found:
            print(f"오류: 카테고리 '{category_name}'를 찾을 수 없습니다.")
            print(f"사용 가능한 카테고리: {', '.join(c['name'] for c in CATEGORIES)}")
    else:
        # 모든 카테고리 테스트
        asyncio.run(test_all_categories())