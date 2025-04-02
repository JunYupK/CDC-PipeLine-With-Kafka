import asyncio
import json
import os
import sys
from datetime import datetime
from pathlib import Path

# 상위 디렉토리 추가 (services 모듈 임포트를 위함)
sys.path.append(str(Path(__file__).parent.parent))

# news_crawler와 content_crawler를 import합니다
from services.crawlers.news_crawler import meta_crawling
from services.crawlers.contents_crawler import get_article


async def test_single_category(category, url):
    """단일 카테고리 크롤링 테스트"""
    print(f"\n{'=' * 50}")
    print(f"테스트 시작: {category} 카테고리")
    print(f"URL: {url}")
    print(f"{'=' * 50}")

    start_time = datetime.now()
    
    # 메타데이터 크롤링
    print(f"\n1. {category} 카테고리 메타데이터 크롤링 시작...")
    articles = await meta_crawling(url)
    
    if not articles or len(articles) == 0:
        print(f"\n실패: {category} 카테고리 메타데이터 크롤링 실패")
        return {
            "success": False,
            "stage": "metadata",
            "article_count": 0,
            "duration_seconds": (datetime.now() - start_time).total_seconds()
        }
    
    print(f"\n성공: {len(articles)}개 기사 메타데이터 크롤링 완료")
    
    # 결과 저장
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    data_dir = Path('data')
    data_dir.mkdir(exist_ok=True)
    meta_file = data_dir / f'{category}_news_metadata_{timestamp}.json'
    
    with open(meta_file, 'w', encoding='utf-8') as f:
        json.dump(articles, f, ensure_ascii=False, indent=2)
    
    print(f"메타데이터 저장 완료: {meta_file}")
    
    # 내용 크롤링
    print(f"\n2. {category} 카테고리 기사 내용 크롤링 시작...")
    content_results = await get_article(timestamp, category)
    
    if not content_results or len(content_results) == 0:
        print(f"\n실패: {category} 카테고리 내용 크롤링 실패")
        return {
            "success": False,
            "stage": "content",
            "metadata_count": len(articles),
            "content_count": 0,
            "duration_seconds": (datetime.now() - start_time).total_seconds()
        }
    
    end_time = datetime.now()
    print(f"\n성공: {len(content_results)}개 기사 내용 크롤링 완료")
    print(f"소요 시간: {end_time - start_time}")
    
    # 첫 번째 기사 내용 샘플 출력
    if len(content_results) > 0:
        sample = content_results[0]
        print("\n첫 번째 기사 샘플:")
        print(f"제목: {sample.get('title', '제목 없음')}")
        print(f"링크: {sample.get('link', '링크 없음')}")
        
        # 내용은 길 수 있으므로 첫 100자만 출력
        content = sample.get('content', '내용 없음')
        print(f"내용 (처음 100자): {content[:100]}...")
    
    return {
        "success": True,
        "metadata_count": len(articles),
        "content_count": len(content_results),
        "duration_seconds": (datetime.now() - start_time).total_seconds()
    }


async def test_all_categories():
    """모든 카테고리 크롤링 테스트"""
    # 크롤링할 URL 및 카테고리 정의
    URLS = [
        ["정치", "https://news.naver.com/section/100"],
        ["경제", "https://news.naver.com/section/101"],
        ["사회", "https://news.naver.com/section/102"],
        ["생활문화", "https://news.naver.com/section/103"],
        ["세계", "https://news.naver.com/section/104"],
        ["IT과학", "https://news.naver.com/section/105"]
    ]

    print(f"\n모든 뉴스 카테고리 테스트 시작\n")

    # 폴더 생성 (데이터 저장용)
    data_dir = Path('data')
    data_dir.mkdir(exist_ok=True)

    results = {}
    for category, url in URLS:
        try:
            print(f"\n{'=' * 30} {category} 테스트 시작 {'=' * 30}")
            result = await test_single_category(category, url)
            results[category] = result

        except Exception as e:
            print(f"{category} 테스트 중 오류 발생: {str(e)}")
            results[category] = {
                "success": False,
                "error": str(e)
            }

        # 각 카테고리 사이에 딜레이 추가
        await asyncio.sleep(3)

    # 결과 저장
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    result_file = f"news_crawler_test_results_{timestamp}.json"

    with open(result_file, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    print(f"\n테스트 결과가 {result_file}에 저장되었습니다.")

    # 성공/실패 요약
    success_count = sum(1 for r in results.values() if r.get("success", False))
    print(f"\n결과 요약: {success_count}/{len(URLS)} 카테고리 성공")

    # 결과 표 형태로 출력
    print("\n테스트 결과 요약:")
    print("-" * 90)
    print(f"{'카테고리':<12} | {'상태':<8} | {'메타데이터 수':<12} | {'내용 수':<8} | {'소요 시간':>12}")
    print("-" * 90)

    for cat, res in results.items():
        status = "성공" if res.get("success", False) else "실패"
        meta_count = res.get("metadata_count", 0)
        content_count = res.get("content_count", 0)
        duration = res.get("duration_seconds", 0)
        print(f"{cat:<12} | {status:<8} | {meta_count:<12} | {content_count:<8} | {duration:>9.2f} 초")

    print("-" * 90)


async def main():
    """메인 테스트 함수"""
    # 명령행 인자 처리
    if len(sys.argv) > 1:
        # 단일 카테고리 테스트
        category = sys.argv[1]
        for cat_name, url in [
            ["정치", "https://news.naver.com/section/100"],
            ["경제", "https://news.naver.com/section/101"],
            ["사회", "https://news.naver.com/section/102"],
            ["생활문화", "https://news.naver.com/section/103"],
            ["세계", "https://news.naver.com/section/104"],
            ["IT과학", "https://news.naver.com/section/105"]
        ]:
            if cat_name == category:
                await test_single_category(category, url)
                break
        else:
            print(f"오류: 유효하지 않은 카테고리입니다: {category}")
            print("유효한 카테고리: 정치, 경제, 사회, 생활문화, 세계, IT과학")
    else:
        # 모든 카테고리 테스트
        await test_all_categories()


if __name__ == "__main__":
    asyncio.run(main())
