from .news_crawler import meta_crawling as crawl_news
from .contents_crawler import fetch_article_content  as crawl_content

__all__ = ['crawl_news', 'crawl_content']