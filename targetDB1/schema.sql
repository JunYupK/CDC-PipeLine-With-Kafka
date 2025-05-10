-- 카테고리 테이블
CREATE TABLE categories (
                            id INT AUTO_INCREMENT PRIMARY KEY,
                            name VARCHAR(50) NOT NULL,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 뉴스 아티클 테이블
CREATE TABLE articles (
                          id BIGINT,
                          title TEXT NOT NULL,
                          content TEXT NOT NULL,
                          link TEXT NOT NULL,
                          category_id INT,
                          category VARCHAR(100),
                          source VARCHAR(100),
                          author VARCHAR(100),
                          published_at TIMESTAMP NULL,
                          stored_date CHAR(8) NOT NULL,
                          views_count INT DEFAULT 0,
                          sentiment_score FLOAT,
                          article_text_length INT,
                          extracted_entities JSON,
                          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                          updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                          version INT DEFAULT 1,
                          is_deleted BOOLEAN DEFAULT FALSE,
                          PRIMARY KEY (id),
                          INDEX idx_category (category),
                          INDEX idx_stored_date (stored_date),
                          INDEX idx_published_at (published_at),
                          FULLTEXT INDEX idx_article_content (title, content)
);

-- 멀티미디어 테이블
CREATE TABLE media (
                       id BIGINT PRIMARY KEY,
                       article_id BIGINT,
                       stored_date CHAR(8),
                       type VARCHAR(20) NOT NULL,
                       url TEXT NOT NULL,
                       caption TEXT,
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                       FOREIGN KEY (article_id) REFERENCES articles(id) ON DELETE CASCADE
);

-- 초기 카테고리 데이터 삽입
INSERT INTO categories (name) VALUES
                                  ('정치'),
                                  ('경제'),
                                  ('사회'),
                                  ('생활문화'),
                                  ('세계'),
                                  ('IT과학');