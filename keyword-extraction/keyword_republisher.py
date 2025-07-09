"""
Kafka 키워드 재발행 모듈
키워드 추출 후 알림 처리를 위해 Kafka로 재발행
"""
import json
import logging
from typing import List, Dict, Optional
from datetime import datetime
from confluent_kafka import Producer, KafkaError
from dataclasses import dataclass, asdict

logger = logging.getLogger(__name__)

@dataclass
class KeywordEvent:
    """키워드 이벤트 데이터 구조"""
    article_id: int
    title: str
    content: str
    category: str
    keywords: List[str]
    extraction_method: str
    confidence_scores: Dict[str, float]
    timestamp: str
    metadata: Dict

@dataclass
class ProcessedKeywordEvent:
    """처리된 키워드 이벤트"""
    article_id: int
    title: str
    category: str
    keywords: List[Dict[str, any]]  # keyword, score, category 포함
    trends: Dict[str, float]  # trending scores
    breaking_indicators: List[str]  # 브레이킹 뉴스 지표
    sentiment: Dict[str, float]
    processing_timestamp: str
    original_timestamp: str

class KafkaKeywordRepublisher:
    """키워드 Kafka 재발행 클래스"""
    
    def __init__(self, bootstrap_servers: str, keyword_topic: str = "processed-keywords"):
        self.bootstrap_servers = bootstrap_servers
        self.keyword_topic = keyword_topic
        
        # Producer 설정
        self.producer_config = {
            'bootstrap.servers': bootstrap_servers,
            'client.id': 'keyword-republisher',
            'acks': 'all',  # 모든 복제본에서 확인
            'retries': 3,
            'compression.type': 'snappy',
            'batch.size': 16384,
            'linger.ms': 10,  # 배치를 위한 대기시간
            'buffer.memory': 33554432
        }
        
        self.producer = Producer(self.producer_config)
        self.stats = {
            'published_count': 0,
            'failed_count': 0,
            'last_publish_time': None
        }
        
        logger.info(f"Kafka Republisher 초기화: {keyword_topic}")

    def _delivery_callback(self, err, msg):
        """메시지 전송 콜백"""
        if err is not None:
            logger.error(f"키워드 발행 실패: {err}")
            self.stats['failed_count'] += 1
        else:
            logger.debug(f"키워드 발행 성공: {msg.topic()}-{msg.partition()}-{msg.offset()}")
            self.stats['published_count'] += 1
            self.stats['last_publish_time'] = datetime.now().isoformat()

    async def publish_keywords(self, 
                             article_id: int,
                             title: str,
                             content: str,
                             category: str,
                             keywords: List[str],
                             extraction_method: str = "hybrid",
                             confidence_scores: Optional[Dict[str, float]] = None,
                             metadata: Optional[Dict] = None) -> bool:
        """키워드를 Kafka로 발행"""
        try:
            # 키워드 이벤트 생성
            keyword_event = KeywordEvent(
                article_id=article_id,
                title=title,
                content=content[:500],  # 내용 길이 제한
                category=category,
                keywords=keywords,
                extraction_method=extraction_method,
                confidence_scores=confidence_scores or {},
                timestamp=datetime.now().isoformat(),
                metadata=metadata or {}
            )
            
            # JSON 직렬화
            message_value = json.dumps(asdict(keyword_event), ensure_ascii=False)
            message_key = f"article_{article_id}"
            
            # Kafka로 발행
            self.producer.produce(
                topic=self.keyword_topic,
                key=message_key,
                value=message_value,
                callback=self._delivery_callback
            )
            
            # 즉시 전송 (비동기)
            self.producer.poll(0)
            
            logger.info(f"키워드 이벤트 발행: article_{article_id}, keywords={len(keywords)}")
            return True
            
        except Exception as e:
            logger.error(f"키워드 발행 실패: {e}")
            self.stats['failed_count'] += 1
            return False

    def flush(self, timeout: float = 10.0):
        """대기 중인 모든 메시지 전송 완료 대기"""
        try:
            self.producer.flush(timeout)
            logger.info("Kafka 메시지 플러시 완료")
        except Exception as e:
            logger.error(f"Kafka 플러시 실패: {e}")

    def get_stats(self) -> Dict:
        """발행 통계 조회"""
        return self.stats.copy()

    def close(self):
        """Producer 종료"""
        try:
            self.flush()
            logger.info("Kafka Republisher 종료")
        except Exception as e:
            logger.error(f"Republisher 종료 오류: {e}")

class BreakingNewsDetector:
    """브레이킹 뉴스 감지기"""
    
    BREAKING_KEYWORDS = [
        "속보", "긴급", "사망", "화재", "사고", "폭발", "지진", 
        "테러", "총격", "붕괴", "침몰", "추락", "실종"
    ]
    
    URGENT_CATEGORIES = ["사회", "사건사고", "정치", "국제"]
    
    @classmethod
    def detect_breaking_news(cls, title: str, content: str, 
                           category: str, keywords: List[str]) -> List[str]:
        """브레이킹 뉴스 지표 감지"""
        indicators = []
        
        # 제목에서 브레이킹 키워드 확인
        title_lower = title.lower()
        for keyword in cls.BREAKING_KEYWORDS:
            if keyword in title_lower:
                indicators.append(f"breaking_keyword_{keyword}")
        
        # 긴급 카테고리 확인
        if category in cls.URGENT_CATEGORIES:
            indicators.append(f"urgent_category_{category}")
        
        # 추출된 키워드에서 브레이킹 관련 확인
        breaking_in_keywords = [k for k in keywords if any(bk in k for bk in cls.BREAKING_KEYWORDS)]
        if breaking_in_keywords:
            indicators.append(f"breaking_in_keywords_{len(breaking_in_keywords)}")
        
        return indicators

class EnhancedKeywordProcessor:
    """향상된 키워드 처리기"""
    
    def __init__(self, republisher: KafkaKeywordRepublisher):
        self.republisher = republisher
        self.breaking_detector = BreakingNewsDetector()
        
    async def process_and_republish(self,
                                  article_id: int,
                                  title: str,
                                  content: str,
                                  category: str,
                                  raw_keywords: List[str],
                                  confidence_scores: Optional[Dict[str, float]] = None) -> bool:
        """키워드 처리 및 재발행"""
        try:
            # 브레이킹 뉴스 지표 감지
            breaking_indicators = self.breaking_detector.detect_breaking_news(
                title, content, category, raw_keywords
            )
            
            # 키워드 처리 (점수와 함께)
            processed_keywords = []
            for keyword in raw_keywords:
                processed_keywords.append({
                    "keyword": keyword,
                    "score": confidence_scores.get(keyword, 0.5) if confidence_scores else 0.5,
                    "category": self._classify_keyword(keyword)
                })
            
            # 트렌드 점수 계산 (임시)
            trends = self._calculate_trend_scores(raw_keywords)
            
            # 감정 분석 (기본값)
            sentiment = {"positive": 0.3, "negative": 0.4, "neutral": 0.3}
            
            # 처리된 이벤트 생성
            processed_event = ProcessedKeywordEvent(
                article_id=article_id,
                title=title,
                category=category,
                keywords=processed_keywords,
                trends=trends,
                breaking_indicators=breaking_indicators,
                sentiment=sentiment,
                processing_timestamp=datetime.now().isoformat(),
                original_timestamp=datetime.now().isoformat()
            )
            
            # 재발행
            return await self.republisher.publish_keywords(
                article_id=article_id,
                title=title,
                content=content,
                category=category,
                keywords=raw_keywords,
                confidence_scores=confidence_scores,
                metadata={
                    "processed_event": asdict(processed_event),
                    "breaking_indicators": breaking_indicators
                }
            )
            
        except Exception as e:
            logger.error(f"키워드 처리/재발행 실패: {e}")
            return False
    
    def _classify_keyword(self, keyword: str) -> str:
        """키워드 분류"""
        # 간단한 분류 로직 (향후 ML 모델로 교체)
        if any(char.isdigit() for char in keyword):
            return "numeric"
        elif len(keyword) <= 2:
            return "short"
        else:
            return "general"
    
    def _calculate_trend_scores(self, keywords: List[str]) -> Dict[str, float]:
        """트렌드 점수 계산 (임시)"""
        trends = {}
        for keyword in keywords:
            # 임시 트렌드 점수 (실제로는 Redis 데이터 기반)
            trends[keyword] = 0.5  # 기본값
        return trends