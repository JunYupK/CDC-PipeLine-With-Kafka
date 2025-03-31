import asyncio
import json
import os
import sys
from datetime import datetime
from pathlib import Path

# 상위 디렉토리 추가 (services 모듈 임포트를 위함)
sys.path.append(str(Path(__file__).parent.parent))

# sports_crawler_nojs.py가 services/crawlers에 있다고 가정
from services.crawlers.sports_crawler import crawl_sports_news, CATEGORY_MAPPING


async def test_single_category(category, pages=3):
    """단일 카테고리 크롤링 테스트"""
    print(f"\n{'=' * 50}")
    print(f"테스트 시작: {category} 카테고리 ({pages} 페이지)")
    print(f"{'=' * 50}")

    start_time = datetime.now()
    result = await crawl_sports_news(category, pages)
    end_time = datetime.now()

    if result:
        print(f"\n성공: {len(result)}개 기사 크롤링 완료")
        print(f"소요 시간: {end_time - start_time}")

        # 첫 번째 기사 내용 샘플 출력
        if len(result) > 0:
            sample = result[0]
            print("\n첫 번째 기사 샘플:")
            print(f"제목: {sample.get('title', '제목 없음')}")
            print(f"링크: {sample.get('link', '링크 없음')}")
            print(f"언론사: {sample.get('press', '언론사 정보 없음')}")
            print(f"발행일: {sample.get('published_date', '발행일 정보 없음')}")

            # 내용은 길 수 있으므로 첫 100자만 출력
            content = sample.get('content', '내용 없음')
            print(f"내용 (처음 100자): {content[:100]}...")

        return {
            "success": True,
            "article_count": len(result),
            "duration_seconds": (end_time - start_time).total_seconds()
        }
    else:
        print(f"\n실패: {category} 카테고리 크롤링 실패")
        return {
            "success": False,
            "article_count": 0,
            "duration_seconds": (end_time - start_time).total_seconds()
        }


async def test_all_categories(pages=3):
    """모든 카테고리 크롤링 테스트"""
    categories = list(CATEGORY_MAPPING.keys())  # 한글 카테고리명 사용

    print(f"\n모든 스포츠 카테고리 테스트 시작 (각 {pages} 페이지)\n")

    # 폴더 생성 (데이터 저장용)
    data_dir = Path('data')
    data_dir.mkdir(exist_ok=True)

    results = {}
    for category in categories:
        try:
            print(f"\n{'=' * 30} {category} 테스트 시작 {'=' * 30}")
            result = await test_single_category(category, pages)
            results[category] = result

        except Exception as e:
            print(f"{category} 테스트 중 오류 발생: {str(e)}")
            results[category] = {
                "success": False,
                "error": str(e)
            }

        # 각 카테고리 사이에 딜레이 추가
        await asyncio.sleep(2)

    # 결과 저장
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    result_file = f"sports_crawler_test_results_{timestamp}.json"

    with open(result_file, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    print(f"\n테스트 결과가 {result_file}에 저장되었습니다.")

    # 성공/실패 요약
    success_count = sum(1 for r in results.values() if r.get("success", False))
    print(f"\n결과 요약: {success_count}/{len(categories)} 카테고리 성공")

    # 결과 표 형태로 출력
    print("\n테스트 결과 요약:")
    print("-" * 80)
    print(f"{'카테고리':<12} | {'상태':<8} | {'기사 수':<8} | {'소요 시간':>12}")
    print("-" * 80)

    for cat, res in results.items():
        status = "성공" if res.get("success", False) else "실패"
        count = res.get("article_count", 0)
        duration = res.get("duration_seconds", 0)
        print(f"{cat:<12} | {status:<8} | {count:<8} | {duration:>9.2f} 초")

    print("-" * 80)


async def main():
    """메인 테스트 함수"""
    # 명령행 인자 처리
    if len(sys.argv) > 1:
        # 단일 카테고리 테스트
        category = sys.argv[1]
        pages = int(sys.argv[2]) if len(sys.argv) > 2 else 3
        await test_single_category(category, pages)
    else:
        # 모든 카테고리 테스트
        pages = int(sys.argv[1]) if len(sys.argv) > 1 else 3
        await test_all_categories(pages)


if __name__ == "__main__":
    asyncio.run(main())