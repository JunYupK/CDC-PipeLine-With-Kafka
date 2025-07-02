import asyncio
import openai
from typing import List, Dict, Optional, Tuple
import re
import logging
from keybert import KeyBERT
from transformers import AutoTokenizer, AutoModel
import torch

logger = logging.getLogger(__name__)

class HybridKeywordExtractor:
    def __init__(self, openai_api_key: Optional[str] = None):
        self.keybert_model = None
        self.kobert_tokenizer = None
        self.kobert_model = None
        self.openai_client = None
        
        if openai_api_key:
            self.openai_client = openai.AsyncOpenAI(api_key=openai_api_key)
        
        # 중요 기사 판정 임계값
        self.important_thresholds = {
            "views_count": 10000,
            "comments_count": 100
        }
        
        # 통계 카운터
        self._basic_only_count = 0
        self._llm_refined_count = 0
        self._total_processed = 0
        
    async def initialize(self):
        """KoBERT + KeyBERT 모델 초기화"""
        try:
            # KoBERT 모델 로드
            model_name = "skt/kobert-base-v1"
            logger.info(f"KoBERT 모델 로딩 시작: {model_name}")
            
            self.kobert_tokenizer = AutoTokenizer.from_pretrained(model_name)
            self.kobert_model = AutoModel.from_pretrained(model_name)
            
            # KeyBERT 초기화 (KoBERT 사용)
            self.keybert_model = KeyBERT(model=self.kobert_model)
            
            logger.info("✅ KoBERT + KeyBERT 초기화 완료")
            
        except Exception as e:
            logger.error(f"❌ 모델 초기화 실패: {e}")
            # Fallback: multilingual 모델 사용
            logger.info("Fallback: multilingual 모델 사용")
            from sentence_transformers import SentenceTransformer
            fallback_model = SentenceTransformer('sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2')
            self.keybert_model = KeyBERT(model=fallback_model)
    
    def _remove_josa(self, text: str) -> str:
        """조사 제거"""
        # 한글 조사 패턴 
        josa_patterns = [
            r'(을|를|이|가|은|는|에|에서|에게|한테|께|으로|로|와|과|랑|이랑)$',
            r'(의|도|만|까지|부터|마저|조차|밖에|뿐|라도|라서)$',
            r'(에서|에게|한테서|로부터|으로부터)$',
            r'(다가|면서|지만|거나|든지)$'
        ]
        
        for pattern in josa_patterns:
            text = re.sub(pattern, '', text)
        
        return text.strip()
    
    def _extract_simple_nouns(self, text: str) -> List[str]:
        """간단한 명사 추출"""
        # 한글 단어 추출 (2-8글자)
        korean_words = re.findall(r'[가-힣]{2,8}', text)
        
        # 조사 제거 및 필터링
        nouns = []
        for word in korean_words:
            clean_word = self._remove_josa(word)
            if clean_word and len(clean_word) >= 2:
                nouns.append(clean_word)
        
        return list(dict.fromkeys(nouns))  # 순서 유지하며 중복 제거
    
    def _preprocess_text(self, title: str, content: str) -> str:
        """텍스트 전처리"""
        # 제목 가중치 (3배)
        processed_text = f"{title} {title} {title}"
        
        # 본문 추가 (최대 1000자)
        if content and len(content.strip()) > 20:
            content_truncated = content[:1000]
            processed_text += f" {content_truncated}"
        
        # 특수문자 제거 및 정제
        processed_text = re.sub(r'[^\w\s가-힣]', ' ', processed_text)
        processed_text = re.sub(r'\s+', ' ', processed_text).strip()
        
        return processed_text
    
    async def extract_keywords(self, title: str, content: str, article_metadata: Dict = None) -> List[str]:
        """키워드 추출 메인 함수"""
        self._total_processed += 1
        
        # 전처리
        processed_text = self._preprocess_text(title, content)
        
        # 기본 키워드 추출
        keywords = await self._extract_basic_keywords(processed_text)
        
        # 중요 기사인 경우 LLM 정제
        if self._is_important_article(article_metadata) and self.openai_client:
            self._llm_refined_count += 1
            refined_keywords = await self._refine_with_llm(title, content, keywords)
            keywords = self._merge_keywords(keywords, refined_keywords)
        else:
            self._basic_only_count += 1
        
        return keywords[:6]  # 최대 6개
    
    async def _extract_basic_keywords(self, text: str) -> List[str]:
        """기본 키워드 추출"""
        # 범용 불용어
        stopwords = {
            '기자', '이번', '다음', '지난', '당시', '현재', '오늘', '내일', '어제',
            '그러나', '하지만', '또한', '그리고', '때문', '통해', '위해', '따라',
            '이미지', '사진', '제공', '뉴스', '기사', '오전', '오후',
            '있는', '없는', '되는', '하는', '같은', '많은', '모든',
            '이상', '이하', '관련', '대한', '통한', '의한'
        }
        
        try:
            if self.keybert_model:
                # KeyBERT로 키워드 추출 (단어 단위만)
                keywords = self.keybert_model.extract_keywords(
                    text,
                    keyphrase_ngram_range=(1, 1),  # 단어만
                    stop_words=list(stopwords),
                    use_mmr=True,
                    diversity=0.3,
                    top_n=10
                )
                
                # 조사 제거 및 검증
                valid_keywords = []
                seen = set()
                
                for keyword, score in keywords:
                    clean_kw = self._remove_josa(keyword.strip())
                    if self._is_valid_keyword(clean_kw, seen, stopwords):
                        valid_keywords.append(clean_kw)
                        seen.add(clean_kw.lower())
                
                # 부족하면 단순 명사 추출로 보완
                if len(valid_keywords) < 4:
                    simple_nouns = self._extract_simple_nouns(text)
                    for noun in simple_nouns:
                        if noun.lower() not in seen and noun not in stopwords:
                            valid_keywords.append(noun)
                            seen.add(noun.lower())
                            if len(valid_keywords) >= 6:
                                break
                
                return valid_keywords
            else:
                # KeyBERT 없으면 단순 명사 추출
                return self._extract_simple_nouns(text)[:6]
                
        except Exception as e:
            logger.error(f"키워드 추출 오류: {e}")
            return self._extract_simple_nouns(text)[:6]
    
    def _is_valid_keyword(self, keyword: str, seen: set, stopwords: set) -> bool:
        """키워드 유효성 검사"""
        if not keyword or len(keyword) < 2 or len(keyword) > 8:
            return False
        
        if keyword.isdigit() or keyword.lower() in stopwords:
            return False
        
        if keyword.lower() in seen:
            return False
        
        # 동사/형용사 어미 체크
        if re.search(r'(하다|되다|있다|없다)$', keyword):
            return False
        
        return True
    
    def _is_important_article(self, metadata: Dict) -> bool:
        """중요 기사 판정"""
        if not metadata:
            return False
        
        # 조회수나 댓글수가 임계값 이상
        if metadata.get('views_count', 0) > self.important_thresholds["views_count"]:
            return True
        
        if metadata.get('comments_count', 0) > self.important_thresholds["comments_count"]:
            return True
        
        return False
    
    async def _refine_with_llm(self, title: str, content: str, basic_keywords: List[str]) -> List[str]:
        """LLM으로 키워드 정제"""
        try:
            content_truncated = content[:500] if len(content) > 500 else content
            
            prompt = f"""
다음 뉴스 기사의 핵심 키워드 5개를 추출하세요.
단어 형태로만, 조사 없이, 2-8자 길이로 추출하세요.

제목: {title}
내용: {content_truncated}
참고 키워드: {', '.join(basic_keywords)}

키워드(쉼표로 구분):
"""
            
            response = await self.openai_client.chat.completions.create(
                model="gpt-3.5-turbo",
                messages=[{"role": "user", "content": prompt}],
                max_tokens=100,
                temperature=0.3
            )
            
            refined_text = response.choices[0].message.content.strip()
            refined_keywords = [kw.strip() for kw in refined_text.split(',')]
            
            # 정제
            valid_refined = []
            for kw in refined_keywords:
                clean_kw = self._remove_josa(kw)
                if clean_kw and 2 <= len(clean_kw) <= 8:
                    valid_refined.append(clean_kw)
            
            return valid_refined[:5]
            
        except Exception as e:
            logger.error(f"LLM 정제 오류: {e}")
            return []
    
    def _merge_keywords(self, basic_keywords: List[str], refined_keywords: List[str]) -> List[str]:
        """키워드 병합"""
        merged = []
        seen = set()
        
        # LLM 정제 키워드 우선
        for kw in refined_keywords:
            if kw.lower() not in seen:
                merged.append(kw)
                seen.add(kw.lower())
        
        # 기본 키워드 추가
        for kw in basic_keywords:
            if kw.lower() not in seen and len(merged) < 6:
                merged.append(kw)
                seen.add(kw.lower())
        
        return merged
    
    def get_extraction_stats(self) -> Dict:
        """추출 통계"""
        return {
            "basic_only_count": self._basic_only_count,
            "llm_refined_count": self._llm_refined_count,
            "total_processed": self._total_processed,
            "llm_usage_ratio": self._llm_refined_count / max(self._total_processed, 1)
        }
    
    async def batch_extract_keywords(self, articles: List[Dict], batch_size: int = 10) -> List[Dict]:
        """배치 처리"""
        results = []
        
        for i in range(0, len(articles), batch_size):
            batch = articles[i:i + batch_size]
            tasks = []
            
            for article in batch:
                task = self.extract_keywords(
                    article.get('title', ''),
                    article.get('content', ''),
                    article.get('metadata', {})
                )
                tasks.append(task)
            
            batch_results = await asyncio.gather(*tasks, return_exceptions=True)
            
            for article, keywords in zip(batch, batch_results):
                if isinstance(keywords, Exception):
                    logger.error(f"배치 처리 오류: {keywords}")
                    keywords = []
                
                results.append({
                    'article_id': article.get('id'),
                    'keywords': keywords
                })
        
        return results

