# config.py
import os
import logging
from typing import Dict, Any, Optional, cast
import re
from dotenv import load_dotenv

# 로깅 설정
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# 환경 변수 로드
load_dotenv()


class Config:
    """애플리케이션 설정 관리 클래스"""
    
    # 데이터베이스 설정
    DB_NAME: str = os.getenv('DB_NAME', '')
    DB_USER: str = os.getenv('DB_USER', '')
    DB_PASSWORD: str = os.getenv('DB_PASSWORD', '')
    DB_HOST: str = os.getenv('DB_HOST', '')
    DB_PORT: str = os.getenv('DB_PORT', '5432')
    
    # 서버 설정
    FASTAPI_PORT: int = int(os.getenv('FASTAPI_PORT', '8000'))
    METRICS_PORT: int = int(os.getenv('METRICS_PORT', '8001'))
    
    # 크롤링 설정
    CRAWL_INTERVAL: int = int(os.getenv('CRAWL_INTERVAL', '10800'))  # 기본값: 3시간(10800초)
    TEMP_FILE: str = os.getenv('TEMP_FILE', 'naver_it_news.json')
    MIN_ARTICLES: int = int(os.getenv('MIN_ARTICLES', '30'))
    
    # 크롤링 URL 및 카테고리
    CATEGORIES: Dict[str, str] = {
        "정치": "https://news.naver.com/section/100",
        "경제": "https://news.naver.com/section/101",
        "사회": "https://news.naver.com/section/102",
        "생활문화": "https://news.naver.com/section/103",
        "세계": "https://news.naver.com/section/104",
        "IT과학": "https://news.naver.com/section/105"
    }

    @classmethod
    def validate(cls) -> None:
        """
        설정값을 검증하고 문제가 있는 경우 예외를 발생시킵니다.
        
        Raises:
            ValueError: 필수 환경변수가 누락되었거나 값이 유효하지 않은 경우
        """
        # 필수 환경변수 목록
        required_vars = ['DB_NAME', 'DB_USER', 'DB_PASSWORD', 'DB_HOST']
        missing_vars = []
        
        # 누락된 변수 확인
        for var in required_vars:
            if not getattr(cls, var):
                missing_vars.append(var)
        
        if missing_vars:
            error_msg = f"필수 환경변수가 누락되었습니다: {', '.join(missing_vars)}"
            logger.error(error_msg)
            raise ValueError(error_msg)
        
        # 포트 형식 검증
        try:
            if not (1 <= cls.FASTAPI_PORT <= 65535):
                raise ValueError(f"FASTAPI_PORT는 1-65535 사이 값이어야 합니다: {cls.FASTAPI_PORT}")
            
            if not (1 <= cls.METRICS_PORT <= 65535):
                raise ValueError(f"METRICS_PORT는 1-65535 사이 값이어야 합니다: {cls.METRICS_PORT}")
                
            if cls.FASTAPI_PORT == cls.METRICS_PORT:
                raise ValueError(f"FASTAPI_PORT와 METRICS_PORT는 서로 다른 값이어야 합니다")
        except ValueError as e:
            logger.error(str(e))
            raise
            
        # DB 포트 검증
        try:
            db_port = int(cls.DB_PORT)
            if not (1 <= db_port <= 65535):
                raise ValueError(f"DB_PORT는 1-65535 사이 값이어야 합니다: {cls.DB_PORT}")
        except ValueError:
            error_msg = f"DB_PORT가 유효한 숫자가 아닙니다: {cls.DB_PORT}"
            logger.error(error_msg)
            raise ValueError(error_msg)
            
        logger.info("모든 설정값 검증 완료")

    @classmethod
    def get_all(cls) -> Dict[str, Any]:
        """
        모든 설정값을 딕셔너리로 반환합니다.
        
        Returns:
            Dict[str, Any]: 설정값 딕셔너리
        """
        config_dict = {}
        for attr in dir(cls):
            if not attr.startswith('_') and not callable(getattr(cls, attr)):
                config_dict[attr] = getattr(cls, attr)
        return config_dict


# 설정값 자동 검증 (임포트 시점에 실행)
try:
    Config.validate()
except Exception as e:
    logger.warning(f"설정값 검증 중 오류 발생: {e}")
    logger.warning("애플리케이션이 일부 기능 제한과 함께 실행됩니다.")

# Config 클래스를 명시적으로 export
__all__ = ['Config']