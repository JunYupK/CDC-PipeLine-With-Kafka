# main.py - 통합 키워드 추출 서비스
import asyncio
import json
import logging
from datetime import datetime
from typing import List, Dict, Optional, Set

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from aiokafka import AIOKafkaConsumer

from hybrid_keyword_extractor import HybridKeywordExtractor
from advanced_trend_analyzer import AdvancedTrendAnalyzer, TrendMetrics

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

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

class KafkaArticleConsumer:
    def __init__(self, extractor: HybridKeywordExtractor, analyzer: AdvancedTrendAnalyzer, websocket_manager: WebSocketManager):
        self.extractor = extractor
        self.analyzer = analyzer
        self.websocket_manager = websocket_manager
        self.consumer = None
        self.running = False
        self.processed_count = 0
        
    async def start_consuming(self):
        """Kafka Consumer 시작"""
        self.running = True
        
        try:
            self.consumer = AIOKafkaConsumer(
                'postgres.public.articles',
                bootstrap_servers='localhost:9092',
                group_id='keyword-extraction-service',
                auto_offset_reset='latest',
                value_deserializer=lambda m: json.loads(m.decode('utf-8'))
            )
            
            await self.consumer.start()
            logger.info("📡 Kafka Consumer 시작 완료")
            
            async for message in self.consumer:
                if not self.running:
                    break
                await self._process_cdc_event(message.value)
                
        except Exception as e:
            logger.error(f"❌ Kafka Consumer 오류: {e}")
        finally:
            if self.consumer:
                await self.consumer.stop()
    
    async def _process_cdc_event(self, event_data: Dict):
        """CDC 이벤트 처리"""
        try:
            if event_data.get('op') != 'c':
                return
                
            article_data = event_data.get('after', {})
            if not article_data or article_data.get('keywords'):
                return
                
            article_id = article_data.get('id')
            title = article_data.get('title', '')
            content = article_data.get('content', '')
            category = article_data.get('category', '')
            
            if not title or not content or len(content) < 50:
                return
                
            logger.info(f"📄 기사 처리 시작: {article_id}")
            
            # 하이브리드 키워드 추출
            metadata = {
                'views_count': article_data.get('views_count', 0),
                'category': category,
                'article_id': article_id
            }
            
            keywords = await self.extractor.extract_keywords(title, content, metadata)
            
            if keywords:
                # 고도화된 트렌드 분석에 추가
                await self.analyzer.add_keywords(keywords, category, metadata)
                
                self.processed_count += 1
                
                # WebSocket으로 실시간 전송
                await self._broadcast_new_keywords(article_id, title, category, keywords)
                
                logger.info(f"✅ 키워드 추출 완료: {keywords}")
            
        except Exception as e:
            logger.error(f"CDC 이벤트 처리 오류: {e}")
    
    async def _broadcast_new_keywords(self, article_id: int, title: str, category: str, keywords: List[str]):
        """새 키워드 WebSocket 브로드캐스트"""
        message = {
            "type": "new_keywords",
            "data": {
                "article_id": article_id,
                "title": title,
                "category": category,
                "keywords": keywords,
                "timestamp": datetime.now().isoformat()
            }
        }
        await self.websocket_manager.broadcast(message)
    
    async def stop(self):
        self.running = False
        if self.consumer:
            await self.consumer.stop()

# === FastAPI 앱 ===
app = FastAPI(title="고도화된 키워드 추출 서비스", version="2.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 전역 인스턴스
extractor = HybridKeywordExtractor()  # OpenAI API 키가 있으면 추가
analyzer = AdvancedTrendAnalyzer()
websocket_manager = WebSocketManager()
kafka_consumer = KafkaArticleConsumer(extractor, analyzer, websocket_manager)

@app.on_event("startup")
async def startup():
    """서비스 시작시 초기화"""
    await extractor.initialize()
    await analyzer.initialize()
    
    # Kafka Consumer와 Redis 알림 구독 시작
    asyncio.create_task(kafka_consumer.start_consuming())
    asyncio.create_task(redis_alert_subscriber())
    
    logger.info("🚀 고도화된 키워드 추출 서비스 시작 완료")

@app.on_event("shutdown")
async def shutdown():
    await kafka_consumer.stop()

async def redis_alert_subscriber():
    """Redis 알림 채널 구독 (WebSocket 브로드캐스트용)"""
    import redis.asyncio as redis
    
    try:
        redis_client = redis.from_url("redis://localhost:6379")
        pubsub = redis_client.pubsub()
        await pubsub.subscribe("alert_channel")
        
        async for message in pubsub.listen():
            if message["type"] == "message":
                alert_data = json.loads(message["data"])
                
                # WebSocket으로 알림 브로드캐스트
                await websocket_manager.broadcast({
                    "type": "alert",
                    "data": alert_data
                })
                
    except Exception as e:
        logger.error(f"Redis 알림 구독 오류: {e}")

@app.websocket("/ws/keywords")
async def websocket_endpoint(websocket: WebSocket):
    """실시간 키워드 WebSocket"""
    await websocket_manager.connect(websocket)
    
    try:
        # 연결 시 현재 트렌딩 키워드 전송
        trending = await analyzer.get_trending_keywords_advanced(10)
        await websocket.send_text(json.dumps({
            "type": "initial_trending",
            "data": [
                {
                    "keyword": t.keyword,
                    "count": t.count_1h,
                    "trend": t.trend_direction,
                    "score": t.compound_score
                }
                for t in trending
            ]
        }, ensure_ascii=False))
        
        # 연결 유지
        while True:
            await websocket.receive_text()
            
    except WebSocketDisconnect:
        websocket_manager.disconnect(websocket)

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
    """키워드 시간별 데이터 (차트용)"""
    timeline = await analyzer.get_timeline_data(keyword, hours)
    return {"keyword": keyword, "timeline": timeline}

@app.get("/alerts")
async def get_recent_alerts(limit: int = 10):
    """최근 알림 조회"""
    alerts = await analyzer.get_recent_alerts(limit)
    return {"alerts": alerts}

@app.get("/stats")
async def get_service_stats():
    """서비스 통계"""
    extraction_stats = extractor.get_extraction_stats()
    
    return {
        "processed_articles": kafka_consumer.processed_count,
        "active_websockets": len(websocket_manager.active_connections),
        "extraction_stats": extraction_stats,
        "timestamp": datetime.now().isoformat()
    }

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

@app.get("/health")
async def health_check():
    try:
        await analyzer.redis_client.ping()
        redis_status = True
    except:
        redis_status = False
    
    return {
        "status": "healthy" if redis_status else "degraded",
        "services": {
            "redis": redis_status,
            "kafka_consumer": kafka_consumer.running,
            "websocket_connections": len(websocket_manager.active_connections)
        },
        "processed_count": kafka_consumer.processed_count
    }

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8001, reload=True)