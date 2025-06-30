import asyncio
import openai
from typing import List, Dict, Optional, Tuple
import re
import logging
from keybert import KeyBERT
from sentence_transformers import SentenceTransformer
from konlpy.tag import Okt  # 조사 분리를 위한 형태소 분석기

logger = logging.getLogger(__name__)

class HybridKeywordExtractor:
    def __init__(self, openai_api_key: Optional[str] = None):
        self.keybert_model = None
        self.openai_client = None
        self.okt = None  # 형태소 분석기
        
        if openai_api_key:
            self.openai_client = openai.AsyncOpenAI(api_key=openai_api_key)
        
        # 중요 기사 판정 임계값
        self.important_thresholds = {
            "views_count": 1000,
            "hot_keywords": ["긴급", "속보", "발표", "사고", "선거", "투자", "상장", "코로나", "지진"]
        }
        
        # 통계 카운터
        self._basic_only_count = 0
        self._llm_refined_count = 0
        self._total_processed = 0
        self._empty_content_count = 0
        
    async def initialize(self):
        """모델 초기화"""
        sentence_model = SentenceTransformer('sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2')
        self.keybert_model = KeyBERT(model=sentence_model)
        self.okt = Okt()  # 형태소 분석기 초기화
        logger.info("✅ 하이브리드 키워드 추출기 초기화 완료")
    
    def _remove_josa(self, text: str) -> str:
        """조사 제거 및 명사 추출"""
        try:
            # 형태소 분석
            morphs = self.okt.pos(text)
            
            # 명사류(Noun, Alpha)만 추출하여 재조합
            nouns = []
            for word, pos in morphs:
                if pos in ['Noun', 'Alpha'] or (pos == 'Number' and len(word) > 1):
                    nouns.append(word)
            
            # 연속된 명사는 결합
            if len(nouns) > 1:
                return ' '.join(nouns)
            elif nouns:
                return nouns[0]
            else:
                # 명사가 없으면 원본 반환 (조사만 제거)
                josa_pattern = r'(을|를|이|가|은|는|에|에서|에게|으로|로|와|과|의|도|만|까지|부터|마저|조차|밖에)$'
                return re.sub(josa_pattern, '', text)
                
        except Exception as e:
            logger.error(f"조사 제거 오류: {e}")
            # 오류 시 간단한 패턴 매칭으로 처리
            josa_pattern = r'(을|를|이|가|은|는|에|에서|에게|으로|로|와|과|의|도|만|까지|부터|마저|조차|밖에)$'
            return re.sub(josa_pattern, '', text)
    
    def _preprocess_text(self, title: str, content: str) -> Tuple[str, bool]:
        """텍스트 전처리 및 유효성 검사"""
        # 제목 가중치 높이기 (제목을 3번 반복)
        processed_text = f"{title} {title} {title}"
        
        # 본문 내용 확인
        content_cleaned = re.sub(r'[^\w\s가-힣]', ' ', content).strip()
        is_empty_content = len(content_cleaned) < 20  # 20자 미만이면 빈 기사로 판단
        
        if not is_empty_content:
            # 본문이 있으면 추가 (최대 1000자)
            content_truncated = content[:1000] if len(content) > 1000 else content
            processed_text += f" {content_truncated}"
        else:
            # 본문이 없으면 제목만으로 처리
            self._empty_content_count += 1
            logger.warning(f"빈 본문 기사 발견: {title[:50]}...")
        
        # 특수문자 제거 및 공백 정리
        processed_text = re.sub(r'[^\w\s가-힣]', ' ', processed_text)
        processed_text = re.sub(r'\s+', ' ', processed_text).strip()
        
        return processed_text, is_empty_content
    
    async def extract_keywords(self, title: str, content: str, article_metadata: Dict = None) -> List[str]:
        """하이브리드 키워드 추출"""
        self._total_processed += 1
        
        # 텍스트 전처리
        processed_text, is_empty_content = self._preprocess_text(title, content)
        
        # 빈 기사 처리
        if is_empty_content:
            # 제목에서만 키워드 추출
            basic_keywords = await self._extract_from_title_only(title)
            return basic_keywords[:5]  # 최대 5개
        
        # 1단계: 빠른 NLP 키워드 추출
        basic_keywords = await self._extract_basic_keywords(processed_text)
        
        # 2단계: 중요 기사 판정
        if self._is_important_article(article_metadata, basic_keywords):
            if self.openai_client:
                # 3단계: LLM으로 정제
                self._llm_refined_count += 1
                refined_keywords = await self._refine_with_llm(title, content, basic_keywords)
                return self._merge_keywords(basic_keywords, refined_keywords)
            else:
                logger.warning("OpenAI 클라이언트 없음, 기본 키워드만 반환")
        
        self._basic_only_count += 1
        return basic_keywords
    
    async def _extract_from_title_only(self, title: str) -> List[str]:
        """제목에서만 키워드 추출 (빈 본문 기사용)"""
        try:
            # 형태소 분석으로 명사 추출
            morphs = self.okt.pos(title)
            keywords = []
            
            for word, pos in morphs:
                if pos in ['Noun', 'Alpha'] and len(word) >= 2:
                    keywords.append(word)
            
            # KeyBERT로 보완
            if len(keywords) < 3 and self.keybert_model:
                bert_keywords = self.keybert_model.extract_keywords(
                    title,
                    keyphrase_ngram_range=(1, 2),
                    use_mmr=True,
                    diversity=0.5,
                    top_k=5
                )
                
                for kw, _ in bert_keywords:
                    clean_kw = self._remove_josa(kw)
                    if clean_kw and clean_kw not in keywords:
                        keywords.append(clean_kw)
            
            return keywords[:5]
            
        except Exception as e:
            logger.error(f"제목 키워드 추출 오류: {e}")
            # 오류 시 단순 분할
            words = title.split()
            return [self._remove_josa(w) for w in words if len(w) >= 2][:5]
    
    async def _extract_basic_keywords(self, text: str, max_keywords: int = 8) -> List[str]:
        """기본 NLP 키워드 추출 (조사 제거 포함)"""
        stopwords = {
            '기자', '이번', '다음', '지난', '당시', '현재', '오늘', '내일', '어제',
            '그러나', '하지만', '또한', '그리고', '때문', '통해', '위해', '따라',
            '이미지', '사진', '제공', '연합뉴스', '뉴스', '기사', '오전', '오후'
        }
        
        try:
            # KeyBERT로 키워드 추출
            keywords = self.keybert_model.extract_keywords(
                text,
                keyphrase_ngram_range=(1, 3),
                stop_words=list(stopwords),
                use_mmr=True,
                diversity=0.7,
                top_k=max_keywords * 3  # 더 많이 추출 후 필터링
            )
            
            valid_keywords = []
            seen_roots = set()  # 중복 제거용
            
            for keyword, score in keywords:
                # 조사 제거
                clean_keyword = self._remove_josa(keyword.strip())
                
                # 유효성 검사
                if (clean_keyword and 
                    2 <= len(clean_keyword) <= 15 and 
                    not clean_keyword.isdigit() and
                    clean_keyword.lower() not in stopwords and
                    clean_keyword not in seen_roots):
                    
                    valid_keywords.append(clean_keyword)
                    seen_roots.add(clean_keyword.lower())
                    
                if len(valid_keywords) >= max_keywords:
                    break
            
            return valid_keywords
            
        except Exception as e:
            logger.error(f"기본 키워드 추출 오류: {e}")
            return []
    
    def _is_important_article(self, metadata: Dict, basic_keywords: List[str]) -> bool:
        """중요 기사 판정 (개선)"""
        if not metadata:
            return False
        
        # 조회수 기준
        views = metadata.get('views_count', 0)
        if views > self.important_thresholds["views_count"]:
            return True
        
        # 핫 키워드 포함 여부
        text_to_check = ' '.join(basic_keywords).lower()
        for hot_keyword in self.important_thresholds["hot_keywords"]:
            if hot_keyword in text_to_check:
                return True
        
        # 카테고리별 중요도
        category = metadata.get('category', '')
        if category in ['정치', '경제', '사회'] and len(basic_keywords) >= 4:
            return True
        
        # 댓글 수 기준 추가
        comments = metadata.get('comments_count', 0)
        if comments > 50:
            return True
        
        return False
    
    async def _refine_with_llm(self, title: str, content: str, basic_keywords: List[str]) -> List[str]:
        """LLM으로 키워드 정제 (조사 제거 강조)"""
        try:
            # 내용 길이 제한 (짧은 기사 특성 고려)
            content_truncated = content[:800] if len(content) > 800 else content
            
            prompt = f"""
다음 뉴스 기사에서 가장 중요한 키워드 5개를 추출해주세요.
기존 키워드를 참고하되, 조사를 제거한 명사 형태로만 추출하세요.

제목: {title}
내용: {content_truncated}
기존 키워드: {', '.join(basic_keywords)}

조건:
1. 조사(을, 를, 의, 에, 에서 등)를 제거한 순수 명사만
2. 2-15자 길이
3. 고유명사, 일반명사, 복합명사 모두 가능
4. 한국어로 된 키워드만
5. 쉼표로 구분하여 나열

키워드:
"""
            
            response = await self.openai_client.chat.completions.create(
                model="gpt-3.5-turbo",
                messages=[{"role": "user", "content": prompt}],
                max_tokens=150,
                temperature=0.3
            )
            
            refined_text = response.choices[0].message.content.strip()
            refined_keywords = [kw.strip() for kw in refined_text.split(',') if kw.strip()]
            
            # 추가 조사 제거 및 유효성 검사
            valid_refined = []
            for kw in refined_keywords:
                clean_kw = self._remove_josa(kw)
                if clean_kw and 2 <= len(clean_kw) <= 15 and not clean_kw.isdigit():
                    valid_refined.append(clean_kw)
            
            logger.info(f"LLM 정제 완료: {basic_keywords} → {valid_refined}")
            return valid_refined[:5]
            
        except Exception as e:
            logger.error(f"LLM 키워드 정제 오류: {e}")
            return []
    
    def _merge_keywords(self, basic_keywords: List[str], refined_keywords: List[str]) -> List[str]:
        """기본 키워드와 정제된 키워드 병합 (중복 제거 개선)"""
        merged = []
        seen = set()
        
        # 정제된 키워드 우선
        for kw in refined_keywords:
            kw_lower = kw.lower()
            if kw_lower not in seen:
                merged.append(kw)
                seen.add(kw_lower)
        
        # 기본 키워드 중 중복되지 않은 것 추가
        for kw in basic_keywords:
            kw_lower = kw.lower()
            if kw_lower not in seen and len(merged) < 8:
                merged.append(kw)
                seen.add(kw_lower)
        
        return merged
    
    def get_extraction_stats(self) -> Dict:
        """추출 통계 (모니터링용)"""
        return {
            "basic_only_count": self._basic_only_count,
            "llm_refined_count": self._llm_refined_count,
            "total_processed": self._total_processed,
            "empty_content_count": self._empty_content_count,
            "llm_usage_ratio": self._llm_refined_count / max(self._total_processed, 1)
        }
    
    async def batch_extract_keywords(self, articles: List[Dict], batch_size: int = 10) -> List[Dict]:
        """배치 처리 (대량 기사 처리용)"""
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
            
            # 동시 처리
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
