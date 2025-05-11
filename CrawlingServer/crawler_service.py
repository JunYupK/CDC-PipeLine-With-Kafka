import time
from datetime import datetime
import asyncio
import json
import os
from typing import Optional, Dict, Any, List, Tuple, cast, ClassVar
import psutil  # 시스템 메트릭용

from prometheus_client import Counter, Histogram, Gauge, Summary, CollectorRegistry
from pathlib import Path

from services.db import save_to_db_with_retry
from services.crawlers import crawl_news, crawl_content


class CrawlerService:
    """
    뉴스 크롤링 서비스를 관리하는 클래스
    
    크롤링 작업 실행, 상태 관리, 메트릭 수집 기능을 제공합니다.
    """
    _instance: ClassVar[Optional['CrawlerService']] = None
    _registry: ClassVar[CollectorRegistry] = CollectorRegistry()

    def __new__(cls) -> 'CrawlerService':
        """싱글톤 패턴 구현"""
        if cls._instance is None:
            cls._instance = super(CrawlerService, cls).__new__(cls)
        return cls._instance

    def __init__(self) -> None:
        """인스턴스 초기화"""
        print("init!")
        # 이미 초기화되었는지 확인
        if hasattr(self, 'initialized') and self.initialized:
            # 이미 초기화된 경우 더 이상 진행하지 않음
            return

        # 크롤링할 URL 및 카테고리 정의 - 간단하게 유지
        self.URLS: List[List[str]] = [
            ["정치", "https://news.naver.com/section/100"],
            ["경제", "https://news.naver.com/section/101"],
            ["사회", "https://news.naver.com/section/102"],
            ["생활문화", "https://news.naver.com/section/103"],
            ["세계", "https://news.naver.com/section/104"],
            ["IT과학", "https://news.naver.com/section/105"]
        ]

        # 크롤링 상태 관리 변수
        self.is_crawling: bool = False
        self.current_category: Optional[str] = None
        self.crawl_start_time: Optional[datetime] = None
        self.error_count: Dict[str, int] = {}
        self.current_task: Optional[asyncio.Task[Any]] = None

        # Prometheus 메트릭 초기화
        self._init_metrics()

        # 에러 카운트 초기화 (메트릭 초기화 후)
        self.error_count = {category: 0 for category, _ in self.URLS}

        # 초기화 완료 표시
        self.initialized = True

    def _init_metrics(self) -> None:
        """Prometheus 메트릭 초기화"""
        # 메트릭 이름에 접두어 추가하여 충돌 방지
        prefix = "news_crawler_"

        # 1. 모든 메트릭에서 레이블 이름을 일관되게 cat_id로 사용
        self.CRAWL_STATUS = Gauge(
            f'{prefix}crawl_status',
            'Crawler status for different categories',
            ['cat_id'],  # 간단한 레이블명 사용
            registry=self._registry
        )

        self.LAST_EXECUTION_TIME = Gauge(
            f'{prefix}last_execution_time',
            'Last execution time for each category',
            ['cat_id'],
            registry=self._registry
        )

        self.ARTICLES_PROCESSED = Counter(
            f'{prefix}articles_processed',
            'Number of articles processed per category',
            ['cat_id'],
            registry=self._registry
        )

        self.CRAWL_SUCCESS = Counter(
            f'{prefix}crawl_success',
            'Number of successful crawls per category',
            ['cat_id'],
            registry=self._registry
        )

        self.CRAWL_FAILURE = Counter(
            f'{prefix}crawl_failure',
            'Number of failed crawls per category',
            ['cat_id'],
            registry=self._registry
        )

        self.CRAWL_TIME = Histogram(
            f'{prefix}crawl_time',
            'Time taken to crawl each category',
            ['cat_id'],
            registry=self._registry
        )

        self.DB_OPERATION_TIME = Histogram(
            f'{prefix}db_operation_time',
            'Time taken for database operations',
            ['op_type', 'cat_id'],  # 간단한 레이블명으로 변경
            registry=self._registry
        )

        self.API_REQUESTS = Counter(
            f'{prefix}api_requests',
            'Number of API requests per category',
            ['cat_id', 'endpoint'],
            registry=self._registry
        )

        # 시스템 메트릭 (레이블 없음)
        self.MEMORY_USAGE = Gauge(
            f'{prefix}memory_usage',
            'Memory usage in bytes',
            registry=self._registry
        )

        self.CPU_USAGE = Gauge(
            f'{prefix}cpu_usage',
            'CPU usage percentage',
            registry=self._registry
        )

        self.OPEN_FILES = Gauge(
            f'{prefix}open_files',
            'Number of open files',
            registry=self._registry
        )
        # _init_metrics 메서드에 추가
        self.GC_OBJECTS_COLLECTED = Counter(
            f'{prefix}gc_objects_collected',
            'Number of objects collected by the Python garbage collector',
            ['generation'],
            registry=self._registry
        )


        # 카테고리 매핑 추가 - 매우 짧은 ID 사용
        self.category_map = {
            "정치": "pol",
            "경제": "eco",
            "사회": "soc",
            "생활문화": "cul",
            "세계": "wld",
            "IT과학": "its"
        }

        # 이 간단한 카테고리 ID로 모든 메트릭 초기화
        categories = ["pol", "eco", "soc", "cul", "wld", "its"]

        # 모든 카테고리에 대한 초기값 설정
        for cat_id in categories:
            self.CRAWL_STATUS.labels(cat_id=cat_id).set(0)
            self.LAST_EXECUTION_TIME.labels(cat_id=cat_id).set(0)
            self.ARTICLES_PROCESSED.labels(cat_id=cat_id).inc(0)
            self.CRAWL_SUCCESS.labels(cat_id=cat_id).inc(0)
            self.CRAWL_FAILURE.labels(cat_id=cat_id).inc(0)
            self.API_REQUESTS.labels(cat_id=cat_id, endpoint='meta').inc(0)
            self.API_REQUESTS.labels(cat_id=cat_id, endpoint='content').inc(0)


    def update_system_metrics(self) -> None:
        """시스템 메트릭 및 크롤링 메트릭 업데이트"""
        try:
            import psutil
            process = psutil.Process()

            # 메모리 사용량
            memory_info = process.memory_info()
            self.MEMORY_USAGE.set(memory_info.rss)  # 실제 메모리 사용량

            # CPU 사용량
            self.CPU_USAGE.set(process.cpu_percent(interval=0.1))

            # 오픈 파일 수
            self.OPEN_FILES.set(len(process.open_files()))
            # update_system_metrics 메서드에서 사용
            import gc
            # GC 통계 업데이트
            for i, count in enumerate(gc.get_count()):
                self.GC_OBJECTS_COLLECTED.labels(generation=str(i)).inc(count)

            # 카테고리별 기사 수 추적
            for category, _ in self.URLS:
                category_count_key = f"{category}_last_count"
                cat_id = self.category_map.get(category, "unknown")

                # 처음 실행 시 속성 초기화
                if not hasattr(self, category_count_key):
                    setattr(self, category_count_key, 0.0)

                # 현재 기사 수 읽기 - cat_id 레이블 사용
                current_count = float(self.ARTICLES_PROCESSED.labels(cat_id=cat_id)._value.get() or 0)

                # 이전 값과 비교하여 변화량 계산
                try:
                    last_count = getattr(self, category_count_key)
                    if current_count > last_count:
                        # 새로운 기사가 추가된 경우
                        delta = current_count - last_count
                        print(f"{category} 카테고리에 {int(delta)}개 기사 추가됨")
                except (AttributeError, TypeError) as e:
                    # 첫 실행 시 오류 발생 가능성 무시
                    pass

                # 현재 값 저장
                setattr(self, category_count_key, current_count)

        except Exception as e:
            print(f"시스템 메트릭 업데이트 중 오류 발생: {str(e)}")

    async def crawling_job(self, target_category: Optional[str] = None) -> None:
        """
        주어진 카테고리 또는 모든 카테고리의 뉴스를 크롤링

        Args:
            target_category: 크롤링할 특정 카테고리. None인 경우 모든 카테고리 크롤링

        Raises:
            RuntimeError: 이미 크롤링이 진행 중인 경우
        """
        if self.is_crawling:
            raise RuntimeError("Crawling is already in progress")

        self.is_crawling = True
        self.current_category = target_category
        self.crawl_start_time = datetime.now()
        timestamp = self.crawl_start_time.strftime("%Y%m%d_%H%M%S")
        self.current_task = asyncio.current_task()

        try:
            data_dir = Path(os.path.dirname(os.path.abspath(__file__))).parent / 'data'
            data_dir.mkdir(parents=True, exist_ok=True)

            # 대상 URL 필터링
            urls = [url for url in self.URLS if target_category is None or url[0] == target_category]

            for category, url in urls:
                try:
                    # 카테고리 ID 가져오기
                    cat_id = self.category_map.get(category, "unknown")

                    print(f"\n크롤링 시작: {category} - {timestamp}")
                    self.CRAWL_STATUS.labels(cat_id=cat_id).set(1)  # cat_id 레이블 사용

                    # 크롤링 시간 측정 - cat_id 레이블 사용
                    with self.CRAWL_TIME.labels(cat_id=cat_id).time():
                        articles = await crawl_news(url)

                        if articles:
                            # 메타데이터 저장
                            news_file = data_dir / f'{category}_naver_it_news_{timestamp}.json'
                            with open(news_file, 'w', encoding='utf-8') as f:
                                json.dump(articles, f, ensure_ascii=False, indent=2)

                            # 상세 내용 크롤링
                            result = await crawl_content(timestamp, category)
                            if result:
                                articles_to_save = [{
                                    "title": article["title"],
                                    "content": article["content"],
                                    "link": article["link"],
                                    "stored_date": timestamp[:8],
                                    "category": category
                                } for article in result]

                                # DB 저장 - cat_id와 operation_type 레이블 사용
                                with self.DB_OPERATION_TIME.labels(
                                        op_type='insert',  # 변경된 레이블 이름
                                        cat_id=cat_id      # 변경된 레이블 이름
                                ).time():
                                    try:
                                        save_to_db_with_retry(articles_to_save)
                                        # 레이블 이름 업데이트
                                        self.ARTICLES_PROCESSED.labels(cat_id=cat_id).inc(len(articles_to_save))
                                        print(f"{category} 카테고리 {len(articles_to_save)}개 기사 DB 저장 완료")
                                    except Exception as e:
                                        print(f"DB 저장 실패: {str(e)}")
                                        raise

                    # 성공 카운터 증가 - cat_id 레이블 사용
                    self.CRAWL_SUCCESS.labels(cat_id=cat_id).inc()
                    self.LAST_EXECUTION_TIME.labels(cat_id=cat_id).set_to_current_time()
                    self.error_count[category] = 0

                except Exception as e:
                    # 실패 카운터 증가 - cat_id 레이블 사용
                    cat_id = self.category_map.get(category, "unknown")
                    self.CRAWL_FAILURE.labels(cat_id=cat_id).inc()
                    self.error_count[category] += 1
                    print(f"크롤링 중 오류 발생 ({category}): {str(e)}")

                finally:
                    # 상태 초기화 - cat_id 레이블 사용
                    cat_id = self.category_map.get(category, "unknown")
                    self.CRAWL_STATUS.labels(cat_id=cat_id).set(0)

        except asyncio.CancelledError:
            print("크롤링 작업이 취소되었습니다.")
            raise
        finally:
            self.is_crawling = False
            self.current_category = None
            self.crawl_start_time = None
            self.current_task = None

    def get_crawling_status(self) -> Dict[str, Any]:
        """
        현재 크롤링 상태 조회
        
        Returns:
            Dict[str, Any]: 현재 크롤링 상태 정보
        """
        return {
            "is_crawling": self.is_crawling,
            "current_category": self.current_category,
            "crawl_start_time": self.crawl_start_time.isoformat() if self.crawl_start_time else None,
            "error_counts": self.error_count
        }

    def get_last_execution(self) -> Dict[str, float]:
        """
        각 카테고리별 마지막 실행 시간 조회

        Returns:
            Dict[str, float]: 카테고리별 마지막 실행 시간 (유닉스 타임스탬프)
        """
        result = {}
        for category, _ in self.URLS:
            # 카테고리 ID 가져오기
            cat_id = self.category_map.get(category, "unknown")
            result[category] = float(self.LAST_EXECUTION_TIME.labels(cat_id=cat_id)._value.get() or 0)
        return result

    def get_articles_processed(self) -> Dict[str, int]:
        """
        각 카테고리별 처리된 기사 수 조회

        Returns:
            Dict[str, int]: 카테고리별 처리된 기사 수
        """
        result = {}
        for category, _ in self.URLS:
            # 카테고리 ID 가져오기
            cat_id = self.category_map.get(category, "unknown")
            result[category] = int(self.ARTICLES_PROCESSED.labels(cat_id=cat_id)._value.get() or 0)
        return result

    def get_success_rate(self) -> Dict[str, float]:
        """
        각 카테고리별 크롤링 성공률 계산

        Returns:
            Dict[str, float]: 카테고리별 크롤링 성공률 (0.0 ~ 1.0)
        """
        rates = {}
        for category, _ in self.URLS:
            try:
                # 카테고리 ID 가져오기
                cat_id = self.category_map.get(category, "unknown")
                success = float(self.CRAWL_SUCCESS.labels(cat_id=cat_id)._value.get() or 0)
                processed = float(self.ARTICLES_PROCESSED.labels(cat_id=cat_id)._value.get() or 1)
                rates[category] = success / processed if processed > 0 else 0.0
            except (TypeError, ZeroDivisionError):
                rates[category] = 0.0
        return rates

    def stop_crawling(self) -> Dict[str, str]:
        """
        현재 실행 중인 크롤링 작업 중지
        
        Returns:
            Dict[str, str]: 중지 요청 결과
            
        Raises:
            RuntimeError: 실행 중인 크롤링 작업이 없는 경우
        """
        if not self.is_crawling or not self.current_task:
            raise RuntimeError("No crawling job is currently running")

        try:
            # 실행 중인 태스크 취소
            if not self.current_task.done():
                self.current_task.cancel()

            return {"message": "Crawling job stop requested"}
        except Exception as e:
            raise RuntimeError(f"Failed to stop crawling: {str(e)}")

    def get_metrics(self) -> Dict[str, Any]:
        """
        모든 Prometheus 메트릭 수집

        Returns:
            Dict[str, Any]: 수집된 메트릭 데이터
        """
        return {
            "crawl_time": self.CRAWL_TIME.collect(),
            "success": self.CRAWL_SUCCESS.collect(),
            "failure": self.CRAWL_FAILURE.collect(),
            "processed": self.ARTICLES_PROCESSED.collect(),
            "db_operation_time": self.DB_OPERATION_TIME.collect(),
            "status": self.CRAWL_STATUS.collect()
        }

    @classmethod
    def get_registry(cls) -> CollectorRegistry:
        """
        Prometheus 레지스트리 반환
        
        Returns:
            CollectorRegistry: Prometheus 메트릭 레지스트리
        """
        return cls._registry