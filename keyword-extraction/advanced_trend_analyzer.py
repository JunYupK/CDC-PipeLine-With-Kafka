import asyncio
import json
import numpy as np
from datetime import datetime, timedelta
from typing import List, Dict, Optional, Tuple
from dataclasses import dataclass
from collections import defaultdict
import redis.asyncio as redis
import logging

logger = logging.getLogger(__name__)

@dataclass
class TrendMetrics:
    keyword: str
    count_1h: int
    count_6h: int
    count_24h: int
    count_7d: int
    velocity_1h: float
    velocity_6h: float
    z_score: float
    anomaly_score: float
    trend_direction: str  # "rising", "falling", "stable"
    compound_score: float

@dataclass
class AlertTrigger:
    keyword: str
    alert_type: str  # "breakout", "anomaly", "trending"
    score: float
    timestamp: datetime
    metadata: Dict

class AdvancedTrendAnalyzer:
    def __init__(self):
        self.redis_client = None
        self.alert_callbacks = []
        
        # 시간 윈도우 설정 (시간 단위)
        self.time_windows = [1, 6, 24, 168]  # 1h, 6h, 24h, 7d
        
        # 알림 임계값
        self.alert_thresholds = {
            "breakout_ratio": 3.0,
            "velocity_min": 10,
            "z_score_min": 2.0,
            "anomaly_min": 0.8
        }
        
    async def initialize(self, redis_url: str = "redis://localhost:6379"):
        """Redis 연결 초기화"""
        self.redis_client = redis.from_url(redis_url, decode_responses=True)
        await self.redis_client.ping()
        logger.info("✅ 고도화된 트렌드 분석기 초기화 완료")
    
    async def add_keywords(self, keywords: List[str], category: str = "", article_metadata: Dict = None):
        """키워드 추가 및 다차원 트렌드 업데이트"""
        current_time = datetime.now()
        
        for keyword in keywords:
            # 기본 카운트 업데이트
            await self._update_multi_window_counts(keyword, current_time, category)
            
            # 이상치 감지 및 알림 체크
            await self._check_trend_alerts(keyword, category)
    
    async def _update_multi_window_counts(self, keyword: str, timestamp: datetime, category: str):
        """다중 시간 윈도우 카운트 업데이트"""
        pipeline = self.redis_client.pipeline()
        
        # 전체 카운트
        pipeline.zincrby("trending_keywords", 1, keyword)
        
        # 시간 윈도우별 카운트
        for window_hours in self.time_windows:
            window_key = f"keywords:{window_hours}h:{timestamp.strftime('%Y%m%d%H')}"
            pipeline.zincrby(window_key, 1, keyword)
            pipeline.expire(window_key, window_hours * 3600 + 86400)  # 윈도우 + 1일 여유
        
        # 카테고리별 카운트
        if category:
            cat_key = f"keywords:category:{category}"
            pipeline.zincrby(cat_key, 1, keyword)
        
        # 시간별 세부 카운트 (차트용)
        minute_key = f"keywords:timeline:{timestamp.strftime('%Y%m%d%H%M')}"
        pipeline.zincrby(minute_key, 1, keyword)
        pipeline.expire(minute_key, 604800)  # 7일 보관
        
        await pipeline.execute()
    
    async def calculate_trend_metrics(self, keyword: str) -> TrendMetrics:
        """종합 트렌드 메트릭 계산"""
        current_time = datetime.now()
        
        # 다중 윈도우 카운트 수집
        counts = await self._get_multi_window_counts(keyword, current_time)
        
        # 속도 계산 (시간당 증가율)
        velocity_1h = await self._calculate_velocity(keyword, 1, current_time)
        velocity_6h = await self._calculate_velocity(keyword, 6, current_time)
        
        # 통계적 이상치 감지
        z_score, anomaly_score = await self._detect_anomaly(keyword)
        
        # 트렌드 방향 판정
        trend_direction = self._determine_trend_direction(velocity_1h, velocity_6h)
        
        # 종합 점수 계산
        compound_score = self._calculate_compound_score(counts, velocity_1h, z_score, anomaly_score)
        
        return TrendMetrics(
            keyword=keyword,
            count_1h=counts.get("1h", 0),
            count_6h=counts.get("6h", 0),
            count_24h=counts.get("24h", 0),
            count_7d=counts.get("168h", 0),
            velocity_1h=velocity_1h,
            velocity_6h=velocity_6h,
            z_score=z_score,
            anomaly_score=anomaly_score,
            trend_direction=trend_direction,
            compound_score=compound_score
        )
    
    async def _get_multi_window_counts(self, keyword: str, current_time: datetime) -> Dict[str, int]:
        """다중 시간 윈도우 카운트 조회"""
        counts = {}
        
        for window_hours in self.time_windows:
            total_count = 0
            
            # 현재 시간부터 윈도우만큼 역산
            for i in range(min(window_hours, 24)):  # 최대 24시간으로 제한
                hour_time = current_time - timedelta(hours=i)
                window_key = f"keywords:{window_hours}h:{hour_time.strftime('%Y%m%d%H')}"
                try:
                    count = await self.redis_client.zscore(window_key, keyword) or 0
                    total_count += int(count)
                except:
                    continue
            
            counts[f"{window_hours}h"] = total_count
        
        return counts
    
    async def _calculate_velocity(self, keyword: str, window_hours: int, current_time: datetime) -> float:
        """속도 계산 (현재 vs 이전 기간)"""
        # 현재 기간
        current_count = 0
        for i in range(window_hours):
            hour_time = current_time - timedelta(hours=i)
            window_key = f"keywords:{window_hours}h:{hour_time.strftime('%Y%m%d%H')}"
            count = await self.redis_client.zscore(window_key, keyword) or 0
            current_count += int(count)
        
        # 이전 기간
        previous_count = 0
        for i in range(window_hours):
            hour_time = current_time - timedelta(hours=window_hours + i)
            window_key = f"keywords:{window_hours}h:{hour_time.strftime('%Y%m%d%H')}"
            count = await self.redis_client.zscore(window_key, keyword) or 0
            previous_count += int(count)
        
        if previous_count == 0:
            return current_count * 2.0  # 새 키워드
        
        return (current_count - previous_count) / window_hours
    
    async def _detect_anomaly(self, keyword: str) -> Tuple[float, float]:
        """통계적 이상치 감지"""
        # 과거 7일 시간별 데이터 수집
        historical_counts = []
        current_time = datetime.now()
        
        for i in range(168):  # 7일 * 24시간
            hour_time = current_time - timedelta(hours=i)
            window_key = f"keywords:1h:{hour_time.strftime('%Y%m%d%H')}"
            count = await self.redis_client.zscore(window_key, keyword) or 0
            historical_counts.append(int(count))
        
        if len(historical_counts) < 24:  # 최소 24시간 데이터 필요
            return 0.0, 0.0
        
        # 통계 계산
        mean = np.mean(historical_counts[1:])  # 현재 시간 제외
        std = np.std(historical_counts[1:])
        current_count = historical_counts[0]
        
        # Z-score 계산
        z_score = (current_count - mean) / std if std > 0 else 0
        
        # 이상치 점수 (0-1)
        anomaly_score = min(abs(z_score) / 3.0, 1.0)  # 3-sigma 기준 정규화
        
        return float(z_score), float(anomaly_score)
    
    def _determine_trend_direction(self, velocity_1h: float, velocity_6h: float) -> str:
        """트렌드 방향 판정"""
        if velocity_1h > 2.0 and velocity_6h > 1.0:
            return "rising"
        elif velocity_1h < -1.0 and velocity_6h < -0.5:
            return "falling"
        else:
            return "stable"
    
    def _calculate_compound_score(self, counts: Dict, velocity_1h: float, z_score: float, anomaly_score: float) -> float:
        """종합 트렌드 점수 계산"""
        weights = {
            "frequency": 0.25,    # 절대 빈도
            "velocity": 0.30,     # 증가 속도
            "anomaly": 0.25,      # 이상치 정도
            "momentum": 0.20      # 지속성
        }
        
        # 정규화된 점수들
        frequency_score = min(counts.get("1h", 0) / 10.0, 10.0)  # 0-10
        velocity_score = min(max(velocity_1h, 0) / 5.0, 10.0)    # 0-10
        anomaly_score_norm = anomaly_score * 10.0                # 0-10
        momentum_score = min(counts.get("6h", 0) / counts.get("24h", 1), 5.0) * 2  # 0-10
        
        compound = (
            frequency_score * weights["frequency"] +
            velocity_score * weights["velocity"] +
            anomaly_score_norm * weights["anomaly"] +
            momentum_score * weights["momentum"]
        ) * 10  # 0-100 스케일
        
        return round(compound, 2)
    
    async def _check_trend_alerts(self, keyword: str, category: str):
        """실시간 알림 체크"""
        metrics = await self.calculate_trend_metrics(keyword)
        
        alerts = []
        
        # 급상승 알림
        if (metrics.velocity_1h > self.alert_thresholds["velocity_min"] and 
            metrics.z_score > self.alert_thresholds["z_score_min"]):
            alerts.append(AlertTrigger(
                keyword=keyword,
                alert_type="breakout",
                score=metrics.compound_score,
                timestamp=datetime.now(),
                metadata={"category": category, "velocity": metrics.velocity_1h}
            ))
        
        # 이상치 알림
        if metrics.anomaly_score > self.alert_thresholds["anomaly_min"]:
            alerts.append(AlertTrigger(
                keyword=keyword,
                alert_type="anomaly",
                score=metrics.anomaly_score,
                timestamp=datetime.now(),
                metadata={"z_score": metrics.z_score}
            ))
        
        # 알림 발송
        for alert in alerts:
            await self._trigger_alert(alert)
    
    async def _trigger_alert(self, alert: AlertTrigger):
        """알림 발송"""
        # Redis에 알림 저장
        alert_data = {
            "keyword": alert.keyword,
            "type": alert.alert_type,
            "score": alert.score,
            "timestamp": alert.timestamp.isoformat(),
            "metadata": json.dumps(alert.metadata)
        }
        
        await self.redis_client.lpush("trending_alerts", json.dumps(alert_data))
        await self.redis_client.ltrim("trending_alerts", 0, 99)  # 최근 100개만 보관
        
        # WebSocket 브로드캐스트용 이벤트 발행
        await self.redis_client.publish("alert_channel", json.dumps(alert_data))
        
        logger.info(f"🚨 트렌드 알림: {alert.keyword} - {alert.alert_type} (점수: {alert.score})")
    
    async def get_trending_keywords_advanced(self, limit: int = 20) -> List[TrendMetrics]:
        """고도화된 트렌딩 키워드 조회"""
        try:
            # Redis 연결 확인
            await self.redis_client.ping()
        except:
            # 재연결 시도
            await self.initialize(redis_url="redis://:homesweethome@localhost:6379")
        
        # 상위 키워드 후보 조회 (더 많이 가져와서 필터링)
        candidates = await self.redis_client.zrevrange("trending_keywords", 0, limit * 2, withscores=True)
        
        trending_metrics = []
        for keyword, _ in candidates:
            try:
                metrics = await self.calculate_trend_metrics(keyword)
                trending_metrics.append(metrics)
            except Exception as e:
                logger.error(f"키워드 {keyword} 메트릭 계산 실패: {e}")
                continue
        
        # 종합 점수로 정렬
        trending_metrics.sort(key=lambda x: x.compound_score, reverse=True)
        
        return trending_metrics[:limit]
    
    async def get_timeline_data(self, keyword: str, hours: int = 24) -> List[Dict]:
        """키워드 시간별 데이터 (차트용)"""
        timeline = []
        current_time = datetime.now()
        
        for i in range(hours * 60):  # 분 단위
            time_point = current_time - timedelta(minutes=i)
            minute_key = f"keywords:timeline:{time_point.strftime('%Y%m%d%H%M')}"
            count = await self.redis_client.zscore(minute_key, keyword) or 0
            
            timeline.append({
                "timestamp": time_point.isoformat(),
                "count": int(count)
            })
        
        return list(reversed(timeline))  # 시간순 정렬
    
    async def get_recent_alerts(self, limit: int = 10) -> List[Dict]:
        """최근 알림 조회"""
        alerts_raw = await self.redis_client.lrange("trending_alerts", 0, limit - 1)
        return [json.loads(alert) for alert in alerts_raw]