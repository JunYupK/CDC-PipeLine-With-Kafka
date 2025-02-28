import os
import sys
import json
import pytest
from unittest.mock import patch, MagicMock, AsyncMock
from pathlib import Path

sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

from services.crawlers.news_crawler import naver_news_metadata_crawler, meta_crawling
from services.crawlers.content_crawler import get_article_content, get_article
from services.db.postgres import insert_multiple_articles, save_to_db_with_retry
from config import Config


class TestConfig:
    """설정 테스트"""
    
    def test_config_validation(self):
        """설정 유효성 검증 테스트"""
        # 환경 변수가 설정되어 있어야 함
        assert Config.DB_NAME is not None
        assert Config.DB_USER is not None
        assert Config.DB_HOST is not None
        assert Config.FASTAPI_PORT is not None
        assert Config.METRICS_PORT is not None
        
        # 카테고리 설정 확인
        assert len(Config.CATEGORIES) == 6
        assert "정치" in Config.CATEGORIES
        assert "경제" in Config.CATEGORIES
        assert "IT과학" in Config.CATEGORIES


class TestNewsCrawler:
    """뉴스 크롤러 테스트"""
    
    @pytest.mark.asyncio
    @patch('services.crawlers.news_crawler.AsyncWebCrawler')
    async def test_metadata_crawler(self, mock_crawler):
        """메타데이터 크롤러 테스트"""
        # AsyncWebCrawler 모의 객체 설정
        mock_crawler_instance = AsyncMock()
        mock_crawler.return_value.__aenter__.return_value = mock_crawler_instance
        
        # arun 메서드 응답 설정
        mock_result = MagicMock()
        mock_result.extracted_content = json.dumps([
            {"title": "테스트 기사 1", "link": "https://example.com/1"},
            {"title": "테스트 기사 2", "link": "https://example.com/2"}
        ])
        mock_crawler_instance.arun.return_value = mock_result
        
        # 함수 실행 및 검증
        count, articles = await naver_news_metadata_crawler("https://example.com", 1)
        
        assert count == 2
        assert len(articles) == 2
        assert articles[0]["title"] == "테스트 기사 1"
        assert articles[1]["link"] == "https://example.com/2"
        
    @pytest.mark.asyncio
    @patch('services.crawlers.news_crawler.naver_news_metadata_crawler')
    @patch('services.crawlers.news_crawler.open')
    async def test_meta_crawling(self, mock_open, mock_crawler):
        """메타 크롤링 테스트"""
        # 크롤러 응답 설정
        mock_articles = [
            {"title": "테스트 기사 1", "link": "https://example.com/1"},
            {"title": "테스트 기사 2", "link": "https://example.com/2"}
        ]
        mock_crawler.return_value = (len(mock_articles), mock_articles)
        
        # 함수 실행 및 검증
        articles = await meta_crawling("https://example.com")
        
        assert articles is not None
        assert len(articles) == 2
        assert articles[0]["title"] == "테스트 기사 1"
        mock_crawler.assert_called_once()
        mock_open.assert_called_once()


class TestContentCrawler:
    """콘텐츠 크롤러 테스트"""
    
    @pytest.mark.asyncio
    @patch('services.crawlers.content_crawler.AsyncWebCrawler')
    async def test_get_article_content(self, mock_crawler):
        """기사 내용 크롤링 테스트"""
        # 크롤러 인스턴스 설정
        crawler_instance = AsyncMock()
        
        # 크롤러 응답 설정
        mock_result = MagicMock()
        mock_result.extracted_content = json.dumps([
            {
                "content": "테스트 기사 내용입니다.",
                "article_images": ["https://example.com/image1.jpg"]
            }
        ])
        crawler_instance.arun.return_value = mock_result
        
        # 함수 실행 및 검증
        result = await get_article_content("https://example.com/1", crawler_instance)
        
        assert result is not None
        assert result["content"] == "테스트 기사 내용입니다."
        assert result["images"] == ["https://example.com/image1.jpg"]
        crawler_instance.arun.assert_called_once()


class TestDatabase:
    """데이터베이스 작업 테스트"""
    
    @patch('services.db.postgres.get_db_connection')
    def test_insert_multiple_articles(self, mock_get_conn):
        """기사 삽입 테스트"""
        # DB 연결 및 커서 모의 설정
        mock_conn = MagicMock()
        mock_cursor = MagicMock()
        mock_conn.cursor.return_value = mock_cursor
        mock_get_conn.return_value.__enter__.return_value = mock_conn
        
        # 테스트 데이터
        articles = [
            {
                "title": "테스트 기사 1",
                "content": "내용 1",
                "link": "https://example.com/1",
                "stored_date": "20250101",
                "category": "정치"
            },
            {
                "title": "테스트 기사 2",
                "content": "내용 2",
                "link": "https://example.com/2",
                "stored_date": "20250101",
                "category": "경제",
                "img": "https://example.com/image2.jpg"
            }
        ]
        
        # 함수 실행 및 검증
        result = insert_multiple_articles(articles)
        
        assert result == 2
        mock_cursor.executemany.assert_called_once()
        mock_conn.commit.assert_called_once()
        mock_cursor.close.assert_called_once()
        
    @patch('services.db.postgres.insert_multiple_articles')
    def test_save_to_db_with_retry(self, mock_insert):
        """재시도 로직 테스트"""
        # 삽입 결과 설정
        mock_insert.return_value = 5
        
        # 테스트 데이터
        articles = [{"title": f"기사 {i}"} for i in range(5)]
        
        # 함수 실행 및 검증
        result = save_to_db_with_retry(articles)
        
        assert result == 5
        mock_insert.assert_called_once_with(articles)


if __name__ == "__main__":
    pytest.main(["-v"])
