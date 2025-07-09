# main.py - 안정적인 키워드 추출 서비스 (오류 수정 버전)
import os
import json
import logging
import threading
import time
from typing import List, Dict, Optional, Set
from datetime import datetime
from dotenv import load_dotenv
import asyncio

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from confluent_kafka import Consumer, KafkaError, KafkaException

from hybrid_keyword_extractor import HybridKeywordExtractor
from advanced_trend_analyzer import AdvancedTrendAnalyzer, TrendMetrics
from realtime_keyword_aggregator import RealTimeKeywordAggregator, WordCloudData, WordCloudGenerator
from keyword_republisher import KafkaKeywordRepublisher, EnhancedKeywordProcessor


logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)
aggregator = RealTimeKeywordAggregator()
wordcloud_generator = WordCloudGenerator()

load_dotenv()
# 환경변수에서 Kafka 설정 읽기
KAFKA_BOOTSTRAP_SERVERS = os.getenv('KAFKA_BOOTSTRAP_SERVERS')
KAFKA_TOPIC = os.getenv('KAFKA_TOPIC')
KAFKA_GROUP_ID = os.getenv('KAFKA_GROUP_ID')

print(f"Kafka: {KAFKA_BOOTSTRAP_SERVERS}")
print(f"Topic: {KAFKA_TOPIC}")
print(f"Group: {KAFKA_GROUP_ID}")

# === 모델 정의 ===
class KeywordRequest(BaseModel):
    title: str
    content: str
    category: Optional[str] = ""
    metadata: Optional[Dict] = {}

class TrendingResponse(BaseModel):
    keywords: List[Dict]
    alerts: List[Dict]
    total_processed: int

class WebSocketManager:
    def __init__(self):
        self.active_connections: Set[WebSocket] = set()
    
    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.add(websocket)
        logger.info(f"WebSocket 연결됨. 총 {len(self.active_connections)}개 연결")
    
    def disconnect(self, websocket: WebSocket):
        self.active_connections.discard(websocket)
        logger.info(f"WebSocket 연결 해제. 총 {len(self.active_connections)}개 연결")
    
    async def broadcast(self, message: Dict):
        """모든 연결된 클라이언트에 메시지 브로드캐스트"""
        if not self.active_connections:
            return
        
        message_str = json.dumps(message, ensure_ascii=False)
        disconnected = set()
        
        for connection in self.active_connections:
            try:
                await connection.send_text(message_str)
            except Exception:
                disconnected.add(connection)
        
        # 끊어진 연결 정리
        self.active_connections -= disconnected

class KafkaCDCEventHandler:
    """CDC 이벤트 처리 클래스 (수정됨)"""
    
    def __init__(self, extractor: HybridKeywordExtractor, analyzer: AdvancedTrendAnalyzer, websocket_manager: WebSocketManager):
        self.extractor = extractor
        self.analyzer = analyzer
        self.websocket_manager = websocket_manager
        self.processed_count = 0
        
        # 🔥 Kafka 재발행기 초기화 (옵셔널)
        self.keyword_processor = None
        
        try:
            bootstrap_servers = os.getenv('KAFKA_BOOTSTRAP_SERVERS') or "localhost:9092"
            self.republisher = KafkaKeywordRepublisher(
                bootstrap_servers=bootstrap_servers,
                keyword_topic="processed-keywords"
            )
            self.keyword_processor = EnhancedKeywordProcessor(self.republisher)
            logger.info("✅ 키워드 재발행기 초기화 완료")
        except Exception as e:
            logger.error(f"❌ 재발행기 초기화 실패: {e}")
            self.republisher = None
            self.keyword_processor = None

    def process_event(self, event: Dict) -> bool:
        """CDC 이벤트 동기 처리 (수정됨)"""
        try:
            # 이벤트 구조 확인
            payload = event.get('payload', event)
            
            if not payload:
                return False
                
            # 작업 유형 확인 (create, update만 처리)
            operation = payload.get('op')
            if operation not in ('c', 'r', 'u'):
                return True
                
            # 기사 데이터 추출
            article_data = payload.get('after', {})
            if not article_data or not article_data.get('id'):
                return False
                
            title = article_data.get('title', '')
            content = article_data.get('content', '')
            category = article_data.get('category', '')
            article_id = article_data.get('id')
            
            if not title or not content or len(content) < 50:
                logger.debug(f"기사 내용 부족: {article_id}")
                return True
                
            logger.info(f"🔥 기사 처리 시작: {article_id} - {title[:50]}")

            # 🔧 비동기 키워드 추출을 동기 방식으로 처리
            try:
                # 새로운 이벤트 루프에서 키워드 추출 실행
                loop = asyncio.new_event_loop()
                asyncio.set_event_loop(loop)
                
                try:
                    keywords = loop.run_until_complete(
                        self._extract_keywords_async(title, content, category, article_id)
                    )
                    
                    if keywords:
                        self.processed_count += 1
                        
                        # 비동기 작업을 별도 스레드에서 실행
                        threading.Thread(
                            target=self._handle_async_tasks,
                            args=(article_id, title, content, category, keywords)
                        ).start()
                        
                        logger.info(f"✅ 키워드 추출 완료: {len(keywords)}개 - {keywords[:5]}")
                    else:
                        logger.warning(f"⚠️ 키워드 추출 실패: {article_id}")
                        
                finally:
                    loop.close()
                    
            except Exception as e:
                logger.error(f"❌ 키워드 추출 중 오류: {e}")
                return False
                
            return True
            
        except Exception as e:
            logger.error(f"❌ 이벤트 처리 중 오류: {e}", exc_info=True)
            return False
    
    async def _extract_keywords_async(self, title: str, content: str, category: str, article_id: int):
        """키워드 추출 (비동기)"""
        metadata = {
            'category': category,
            'article_id': article_id
        }
        
        return await self.extractor.extract_keywords(title, content, metadata)
    
    def _handle_async_tasks(self, article_id: int, title: str, content: str, category: str, keywords: List[str]):
        """비동기 작업 처리 (별도 이벤트 루프에서)"""
        loop = None
        try:
            # 새로운 이벤트 루프 생성
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            
            # 비동기 작업 실행
            loop.run_until_complete(
                self._async_tasks_with_republish(article_id, title, content, category, keywords)
            )
                
        except Exception as e:
            logger.error(f"비동기 작업 실행 오류: {e}")
        finally:
            # 이벤트 루프 안전하게 정리
            if loop and not loop.is_closed():
                try:
                    pending = asyncio.all_tasks(loop)
                    for task in pending:
                        task.cancel()
                    
                    loop.run_until_complete(loop.shutdown_asyncgens())
                    loop.close()
                except Exception as cleanup_error:
                    logger.error(f"이벤트 루프 정리 중 오류: {cleanup_error}")
    
    async def _async_tasks_with_republish(self, article_id: int, title: str, content: str, 
                                        category: str, keywords: List[str]):
        """재발행 기능이 포함된 비동기 작업들"""
        try:
            # 1. 기존 트렌드 분석
            metadata = {'article_id': article_id}
            await self.analyzer.add_keywords(keywords, category, metadata)
            
            # 2. 실시간 워드클라우드 집계
            await aggregator.on_cdc_event(keywords, category)
            
            # 3. 🔥 키워드 처리 및 Kafka 재발행 (옵셔널)
            republish_success = False
            if self.keyword_processor:
                try:
                    confidence_scores = getattr(self.extractor, 'last_confidence_scores', {})
                    republish_success = await self.keyword_processor.process_and_republish(
                        article_id=article_id,
                        title=title,
                        content=content,
                        category=category,
                        raw_keywords=keywords,
                        confidence_scores=confidence_scores
                    )
                    
                    if republish_success:
                        logger.info(f"🚀 키워드 재발행 성공: article_{article_id}")
                    else:
                        logger.error(f"❌ 키워드 재발행 실패: article_{article_id}")
                except Exception as e:
                    logger.error(f"재발행 처리 중 오류: {e}")
            
            # 4. WebSocket 브로드캐스트 (재발행 상태 포함)
            message = {
                "type": "new_keywords",
                "data": {
                    "article_id": article_id,
                    "title": title,
                    "category": category,
                    "keywords": keywords,
                    "republished": republish_success,
                    "timestamp": datetime.now().isoformat()
                }
            }
            await self.websocket_manager.broadcast(message)
            
        except Exception as e:
            logger.error(f"비동기 작업 실행 오류: {e}")

class StableKafkaConsumer:
    """디버깅이 강화된 Kafka Consumer"""
    
    def __init__(self, event_handler: KafkaCDCEventHandler):
        self.event_handler = event_handler
        self.running = False
        self.consumer = None
        self.worker_thread = None
        
        # Kafka 설정
        self.kafka_config = {
            'bootstrap.servers': KAFKA_BOOTSTRAP_SERVERS,
            'group.id': KAFKA_GROUP_ID,
            'auto.offset.reset': 'latest',
            'enable.auto.commit': True,
            'auto.commit.interval.ms': 1000,
            'max.poll.interval.ms': 300000,
            'session.timeout.ms': 30000,
            'heartbeat.interval.ms': 3000,
        }
        
        self.topics = [KAFKA_TOPIC]
        
    def start(self):
        """Consumer 시작"""
        if self.running:
            logger.warning("이미 실행 중인 Consumer가 있습니다")
            return
            
        self.running = True
        self.worker_thread = threading.Thread(target=self._consume_loop)
        self.worker_thread.daemon = True
        self.worker_thread.start()
        logger.info(f"🚀 Kafka Consumer 시작: {KAFKA_BOOTSTRAP_SERVERS}")
        
    def stop(self):
        """Consumer 중지"""
        self.running = False
        if self.worker_thread:
            logger.info("Kafka Consumer 중지 중...")
            self.worker_thread.join(timeout=30)
            logger.info("Kafka Consumer 중지 완료")
            
    def _consume_loop(self):
        """메시지 소비 루프"""
        try:
            logger.info(f"🔧 Kafka 설정: {self.kafka_config}")
            
            self.consumer = Consumer(self.kafka_config)
            self.consumer.subscribe(self.topics)
            logger.info(f"📡 토픽 구독 완료: {self.topics}")
            
            # Consumer 메타데이터 확인
            metadata = self.consumer.list_topics(timeout=10)
            logger.info(f"📊 사용 가능한 토픽: {list(metadata.topics.keys())}")
            
            if KAFKA_TOPIC in metadata.topics:
                topic_metadata = metadata.topics[KAFKA_TOPIC]
                logger.info(f"📍 토픽 '{KAFKA_TOPIC}' 파티션 수: {len(topic_metadata.partitions)}")
            else:
                logger.error(f"❌ 토픽 '{KAFKA_TOPIC}'을 찾을 수 없습니다!")
                return
            
            message_count = 0
            poll_count = 0
            
            while self.running:
                try:
                    poll_count += 1
                    msg = self.consumer.poll(timeout=5.0)
                    
                    if poll_count % 10 == 0:
                        logger.info(f"⏰ 폴링 #{poll_count} - 메시지: {'있음' if msg else '없음'}")
                    
                    if msg is None:
                        continue
                        
                    if msg.error():
                        if msg.error().code() == KafkaError._PARTITION_EOF:
                            logger.debug(f"파티션 끝 도달: {msg.topic()}-{msg.partition()}")
                        else:
                            logger.error(f"Kafka 오류: {msg.error()}")
                        continue
                    
                    message_count += 1
                    logger.info(f"🔥📨 메시지 #{message_count} 수신!")
                    
                    try:
                        value = msg.value()
                        if value:
                            event = json.loads(value.decode('utf-8'))
                            
                            # 이벤트 처리
                            success = self.event_handler.process_event(event)
                            logger.info(f"{'✅' if success else '❌'} 이벤트 처리 {'성공' if success else '실패'}")
                                
                    except json.JSONDecodeError as e:
                        logger.error(f"JSON 파싱 오류: {e}")
                        continue
                    except Exception as e:
                        logger.error(f"메시지 처리 오류: {e}", exc_info=True)
                        continue
                        
                except KafkaException as e:
                    logger.error(f"Kafka 예외: {e}")
                    time.sleep(5)
                except Exception as e:
                    logger.error(f"예상치 못한 오류: {e}", exc_info=True)
                    time.sleep(1)
                    
        except Exception as e:
            logger.error(f"Consumer 루프 치명적 오류: {e}", exc_info=True)
        finally:
            if self.consumer:
                try:
                    self.consumer.close()
                except Exception as e:
                    logger.error(f"Consumer 종료 오류: {e}")

# === FastAPI 앱 ===
app = FastAPI(title="안정적인 키워드 추출 서비스", version="3.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 전역 인스턴스
extractor = HybridKeywordExtractor()
analyzer = AdvancedTrendAnalyzer()
websocket_manager = WebSocketManager()
event_handler = KafkaCDCEventHandler(extractor, analyzer, websocket_manager)
kafka_consumer = StableKafkaConsumer(event_handler)

@app.on_event("startup")
async def startup():
    """서비스 시작시 초기화"""
    logger.info("🚀 키워드 추출 서비스 초기화 시작")
    
    # 서비스 초기화
    await extractor.initialize()
    await analyzer.initialize(redis_url="redis://:homesweethome@localhost:6379")
    await aggregator.initialize(redis_url="redis://:homesweethome@localhost:6379")
    
    # 워드클라우드 업데이트 콜백 등록
    async def broadcast_wordcloud_update(wordcloud_data):
        serializable_data = {}
        for window_type, data in wordcloud_data.items():
            if isinstance(data, WordCloudData):
                serializable_data[window_type] = {
                    "keywords": data.keywords,
                    "window_type": data.window_type,
                    "timestamp": data.timestamp.isoformat(),
                    "total_count": data.total_count,
                    "unique_keywords": data.unique_keywords,
                    "top_keywords": data.top_keywords
                }
            else:
                serializable_data[window_type] = data
                
        await websocket_manager.broadcast({
            "type": "wordcloud_update",
            "data": serializable_data
        })
    
    aggregator.add_update_callback(broadcast_wordcloud_update)
    
    # Kafka Consumer 시작
    kafka_consumer.start()
    
    # 주기적 워드클라우드 업데이트 태스크 추가
    asyncio.create_task(periodic_wordcloud_update())
    
    logger.info("✅ 키워드 추출 서비스 시작 완료")

async def periodic_wordcloud_update():
    """주기적 워드클라우드 업데이트"""
    logger.info("🔄 주기적 워드클라우드 업데이트 시작")
    
    while True:
        try:
            await asyncio.sleep(60)  # 1분 대기
            
            all_wordcloud_data = {}
            
            for window_type in ["30min", "1h", "6h"]:
                try:
                    wordcloud_data = await aggregator.get_current_wordcloud(window_type)
                    layout = wordcloud_generator.generate_wordcloud_layout(wordcloud_data)
                    
                    all_wordcloud_data[window_type] = {
                        "words": layout.get("words", []),
                        "window_type": window_type,
                        "timestamp": datetime.now().isoformat(),
                        "total_count": wordcloud_data.total_count,
                        "unique_keywords": wordcloud_data.unique_keywords
                    }
                    
                except Exception as e:
                    logger.error(f"워드클라우드 생성 오류 ({window_type}): {e}")
                    continue
            
            if all_wordcloud_data:
                await websocket_manager.broadcast({
                    "type": "wordcloud_update",
                    "data": all_wordcloud_data
                })
                
                logger.info(f"📡 주기적 워드클라우드 업데이트 완료")
            
        except Exception as e:
            logger.error(f"주기적 업데이트 오류: {e}")
            await asyncio.sleep(10)

@app.on_event("shutdown")
async def shutdown():
    """서비스 종료"""
    logger.info("🛑 서비스 종료 중...")
    kafka_consumer.stop()
    
    # 재발행기 종료
    if event_handler.republisher:
        event_handler.republisher.close()
    
    logger.info("✅ 서비스 종료 완료")

@app.websocket("/ws/keywords")
async def websocket_endpoint(websocket: WebSocket):
    """실시간 키워드 WebSocket"""
    await websocket_manager.connect(websocket)
    
    stats_task = None
    try:
        # 연결 시 현재 데이터 전송
        for window_type in ["30min", "1h", "6h"]:
            try:
                wordcloud_data = await aggregator.get_current_wordcloud(window_type)
                layout = wordcloud_generator.generate_wordcloud_layout(wordcloud_data)
                
                await websocket.send_text(json.dumps({
                    "type": "wordcloud_update",
                    "data": {window_type: layout}
                }, ensure_ascii=False))
            except Exception as e:
                logger.error(f"초기 워드클라우드 데이터 전송 오류: {e}")
        
        # 통계 업데이트 스트리밍 시작
        stats_task = asyncio.create_task(stream_stats(websocket))
        
        # 연결 유지
        while True:
            try:
                message = await asyncio.wait_for(websocket.receive_text(), timeout=30.0)
                logger.debug(f"WebSocket 메시지 수신: {message}")
            except asyncio.TimeoutError:
                try:
                    await websocket.send_text(json.dumps({
                        "type": "ping",
                        "timestamp": datetime.now().isoformat()
                    }))
                except Exception:
                    break
                        
    except WebSocketDisconnect:
        logger.info("클라이언트가 WebSocket 연결을 종료했습니다")
    except Exception as e:
        logger.error(f"WebSocket 엔드포인트 오류: {e}")
    finally:
        if stats_task and not stats_task.done():
            stats_task.cancel()
        websocket_manager.disconnect(websocket)

async def stream_stats(websocket: WebSocket):
    """주기적 통계 업데이트"""
    try:
        while True:
            stats = {
                "processed_articles": event_handler.processed_count,
                "active_keywords": len((await aggregator.get_current_wordcloud("30min")).keywords),
                "timestamp": datetime.now().isoformat()
            }
            
            await websocket.send_text(json.dumps({
                "type": "stats_update",
                "data": stats
            }))
            
            await asyncio.sleep(5)
            
    except Exception as e:
        logger.error(f"통계 스트리밍 오류: {e}")

# === API 엔드포인트들 ===
@app.get("/trending-keywords-advanced")
async def get_trending_keywords_advanced(limit: int = 20):
    """고도화된 트렌딩 키워드 조회"""
    trending = await analyzer.get_trending_keywords_advanced(limit)
    
    return {
        "keywords": [
            {
                "keyword": t.keyword,
                "counts": {
                    "1h": t.count_1h,
                    "6h": t.count_6h,
                    "24h": t.count_24h,
                    "7d": t.count_7d
                },
                "velocity": {
                    "1h": t.velocity_1h,
                    "6h": t.velocity_6h
                },
                "anomaly_score": t.anomaly_score,
                "z_score": t.z_score,
                "trend_direction": t.trend_direction,
                "compound_score": t.compound_score
            }
            for t in trending
        ],
        "total_count": len(trending)
    }

@app.get("/keyword-timeline/{keyword}")
async def get_keyword_timeline(keyword: str, hours: int = 24):
    """키워드 시간별 데이터"""
    timeline = await analyzer.get_timeline_data(keyword, hours)
    return {"keyword": keyword, "timeline": timeline}

@app.get("/alerts")
async def get_recent_alerts(limit: int = 10):
    """최근 알림 조회"""
    alerts = await analyzer.get_recent_alerts(limit)
    return {"alerts": alerts}

@app.post("/extract-keywords")
async def manual_extract_keywords(request: KeywordRequest):
    """수동 키워드 추출 (테스트용)"""
    start_time = datetime.now()
    
    keywords = await extractor.extract_keywords(
        request.title, 
        request.content, 
        request.metadata
    )
    
    if keywords:
        await analyzer.add_keywords(keywords, request.category, request.metadata)
    
    processing_time = (datetime.now() - start_time).total_seconds()
    
    return {
        "keywords": keywords,
        "processing_time": processing_time,
        "extraction_method": "hybrid" if extractor.openai_client else "basic"
    }

@app.get("/wordcloud/{window_type}")
async def get_wordcloud(window_type: str = "30min"):
    """워드클라우드 데이터 조회"""
    if window_type not in ["30min", "1h", "6h"]:
        return {"error": "Invalid window type"}
    
    wordcloud_data = await aggregator.get_current_wordcloud(window_type)
    layout = wordcloud_generator.generate_wordcloud_layout(wordcloud_data)
    
    return layout

@app.get("/wordcloud/compare")
async def compare_windows():
    """다중 윈도우 비교"""
    comparison = {}
    
    for window_type in ["30min", "1h", "6h"]:
        wordcloud_data = await aggregator.get_current_wordcloud(window_type)
        comparison[window_type] = {
            "total_count": wordcloud_data.total_count,
            "unique_keywords": wordcloud_data.unique_keywords,
            "top_5": wordcloud_data.top_keywords[:5]
        }
    
    return comparison

# 🔥 재발행기 관련 엔드포인트 (옵셔널)
@app.get("/republisher/stats")
async def get_republisher_stats():
    """키워드 재발행 통계"""
    if event_handler.republisher:
        return {
            "republisher_stats": event_handler.republisher.get_stats(),
            "processed_articles": event_handler.processed_count,
            "available": True,
            "timestamp": datetime.now().isoformat()
        }
    else:
        return {
            "available": False,
            "message": "재발행 기능이 비활성화되어 있습니다.",
            "timestamp": datetime.now().isoformat()
        }

@app.get("/stats")
async def get_service_stats():
    """서비스 통계 (단일 엔드포인트)"""
    extraction_stats = extractor.get_extraction_stats()
    
    return {
        "processed_articles": event_handler.processed_count,
        "active_websockets": len(websocket_manager.active_connections),
        "extraction_stats": extraction_stats,
        "kafka_running": kafka_consumer.running,
        "timestamp": datetime.now().isoformat()
    }

@app.get("/health")
async def health_check():
    """헬스 체크"""
    try:
        await analyzer.redis_client.ping()
        redis_status = True
    except:
        redis_status = False
    
    return {
        "status": "healthy" if (redis_status and kafka_consumer.running) else "degraded",
        "services": {
            "redis": redis_status,
            "kafka_consumer": kafka_consumer.running,
            "websocket_connections": len(websocket_manager.active_connections),
        },
        "kafka_config": {
            "bootstrap_servers": KAFKA_BOOTSTRAP_SERVERS,
            "topic": KAFKA_TOPIC,
            "group_id": KAFKA_GROUP_ID
        },
        "processed_count": event_handler.processed_count
    }

@app.get("/debug/kafka-status")
async def debug_kafka_status():
    """Kafka 상태 디버깅"""
    return {
        "consumer_running": kafka_consumer.running,
        "worker_thread_alive": kafka_consumer.worker_thread.is_alive() if kafka_consumer.worker_thread else False,
        "processed_messages": event_handler.processed_count,
        "config": kafka_consumer.kafka_config,
        "topics": kafka_consumer.topics,
    }

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8001, reload=False)