from .news_crawler import meta_crawling as crawl_news
from .content_crawler import get_article as crawl_content

__all__ = ['crawl_news', 'crawl_content']