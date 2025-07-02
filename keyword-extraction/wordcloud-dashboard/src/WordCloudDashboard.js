// WordCloudDashboard.js - 기존 CSS에 맞는 구조
import React, { useState, useEffect, useRef, useCallback } from 'react';
import * as d3 from 'd3';
import './WordCloudDashboard.css';

const WordCloudDashboard = () => {
  const [connectionStatus, setConnectionStatus] = useState('disconnected');
  const [selectedWindow, setSelectedWindow] = useState('5min');
  const [selectedCategory, setSelectedCategory] = useState('all');
  const [isPaused, setIsPaused] = useState(false);
  const [wordCloudData, setWordCloudData] = useState({});
  const [rankings, setRankings] = useState([]);
  const [stats, setStats] = useState({
    processed_articles: 0,
    active_keywords: 0,
    updates_received: 0,
    last_update: null
  });
  const [timeline, setTimeline] = useState(Array(30).fill(0));
  
  const d3Container = useRef(null);
  const wsRef = useRef(null);

  // D3 워드클라우드 렌더링
  const renderD3WordCloud = useCallback((data) => {
    if (!d3Container.current || !data || !data.words) return;

    console.log('🎨 D3 렌더링 시작:', data.words.length, '개 단어');

    const words = data.words;
    const width = d3Container.current.clientWidth;
    const height = d3Container.current.clientHeight;

    // 기존 SVG 제거
    d3.select(d3Container.current).selectAll("*").remove();

    const svg = d3.select(d3Container.current)
      .append('svg')
      .attr('width', width)
      .attr('height', height);

    const color = d3.scaleOrdinal(d3.schemeCategory10);
    const maxCount = d3.max(words, d => d.count) || 1;
    const fontScale = d3.scaleLinear()
      .domain([1, maxCount])
      .range([16, 60]);

    // 그리드 레이아웃
    const cols = Math.ceil(Math.sqrt(words.length));
    const rows = Math.ceil(words.length / cols);
    const cellWidth = width / cols;
    const cellHeight = height / rows;

    svg.selectAll('text')
      .data(words)
      .enter()
      .append('text')
      .text(d => d.text)
      .attr('font-size', d => fontScale(d.count))
      .attr('fill', (d, i) => color(i))
      .attr('text-anchor', 'middle')
      .attr('dominant-baseline', 'middle')
      .attr('x', (d, i) => (i % cols) * cellWidth + cellWidth / 2)
      .attr('y', (d, i) => Math.floor(i / cols) * cellHeight + cellHeight / 2)
      .attr('opacity', 0)
      .style('cursor', 'pointer')
      .style('font-weight', 'bold')
      .on('mouseover', function(event, d) {
        d3.select(this)
          .transition()
          .duration(200)
          .attr('font-size', fontScale(d.count) * 1.3)
          .style('text-shadow', '2px 2px 4px rgba(0,0,0,0.5)');
      })
      .on('mouseout', function(event, d) {
        d3.select(this)
          .transition()
          .duration(200)
          .attr('font-size', fontScale(d.count))
          .style('text-shadow', 'none');
      })
      .transition()
      .duration(1000)
      .attr('opacity', 1);

    console.log('✅ D3 렌더링 완료');
  }, []);

  // 현재 선택된 윈도우 데이터 표시
  useEffect(() => {
    const currentData = wordCloudData[selectedWindow];
    if (currentData && currentData.words && currentData.words.length > 0) {
      console.log(`🔄 ${selectedWindow} 윈도우 데이터 표시:`, currentData.words.length, '개');
      renderD3WordCloud(currentData);
      updateRankings(currentData.words);
    }
  }, [selectedWindow, wordCloudData, renderD3WordCloud]);

  // WebSocket 연결
  useEffect(() => {
    let isMounted = true;
    let reconnectTimer = null;
    
    const connectWebSocket = () => {
      if (!isMounted) return;
      
      if (wsRef.current?.readyState !== WebSocket.CLOSED) {
        wsRef.current?.close();
      }
      
      console.log('🔌 WebSocket 연결 시도...');
      const ws = new WebSocket('ws://localhost:8001/ws/keywords');
      wsRef.current = ws;
      
      ws.onopen = () => {
        if (!isMounted) return;
        setConnectionStatus('connected');
        console.log('✅ WebSocket 연결 성공');
      };
      
      ws.onmessage = (event) => {
        if (!isMounted || isPaused) return;
        
        try {
          const data = JSON.parse(event.data);
          console.log('📨 메시지 수신:', data.type);
          
          switch (data.type) {
            case 'wordcloud_update':
              console.log('📊 워드클라우드 업데이트:', Object.keys(data.data));
              setWordCloudData(data.data);
              setStats(prev => ({ 
                ...prev, 
                updates_received: prev.updates_received + 1,
                last_update: new Date().toISOString()
              }));
              break;
              
            case 'initial_trending':
              console.log('🔥 초기 트렌딩 데이터');
              updateRankingsFromTrending(data.data);
              break;
              
            case 'stats_update':
              console.log('📈 통계 업데이트');
              setStats(prev => ({ ...prev, ...data.data }));
              break;
              
            case 'ping':
              ws.send(JSON.stringify({ type: 'pong' }));
              break;
          }
        } catch (error) {
          console.error('❌ 메시지 파싱 오류:', error);
        }
      };
      
      ws.onerror = (error) => {
        console.error('❌ WebSocket 오류:', error);
        setConnectionStatus('error');
      };
      
      ws.onclose = (event) => {
        console.log('🔌 WebSocket 종료:', event.code);
        setConnectionStatus('disconnected');
        
        if (isMounted && event.code !== 1000) {
          reconnectTimer = setTimeout(connectWebSocket, 5000);
        }
      };
    };
    
    connectWebSocket();
    
    return () => {
      isMounted = false;
      clearTimeout(reconnectTimer);
      wsRef.current?.close();
    };
  }, [isPaused]);

  const updateRankings = (words) => {
    const topWords = words.slice(0, 10);
    setRankings(topWords.map((word, idx) => ({
      rank: idx + 1,
      keyword: word.text,
      count: word.count,
      trend: Math.random() > 0.5 ? 'up' : 'down'
    })));
  };

  const updateRankingsFromTrending = (trendingData) => {
    setRankings(trendingData.slice(0, 10).map((item, idx) => ({
      rank: idx + 1,
      keyword: item.keyword,
      count: item.count,
      trend: item.trend === 'rising' ? 'up' : 'down'
    })));
  };

  // 테스트 데이터 생성
  const generateTestData = () => {
    const testWords = [
      { text: '테스트', count: 25 },
      { text: 'React', count: 20 },
      { text: 'WebSocket', count: 18 },
      { text: '실시간', count: 15 },
      { text: '워드클라우드', count: 12 },
      { text: 'D3.js', count: 10 },
      { text: '키워드', count: 8 },
      { text: '분석', count: 6 }
    ];

    const testData = {
      '1min': { words: testWords.slice(0, 4) },
      '5min': { words: testWords.slice(0, 6) },
      '15min': { words: testWords }
    };

    console.log('🧪 테스트 데이터 생성');
    setWordCloudData(testData);
    
    // 타임라인 테스트 데이터
    setTimeline(Array(30).fill(0).map(() => Math.random() * 100));
  };

  return (
    <div className="dashboard">
      {/* Controls */}
      <div className="controls">
        <div className="controls-left">
          <h1>🚀 실시간 키워드 분석</h1>
          
          <div className="window-toggle">
            {['1min', '5min', '15min'].map(window => (
              <button
                key={window}
                className={`window-btn ${selectedWindow === window ? 'active' : ''}`}
                onClick={() => {
                  console.log(`🔄 윈도우 변경: ${selectedWindow} → ${window}`);
                  setSelectedWindow(window);
                }}
              >
                {window}
              </button>
            ))}
          </div>
          
          <div className="category-filter">
            <select 
              value={selectedCategory} 
              onChange={(e) => setSelectedCategory(e.target.value)}
            >
              <option value="all">전체</option>
              <option value="politics">정치</option>
              <option value="economy">경제</option>
              <option value="society">사회</option>
            </select>
          </div>
        </div>

        <div className="controls-right">
          <button 
            onClick={() => setIsPaused(!isPaused)}
            className={`play-pause ${isPaused ? 'paused' : ''}`}
          >
            {isPaused ? '▶️ 재생' : '⏸️ 일시정지'}
          </button>
          
          <button 
            onClick={generateTestData}
            style={{
              background: '#9C27B0',
              border: 'none',
              color: 'white',
              padding: '10px 20px',
              borderRadius: '8px',
              cursor: 'pointer'
            }}
          >
            🧪 테스트
          </button>
        </div>
      </div>

      {/* WordCloud */}
      <div className="wordcloud-area">
        <div className="connection-indicator">
          <div className={`connection-dot ${connectionStatus}`}></div>
          {connectionStatus === 'connected' ? '실시간 연결됨' : '연결 중...'}
        </div>
        <div ref={d3Container} className="d3-container"></div>
      </div>

      {/* Ranking */}
      <div className="ranking">
        <h3>🔥 실시간 TOP 10</h3>
        <ul className="ranking-list">
          {rankings.map((item) => (
            <li key={item.keyword} className="ranking-item">
              <span className="rank-number">{item.rank}</span>
              <div className="keyword-info">
                <div className="keyword-name">{item.keyword}</div>
                <div className="keyword-count">{item.count}회</div>
              </div>
              <span className={`trend-arrow trend-${item.trend}`}>
                {item.trend === 'up' ? '↗️' : item.trend === 'down' ? '↘️' : '➡️'}
              </span>
            </li>
          ))}
        </ul>
      </div>

      {/* Status */}
      <div className="status">
        <h3>📈 시스템 상태</h3>
        <div className="stat-item">
          <div className="stat-label">처리된 기사</div>
          <div className="stat-value">{stats.processed_articles.toLocaleString()}</div>
        </div>
        <div className="stat-item">
          <div className="stat-label">활성 키워드</div>
          <div className="stat-value">{stats.active_keywords}</div>
        </div>
        <div className="stat-item">
          <div className="stat-label">업데이트</div>
          <div className="stat-value">{stats.updates_received}</div>
        </div>
        <div className="stat-item">
          <div className="stat-label">연결 상태</div>
          <div className="stat-value" style={{ color: connectionStatus === 'connected' ? '#10b981' : '#ef4444' }}>
            {connectionStatus === 'connected' ? 'LIVE' : 'OFFLINE'}
          </div>
        </div>
      </div>

      {/* Timeline */}
      <div className="timeline">
        <h3>⏱️ 키워드 활동 타임라인 (최근 30분)</h3>
        <div className="chart-container">
          {timeline.map((height, idx) => (
            <div 
              key={idx} 
              className="chart-bar" 
              style={{ height: `${height}%` }}
            ></div>
          ))}
        </div>
      </div>
    </div>
  );
};

export default WordCloudDashboard;