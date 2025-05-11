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
        if not hasattr(self, 'initialized'):
            # 크롤링할 URL 및 카테고리 정의
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
            self.error_count: Dict[str, int] = {category: 0 for category, _ in self.URLS}
            self.current_task: Optional[asyncio.Task[Any]] = None  # 명시적 Any 타입 파라미터
            # Prometheus 메트릭 초기화
            self._init_metrics()

            self.initialized = True
            # 기사 성공 처리 메트릭 (추가)
            self.ARTICLES_SUCCESS = Counter(
                'crawler_articles_success_total',
                'Number of articles successfully processed',
                ['category'],
                registry=self._registry
            )

            # 기사 실패 처리 메트릭 (추가)
            self.ARTICLES_FAILED = Counter(
                'crawler_articles_failed_total',
                'Number of articles that failed during processing',
                ['category'],
                registry=self._registry
            )
            # 작업 시간 메트릭 (기존)
            self.CRAWL_TIME = Histogram(
                'crawl_time',
                'Time taken to crawl each category',
                ['category'],
                buckets=[0.1, 0.5, 1, 2, 5, 10, 30, 60, 120, 300, 600],
                registry=self._registry
            )

            # 스테이지별 작업 시간 메트릭 (추가)
            self.CRAWL_DURATION = Histogram(
                'crawler_duration_seconds',
                'Time taken to crawl each category by stage',
                ['category', 'stage'],  # stage: 'metadata', 'content', 'total'
                buckets=[0.1, 0.5, 1, 2, 5, 10, 30, 60, 120, 300, 600],
                registry=self._registry
            )

    def _init_metrics(self) -> None:
        """Prometheus 메트릭 초기화"""
        # 시스템 리소스 메트릭
        self.MEMORY_USAGE = Gauge(
            'crawler_memory_usage_bytes',
            'Memory usage of the crawler process in bytes',
            registry=self._registry
        )

        self.CPU_USAGE = Gauge(
            'crawler_cpu_usage_percent',
            'CPU usage of the crawler process as percentage',
            registry=self._registry
        )

        # 크롤링 상태 메트릭
        self.CRAWL_STATUS = Gauge(
            'crawl_status',  # 기존 이름 유지
            'Crawler status for different categories',
            ['category'],
            registry=self._registry
        )

        # 마지막 실행 시간 메트릭 (이게 누락되어 있었음)
        self.LAST_EXECUTION_TIME = Gauge(
            'last_execution_time',  # 기존 이름 유지
            'Last execution time for each category',
            ['category'],
            registry=self._registry
        )

        # 기사 처리 메트릭 (카테고리별)
        self.ARTICLES_PROCESSED = Counter(
            'articles_processed',  # 기존 이름 유지
            'Total number of articles processed per category',
            ['category'],
            registry=self._registry
        )

        # 성공/실패 메트릭
        self.CRAWL_SUCCESS = Counter(
            'crawl_success',  # 기존 이름 유지
            'Number of successful crawls per category',
            ['category'],
            registry=self._registry
        )

        self.CRAWL_FAILURE = Counter(  # 이게 누락되어 있었음
            'crawl_failure',  # 기존 이름 유지
            'Number of failed crawls per category',
            ['category'],
            registry=self._registry
        )

        # DB 작업 시간 메트릭
        self.DB_OPERATION_TIME = Histogram(
            'db_operation_time',  # 기존 이름 유지
            'Time taken for database operations',
            ['operation_type', 'category'],
            buckets=[0.01, 0.05, 0.1, 0.5, 1, 2, 5, 10],
            registry=self._registry
        )

        # 크롤링 속도 (Gauge로 변경하여 10초마다 업데이트)
        self.CRAWL_RATE = Gauge(
            'crawler_articles_per_minute',
            'Number of articles processed per minute',
            ['category'],
            registry=self._registry
        )

        # 각 카테고리에 대해 초기값 설정
        for category, _ in self.URLS:
            self.CRAWL_STATUS.labels(category=category).set(0)
            self.LAST_EXECUTION_TIME.labels(category=category).set(0)
            self.CRAWL_RATE.labels(category=category).set(0)
            # 카운터는 초기화할 필요 없음


    def update_system_metrics(self):
        """10초마다 시스템 메트릭 업데이트"""
        process = psutil.Process(os.getpid())

        # 메모리 사용량 업데이트 (바이트 단위)
        memory_info = process.memory_info()
        self.MEMORY_USAGE.set(memory_info.rss)  # 실제 메모리 사용량

        # CPU 사용량 업데이트 (퍼센트)
        self.CPU_USAGE.set(process.cpu_percent(interval=0.1))

        # 크롤링 속도 계산 및 업데이트
        for category, _ in self.URLS:
            # 마지막 업데이트 이후 처리된 기사 수 계산
            current_time = datetime.now()
            category_key = f"{category}_last_update"
            category_count_key = f"{category}_last_count"

            if not hasattr(self, category_key):
                setattr(self, category_key, current_time)
                setattr(self, category_count_key, float(self.ARTICLES_PROCESSED.labels(category=category)._value.get() or 0))
                continue

            last_update = getattr(self, category_key)
            last_count = getattr(self, category_count_key)
            current_count = float(self.ARTICLES_PROCESSED.labels(category=category)._value.get() or 0)

            # 경과 시간 (분)
            elapsed_minutes = (current_time - last_update).total_seconds() / 60.0

            if elapsed_minutes > 0:
                # 분당 처리 기사 수 계산
                articles_per_minute = (current_count - last_count) / elapsed_minutes
                self.CRAWL_RATE.labels(category=category).set(articles_per_minute)

                # 값 업데이트
                setattr(self, category_key, current_time)
                setattr(self, category_count_key, current_count)

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

            urls = [url for url in self.URLS if target_category is None or url[0] == target_category]

            for category, url in urls:
                try:
                    print(f"\n크롤링 시작: {category} - {timestamp}")
                    self.CRAWL_STATUS.labels(category=category).set(1)

                    with self.CRAWL_DURATION.labels(category=category, stage="total").time():
                        # 1. 메타데이터 크롤링
                        metadata_start = time.time()
                        with self.CRAWL_DURATION.labels(category=category, stage="metadata").time():
                            articles = await crawl_news(url)
                        metadata_duration = time.time() - metadata_start

                        # API 요청 메트릭 업데이트
                        self.API_REQUESTS.labels(endpoint="news_api", method="GET").inc()

                        if articles:
                            # 메타데이터 저장
                            news_file = data_dir / f'{category}_naver_it_news_{timestamp}.json'
                            with open(news_file, 'w', encoding='utf-8') as f:
                                json.dump(articles, f, ensure_ascii=False, indent=2)

                            # 메트릭 업데이트: 발견된 기사 수
                            self.ARTICLES_FOUND.labels(category=category).inc(len(articles))

                            # 2. 상세 내용 크롤링
                            content_start = time.time()
                            with self.CRAWL_DURATION.labels(category=category, stage="content").time():
                                result = await crawl_content(timestamp, category)
                            content_duration = time.time() - content_start

                            # 처리 결과에 따라 메트릭 업데이트
                            if result:
                                articles_to_save = []
                                success_count = 0
                                failed_count = 0

                                for article in result:
                                    # 성공 여부 확인 (내용이 있는지)
                                    if article.get("content", "").strip():
                                        success_count += 1
                                    else:
                                        failed_count += 1

                                    articles_to_save.append({
                                        "title": article["title"],
                                        "content": article["content"],
                                        "link": article["link"],
                                        "stored_date": timestamp[:8],
                                        "category": category
                                    })

                                # 메트릭 업데이트
                                self.ARTICLES_PROCESSED.labels(category=category).inc(len(result))
                                self.ARTICLES_SUCCESS.labels(category=category).inc(success_count)
                                self.ARTICLES_FAILED.labels(category=category).inc(failed_count)

                                # DB 저장
                                db_start = time.time()
                                with self.DB_OPERATION_TIME.labels(
                                        operation_type='insert',
                                        category=category
                                ).time():
                                    try:
                                        save_to_db_with_retry(articles_to_save)
                                        print(f"{category} 카테고리 {len(articles_to_save)}개 기사 DB 저장 완료")
                                    except Exception as e:
                                        print(f"DB 저장 실패: {str(e)}")
                                        raise
                                db_duration = time.time() - db_start

                                # 처리 시간 로깅
                                print(f"[성능] {category}: 메타데이터 {metadata_duration:.2f}초, "
                                      f"내용 {content_duration:.2f}초, DB {db_duration:.2f}초")

                    # 카테고리 처리 성공
                    self.CRAWL_SUCCESS.labels(category=category).inc()
                    self.LAST_EXECUTION_TIME.labels(category=category).set_to_current_time()
                    self.error_count[category] = 0

                except Exception as e:
                    self.CRAWL_FAILURE.labels(category=category).inc()
                    self.error_count[category] += 1
                    print(f"크롤링 중 오류 발생 ({category}): {str(e)}")

                finally:
                    self.CRAWL_STATUS.labels(category=category).set(0)
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
        return {
            category: float(self.LAST_EXECUTION_TIME.labels(category=category)._value.get() or 0)
            for category, _ in self.URLS
        }

    def get_articles_processed(self) -> Dict[str, int]:
        """
        각 카테고리별 처리된 기사 수 조회
        
        Returns:
            Dict[str, int]: 카테고리별 처리된 기사 수
        """
        return {
            category: int(self.ARTICLES_PROCESSED.labels(category=category)._value.get() or 0)
            for category, _ in self.URLS
        }

    def get_success_rate(self) -> Dict[str, float]:
        """
        각 카테고리별 기사 처리 성공률 계산

        Returns:
            Dict[str, float]: 카테고리별 기사 처리 성공률 (0.0 ~ 1.0)
        """
        rates = {}
        for category, _ in self.URLS:
            try:
                success = float(self.ARTICLES_SUCCESS.labels(category=category)._value.get() or 0)
                total = float(
                    (self.ARTICLES_SUCCESS.labels(category=category)._value.get() or 0) +
                    (self.ARTICLES_FAILED.labels(category=category)._value.get() or 0)
                )
                rates[category] = success / total if total > 0 else 1.0
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