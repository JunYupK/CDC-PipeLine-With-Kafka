import asyncio
import json
import os
from datetime import datetime
import schedule
import time
from pathlib import Path
from crwaling_news import main as crawl_news
from crawling_from_url import main as crawl_content


async def crawling_job():
    # 시작 시간으로 파일명 생성
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    # ../data 디렉토리 경로 설정 및 생성
    data_dir = Path(os.path.dirname(os.path.abspath(__file__))).parent / 'data'
    data_dir.mkdir(parents=True, exist_ok=True)

    try:
        print(f"\n크롤링 시작: {timestamp}")

        # 1단계: 뉴스 목록 크롤링
        articles = await crawl_news()

        if articles:
            # 타임스탬프가 포함된 파일명으로 직접 저장
            news_file = data_dir / f'naver_it_news_{timestamp}.json'
            with open(news_file, 'w', encoding='utf-8') as f:
                json.dump(articles, f, ensure_ascii=False, indent=2)
            print(f"뉴스 목록 저장 완료: {news_file}")

            # 2단계: 기사 내용 크롤링
            await crawl_content(timestamp)

    except Exception as e:
        print(f"크롤링 중 오류 발생: {str(e)}")


def run_crawling():
    asyncio.run(crawling_job())


def main():
    print("크롤링 스케줄러 시작...")

    # # 2시간마다 실행
    # schedule.every(3).hours.do(run_crawling)
    # 5분마다 실행
    schedule.every(5).minutes.do(run_crawling)

    # 시작하자마자 첫 실행
    run_crawling()

    # 스케줄 유지
    while True:
        schedule.run_pending()
        time.sleep(60)  # 1분마다 스케줄 체크


if __name__ == "__main__":
    main()