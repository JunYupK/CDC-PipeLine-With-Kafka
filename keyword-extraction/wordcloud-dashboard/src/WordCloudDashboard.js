// src/WordCloudDashboard.js
import React, { useState, useEffect, useRef, useCallback } from 'react';
import * as d3 from 'd3';
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

  // WebSocket 연결
  useEffect(() => {
    const connectWebSocket = () => {
      const ws = new WebSocket('ws://localhost:8001/ws/keywords');
      wsRef.current = ws;
      
      ws.onopen = () => {
        setConnectionStatus('connected');
        console.log('WebSocket connected');
      };
      
      ws.onmessage = (event) => {
        if (isPaused) return;
        
        const data = JSON.parse(event.data);
        handleWebSocketMessage(data);
      };
      
      ws.onerror = (error) => {
        console.error('WebSocket error:', error);
        setConnectionStatus('error');
      };
      
      ws.onclose = () => {
        setConnectionStatus('disconnected');
        setTimeout(connectWebSocket, 5000);
      };
      
      setWsConnection(ws);
    };
    
    connectWebSocket();
    
    return () => {
      if (wsRef.current) {
        wsRef.current.close();
      }
    };
  }, []);

  const handleWebSocketMessage = (data) => {
    switch (data.type) {
      case 'wordcloud_update':
        updateWordCloud(data.data);
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
  };

  const updateWordCloud = (data) => {
    if (data[selectedWindow]) {
      setWordCloudData(prev => ({ ...prev, [selectedWindow]: data[selectedWindow] }));
      updateRankings(data[selectedWindow].words || []);
      renderD3WordCloud(data[selectedWindow]);
    }
  };

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

  const renderD3WordCloud = useCallback((data) => {
    if (!d3Container.current || !data.words) return;

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

    // Force simulation for organic layout
    const simulation = d3.forceSimulation(data.words)
      .force("charge", d3.forceManyBody().strength(-50))
      .force("center", d3.forceCenter(0, 0))
      .force("collision", d3.forceCollide().radius(d => d.size * 0.8));

    const words = g.selectAll("text")
      .data(data.words)
      .enter().append("text")
      .style("font-size", d => `${d.size}px`)
      .style("font-weight", "bold")
      .style("fill", d => d.color)
      .style("cursor", "pointer")
      .attr("text-anchor", "middle")
      .text(d => d.text)
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

    // Animation
    simulation.on("tick", () => {
      words
        .attr("x", d => d.x)
        .attr("y", d => d.y);
    });

    // New keyword animation
    words
      .filter(d => d.animation === "pulse")
      .style("opacity", 0)
      .transition()
      .duration(1000)
      .style("opacity", 1);

  }, [selectedWindow]);

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

  // Re-render wordcloud on window resize
  useEffect(() => {
    const handleResize = () => {
      if (wordCloudData[selectedWindow]) {
        renderD3WordCloud(wordCloudData[selectedWindow]);
      }
    };

    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [wordCloudData, selectedWindow, renderD3WordCloud]);

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