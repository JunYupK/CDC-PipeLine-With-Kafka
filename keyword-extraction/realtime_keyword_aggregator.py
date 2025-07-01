# realtime_keyword_aggregator.py
import asyncio
import json
import time
from collections import defaultdict
from typing import Dict, List, Set, Optional
from datetime import datetime, timedelta
import redis.asyncio as redis
import logging
from dataclasses import dataclass
import heapq

logger = logging.getLogger(__name__)

@dataclass
class WordCloudData:
    """워드클라우드 데이터 구조"""
    keywords: Dict[str, int]  # {keyword: count}
    window_type: str  # "1min", "5min", "15min"
    timestamp: datetime
    total_count: int
    unique_keywords: int
    top_keywords: List[tuple]  # [(keyword, count), ...]

class RealTimeKeywordAggregator:
    """실시간 키워드 집계기 (메모리 기반)"""
    
    def __init__(self):
        self.redis_client = None
        
        # 시간 윈도우별 메모리 집계
        self.windows = {
            "1min": {"data": defaultdict(int), "start": time.time()},
            "5min": {"data": defaultdict(int), "start": time.time()},
            "15min": {"data": defaultdict(int), "start": time.time()}
        }
        
        # 이벤트 핸들러
        self.on_update_callbacks = []
        
        # 통계
        self.stats = {
            "total_keywords_processed": 0,
            "updates_sent": 0,
            "last_update": None
        }
        
    async def initialize(self, redis_url: str = "redis://localhost:6379"):
        """초기화"""
        self.redis_client = redis.from_url(redis_url, decode_responses=True)
        await self.redis_client.ping()
        
        # 주기적 플러시 태스크 시작
        asyncio.create_task(self._periodic_flush())
        
        logger.info("✅ 실시간 키워드 집계기 초기화 완료")
    
    async def on_cdc_event(self, keywords: List[str], category: str = ""):
        """CDC 이벤트 처리 - 즉시 업데이트"""
        current_time = time.time()
        
        # 모든 윈도우에 키워드 추가
        for window_type, window_data in self.windows.items():
            for keyword in keywords:
                window_data["data"][keyword] += 1
        
        self.stats["total_keywords_processed"] += len(keywords)
        self.stats["last_update"] = datetime.now()
        
        # 즉시 워드클라우드 데이터 생성 및 브로드캐스트
        await self._generate_and_broadcast()
    
    async def _generate_and_broadcast(self):
        """워드클라우드 데이터 생성 및 브로드캐스트"""
        current_time = time.time()
        wordcloud_updates = {}
        
        for window_type, window_data in self.windows.items():
            # 윈도우 만료 체크
            window_duration = self._get_window_duration(window_type)
            if current_time - window_data["start"] > window_duration:
                await self._flush_window(window_type)
                continue
            
            # 워드클라우드 데이터 생성
            wordcloud = self._create_wordcloud_data(
                window_data["data"], 
                window_type
            )
            wordcloud_updates[window_type] = wordcloud
        
        # 콜백 실행 (WebSocket 브로드캐스트 등)
        for callback in self.on_update_callbacks:
            await callback(wordcloud_updates)
        
        self.stats["updates_sent"] += 1
    
    def _create_wordcloud_data(self, keyword_counts: Dict[str, int], 
                              window_type: str) -> WordCloudData:
        """워드클라우드 데이터 생성"""
        # 상위 N개 키워드 추출
        top_n = 50
        top_keywords = heapq.nlargest(
            top_n, 
            keyword_counts.items(), 
            key=lambda x: x[1]
        )
        
        return WordCloudData(
            keywords=dict(keyword_counts),
            window_type=window_type,
            timestamp=datetime.now(),
            total_count=sum(keyword_counts.values()),
            unique_keywords=len(keyword_counts),
            top_keywords=top_keywords
        )
    
    def _get_window_duration(self, window_type: str) -> int:
        """윈도우 타입별 기간 (초)"""
        durations = {
            "1min": 60,
            "5min": 300,
            "15min": 900
        }
        return durations.get(window_type, 300)
    
    async def _flush_window(self, window_type: str):
        """윈도우 데이터를 Redis로 플러시"""
        window_data = self.windows[window_type]
        
        if window_data["data"]:
            # Redis에 저장 (긴 기간 분석용)
            timestamp = datetime.now().strftime("%Y%m%d%H%M")
            redis_key = f"keywords:{window_type}:{timestamp}"
            
            # 각 키워드를 Redis sorted set에 추가
            pipeline = self.redis_client.pipeline()
            for keyword, count in window_data["data"].items():
                pipeline.zadd(redis_key, {keyword: count})
            
            # TTL 설정 (7일)
            pipeline.expire(redis_key, 604800)
            
            await pipeline.execute()
            
            logger.info(f"✅ {window_type} 윈도우 플러시 완료: {len(window_data['data'])}개 키워드")
        
        # 윈도우 초기화
        window_data["data"].clear()
        window_data["start"] = time.time()
    
    async def _periodic_flush(self):
        """주기적 플러시 태스크"""
        while True:
            try:
                current_time = time.time()
                
                for window_type, window_data in self.windows.items():
                    window_duration = self._get_window_duration(window_type)
                    
                    # 윈도우 만료 확인
                    if current_time - window_data["start"] >= window_duration:
                        await self._flush_window(window_type)
                
                # 1분마다 체크
                await asyncio.sleep(60)
                
            except Exception as e:
                logger.error(f"주기적 플러시 오류: {e}")
                await asyncio.sleep(60)
    
    async def get_current_wordcloud(self, window_type: str = "5min") -> WordCloudData:
        """현재 워드클라우드 데이터 조회"""
        if window_type not in self.windows:
            raise ValueError(f"Invalid window type: {window_type}")
        
        window_data = self.windows[window_type]
        return self._create_wordcloud_data(window_data["data"], window_type)
    
    async def get_historical_data(self, window_type: str, hours: int = 1) -> List[Dict]:
        """과거 데이터 조회 (Redis에서)"""
        historical = []
        current_time = datetime.now()
        
        for i in range(hours * 60 // self._get_window_duration(window_type) * 60):
            time_point = current_time - timedelta(minutes=i * 5)
            timestamp = time_point.strftime("%Y%m%d%H%M")
            redis_key = f"keywords:{window_type}:{timestamp}"
            
            # Redis에서 데이터 조회
            data = await self.redis_client.zrevrange(
                redis_key, 0, -1, withscores=True
            )
            
            if data:
                historical.append({
                    "timestamp": time_point.isoformat(),
                    "keywords": dict(data)
                })
        
        return historical
    
    def add_update_callback(self, callback):
        """업데이트 콜백 추가"""
        self.on_update_callbacks.append(callback)
    
    def get_stats(self) -> Dict:
        """통계 조회"""
        return {
            **self.stats,
            "active_windows": {
                window_type: {
                    "keyword_count": len(data["data"]),
                    "total_count": sum(data["data"].values()),
                    "age_seconds": time.time() - data["start"]
                }
                for window_type, data in self.windows.items()
            }
        }

class WordCloudGenerator:
    """워드클라우드 시각화 데이터 생성기"""
    
    def __init__(self):
        self.size_ranges = {
            1: (40, 60),    # 1위
            5: (30, 40),    # 2-5위
            10: (25, 30),   # 6-10위
            20: (20, 25),   # 11-20위
            50: (15, 20),   # 21-50위
            100: (10, 15)   # 51-100위
        }
        
        self.color_schemes = {
            "default": ["#ef4444", "#f59e0b", "#10b981", "#3b82f6", "#8b5cf6"],
            "heatmap": ["#fef3c7", "#fde68a", "#fbbf24", "#f59e0b", "#d97706"],
            "cool": ["#e0f2fe", "#7dd3fc", "#38bdf8", "#0284c7", "#075985"]
        }
    
    def generate_wordcloud_layout(self, wordcloud_data: WordCloudData, 
                                 color_scheme: str = "default") -> Dict:
        """워드클라우드 레이아웃 생성"""
        layout = {
            "words": [],
            "metadata": {
                "window_type": wordcloud_data.window_type,
                "timestamp": wordcloud_data.timestamp.isoformat(),
                "total_keywords": wordcloud_data.unique_keywords,
                "total_count": wordcloud_data.total_count
            }
        }
        
        colors = self.color_schemes.get(color_scheme, self.color_schemes["default"])
        
        for i, (keyword, count) in enumerate(wordcloud_data.top_keywords):
            # 크기 계산
            size = self._calculate_size(i + 1)
            
            # 색상 선택 (상위 키워드일수록 진한 색)
            color_index = min(i // 10, len(colors) - 1)
            color = colors[color_index]
            
            # 위치는 클라이언트에서 동적 계산
            word_data = {
                "text": keyword,
                "size": size,
                "count": count,
                "color": color,
                "rank": i + 1,
                "animation": self._get_animation_class(i + 1)
            }
            
            layout["words"].append(word_data)
        
        return layout
    
    def _calculate_size(self, rank: int) -> int:
        """순위에 따른 크기 계산"""
        for threshold, (min_size, max_size) in self.size_ranges.items():
            if rank <= threshold:
                # 선형 보간
                ratio = (rank - 1) / threshold
                return int(max_size - ratio * (max_size - min_size))
        
        return 10  # 기본 크기
    
    def _get_animation_class(self, rank: int) -> str:
        """애니메이션 클래스 결정"""
        if rank <= 3:
            return "pulse"
        elif rank <= 10:
            return "glow"
        else:
            return "fade-in"
    
    def generate_trend_indicators(self, current_data: WordCloudData, 
                                previous_data: Optional[WordCloudData]) -> Dict[str, str]:
        """트렌드 지표 생성"""
        if not previous_data:
            return {}
        
        trends = {}
        current_ranks = {kw: i+1 for i, (kw, _) in enumerate(current_data.top_keywords)}
        previous_ranks = {kw: i+1 for i, (kw, _) in enumerate(previous_data.top_keywords)}
        
        for keyword in current_ranks:
            if keyword not in previous_ranks:
                trends[keyword] = "new"
            else:
                rank_change = previous_ranks[keyword] - current_ranks[keyword]
                if rank_change > 3:
                    trends[keyword] = "rising-fast"
                elif rank_change > 0:
                    trends[keyword] = "rising"
                elif rank_change < -3:
                    trends[keyword] = "falling-fast"
                elif rank_change < 0:
                    trends[keyword] = "falling"
                else:
                    trends[keyword] = "stable"
        
        return trends