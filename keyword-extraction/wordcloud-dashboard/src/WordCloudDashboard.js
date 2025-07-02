// src/WordCloudDashboard.js
import React, { useState, useEffect, useRef, useCallback } from 'react';
import * as d3 from 'd3';
import cloud from 'd3-cloud';
import './WordCloudDashboard.css';

const WordCloudDashboard = () => {
  const [wsConnection, setWsConnection] = useState(null);
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
  const [alerts, setAlerts] = useState([]);
  const [timeline, setTimeline] = useState(Array(30).fill(0));
  
  const d3Container = useRef(null);
  const wsRef = useRef(null);
  const currentDataRef = useRef({});

  // WebSocket 연결 설정
  useEffect(() => {
    let isMounted = true;
    let reconnectTimer = null;
    
    const connectWebSocket = () => {
      if (!isMounted) return;
      
      if (wsRef.current && wsRef.current.readyState !== WebSocket.CLOSED) {
        wsRef.current.close(1000, 'Reconnecting');
      }
      const wsUrl = process.env.REACT_APP_WEBSOCKET_URL;
      console.log('WebSocket 연결 시도...');
      const ws = new WebSocket(wsUrl);
      wsRef.current = ws;
      
      ws.onopen = () => {
        if (!isMounted) {
          ws.close();
          return;
        }
        setConnectionStatus('connected');
        console.log('WebSocket 연결 성공');
        
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'ping' }));
        }
      };
      
      ws.onmessage = (event) => {
        if (!isMounted) return;
        
        try {
          const data = JSON.parse(event.data);
          
          if (data.type === 'ping') {
            if (ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify({ type: 'pong' }));
            }
            return;
          }
          
          // 일시정지 상태가 아닐 때만 메시지 처리
          if (!isPaused) {
            handleWebSocketMessage(data);
          }
        } catch (error) {
          console.error('메시지 파싱 오류:', error);
        }
      };
      
      ws.onerror = (error) => {
        console.error('WebSocket 오류:', error);
        if (isMounted) {
          setConnectionStatus('error');
        }
      };
      
      ws.onclose = (event) => {
        console.log('WebSocket 종료:', event.code, event.reason);
        
        if (!isMounted) return;
        
        setConnectionStatus('disconnected');
        
        if (event.code !== 1000 && event.code !== 1001) {
          console.log('5초 후 재연결 시도...');
          reconnectTimer = setTimeout(() => {
            if (isMounted) {
              connectWebSocket();
            }
          }, 5000);
        }
      };
      
      setWsConnection(ws);
    };
    
    connectWebSocket();
    
    return () => {
      isMounted = false;
      
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
      }
      
      if (wsRef.current) {
        console.log('WebSocket 정리 중...');
        wsRef.current.close(1000, 'Component unmounting');
        wsRef.current = null;
      }
    };
  }, [isPaused]); // isPaused 의존성 추가
  // D3 WordCloud 렌더링
  const renderD3WordCloud = useCallback((data) => {
    if (!d3Container.current || !data.words || data.words.length === 0) return;

    const width = d3Container.current.clientWidth;
    const height = d3Container.current.clientHeight;

    // Clear previous
    d3.select(d3Container.current).selectAll("*").remove();

    const svg = d3.select(d3Container.current)
      .append("svg")
      .attr("width", width)
      .attr("height", height);

    const g = svg.append("g")
      .attr("transform", `translate(${width/2},${height/2})`);

    // Tooltip
    const tooltip = d3.select("body").append("div")
      .attr("class", "d3-tooltip")
      .style("opacity", 0);

    // Color scale
    const colorScale = d3.scaleOrdinal()
      .domain([0, 1, 2, 3, 4, 5])
      .range(['#4f46e5', '#06b6d4', '#10b981', '#f59e0b', '#ef4444', '#a855f7']);

    // Font size scale
    const fontSizeScale = d3.scaleLinear()
      .domain([
        d3.min(data.words, d => d.count),
        d3.max(data.words, d => d.count)
      ])
      .range([14, 60]);

    // d3-cloud layout
    const layout = cloud()
      .size([width, height])
      .words(data.words.map(d => ({
        text: d.text,
        size: fontSizeScale(d.count),
        count: d.count,
        rank: d.rank,
        color: colorScale(d.rank % 6)
      })))
      .padding(5)
      .rotate(() => (Math.random() - 0.5) * 60)
      .font("Arial")
      .fontSize(d => d.size)
      .on("end", draw);

    layout.start();

    function draw(words) {
      const text = g.selectAll("text")
        .data(words)
        .enter().append("text")
        .style("font-size", d => `${d.size}px`)
        .style("font-family", "Arial")
        .style("font-weight", "bold")
        .style("fill", d => d.color)
        .style("cursor", "pointer")
        .attr("text-anchor", "middle")
        .attr("transform", d => `translate(${d.x},${d.y})rotate(${d.rotate})`)
        .text(d => d.text)
        .style("opacity", 0)
        .on("click", handleKeywordClick)
        .on("mouseover", function(event, d) {
          d3.select(this)
            .transition()
            .duration(200)
            .style("font-size", `${d.size * 1.2}px`);
          
          tooltip.transition()
            .duration(200)
            .style("opacity", 0.9);
          
          tooltip.html(`${d.text}<br/>Count: ${d.count}<br/>Rank: #${d.rank}`)
            .style("left", (event.pageX + 10) + "px")
            .style("top", (event.pageY - 28) + "px");
        })
        .on("mouseout", function(event, d) {
          d3.select(this)
            .transition()
            .duration(200)
            .style("font-size", `${d.size}px`);
          
          tooltip.transition()
            .duration(500)
            .style("opacity", 0);
        });

      // Fade in animation
      text.transition()
        .duration(1000)
        .style("opacity", 1);
    }

    // Cleanup tooltip on component unmount
    return () => {
      d3.select("body").selectAll(".d3-tooltip").remove();
    };
  }, []);
  // WebSocket 메시지 처리
  const handleWebSocketMessage = useCallback((data) => {
    console.log('[WebSocket] 메시지 수신:', data);
    switch (data.type) {
      case 'wordcloud_update':
        // 🔥 중요: 데이터는 저장하되, 1분 윈도우일 때만 자동 렌더링
        const newData = { ...currentDataRef.current };
        Object.keys(data.data).forEach(window => {
          if (data.data[window]) {
            newData[window] = data.data[window];
          }
        });
        
        currentDataRef.current = newData;
        setWordCloudData(newData);
        
        console.log(`[WebSocket] 현재 선택된 윈도우: ${selectedWindow}`);
        console.log('[WebSocket] 렌더링 할 데이터:', newData[selectedWindow]);

        // 1분 윈도우를 선택했을 때만 실시간 업데이트 반영
        if (selectedWindow === '1min' && newData['1min']) {
          console.log('%c[WebSocket] 1분 윈도우 렌더링 조건 통과!', 'color: green; font-weight: bold;');
          updateRankings(newData['1min'].words || []);
          renderD3WordCloud(newData['1min']);
        }
        
        // 통계는 항상 업데이트
        setStats(prev => ({
          ...prev,
          updates_received: prev.updates_received + 1,
          active_keywords: currentDataRef.current[selectedWindow]?.words?.length || 0
        }));
        break;
      case 'new_keywords':
        handleNewKeywords(data.data);
        break;
      case 'initial_trending':
        updateRankingsFromTrending(data.data);
        break;
      case 'stats_update':
        setStats(prev => ({ ...prev, ...data.data }));
        break;
      default:
        break;
    }
  }, [selectedWindow, renderD3WordCloud]);

  // 랭킹 업데이트
  const updateRankings = (words) => {
    const topWords = words.slice(0, 10);
    setRankings(topWords.map((word, idx) => ({
      rank: idx + 1,
      keyword: word.text,
      count: word.count,
      trend: Math.random() > 0.5 ? 'up' : Math.random() > 0.5 ? 'down' : 'stable'
    })));
  };

  const updateRankingsFromTrending = (trendingData) => {
    setRankings(trendingData.slice(0, 10).map((item, idx) => ({
      rank: idx + 1,
      keyword: item.keyword,
      count: item.count,
      trend: item.trend === 'rising' ? 'up' : item.trend === 'falling' ? 'down' : 'stable'
    })));
  };



  const handleKeywordClick = (event, d) => {
    console.log('Keyword clicked:', d.text);
    // TODO: 키워드 상세 분석 모달 표시
  };

  const handleNewKeywords = (data) => {
    setStats(prev => ({
      ...prev,
      processed_articles: prev.processed_articles + 1,
      last_update: new Date().toISOString()
    }));
    
    // Update timeline
    setTimeline(prev => {
      const newTimeline = [...prev.slice(1), Math.random() * 100];
      return newTimeline;
    });
  };

  // 시간 윈도우 변경 시 즉시 렌더링
  useEffect(() => {
    if (currentDataRef.current[selectedWindow]) {
      renderD3WordCloud(currentDataRef.current[selectedWindow]);
      updateRankings(currentDataRef.current[selectedWindow].words || []);
    }
  }, [selectedWindow, renderD3WordCloud]);

  // 윈도우 리사이즈 핸들링
  useEffect(() => {
    const handleResize = () => {
      if (currentDataRef.current[selectedWindow]) {
        renderD3WordCloud(currentDataRef.current[selectedWindow]);
      }
    };

    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [selectedWindow, renderD3WordCloud]);

  return (
    <div className="dashboard">
      {/* Controls */}
      <div className="controls">
        <div className="controls-left">
          <h1>📊 실시간 키워드 트렌드</h1>
          <div className="window-toggle">
            {['1min', '5min', '15min'].map(window => (
              <button
                key={window}
                className={`window-btn ${selectedWindow === window ? 'active' : ''}`}
                onClick={() => setSelectedWindow(window)}
              >
                {window === '1min' ? '1분' : window === '5min' ? '5분' : '15분'}
              </button>
            ))}
          </div>
        </div>
        <div className="controls-right">
          <div className="category-filter">
            <select value={selectedCategory} onChange={(e) => setSelectedCategory(e.target.value)}>
              <option value="all">전체 카테고리</option>
              <option value="politics">정치</option>
              <option value="economy">경제</option>
              <option value="society">사회</option>
              <option value="tech">IT과학</option>
            </select>
          </div>
          <button 
            className={`play-pause ${isPaused ? 'paused' : ''}`} 
            onClick={() => setIsPaused(!isPaused)}
          >
            {isPaused ? '▶️ 재생' : '⏸️ 일시정지'}
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