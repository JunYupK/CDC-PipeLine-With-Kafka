// src/WordCloudDashboard.js
import React, { useState, useEffect, useRef, useCallback } from 'react';
import * as d3 from 'd3';
import cloud from 'd3-cloud';
import './WordCloudDashboard.css';

const WordCloudDashboard = () => {
  // eslint-disable-next-line no-unused-vars
  const [wsConnection, setWsConnection] = useState(null); // This state is handled by wsRef, but kept for potential future use or context.
  const [connectionStatus, setConnectionStatus] = useState('disconnected');
  const [selectedWindow, setSelectedWindow] = useState('1h');
  // eslint-disable-next-line no-unused-vars
  const [selectedCategory, setSelectedCategory] = useState('all'); // Kept for future filtering implementation.
  const [isPaused, setIsPaused] = useState(false);
  const [wordCloudData, setWordCloudData] = useState({ words: [] });
  const [rankings, setRankings] = useState([]);
  const [stats, setStats] = useState({
    processed_articles: 0,
    active_keywords: 0,
    updates_received: 0,
    last_update: null
  });
  // eslint-disable-next-line no-unused-vars
  const [alerts, setAlerts] = useState([]); // Kept for future alert UI implementation.
  const [timeline, setTimeline] = useState(() => new Array(30).fill(0));
  const [theme, setTheme] = useState(() => {
    const savedTheme = localStorage.getItem('dashboard-theme');
    return savedTheme || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  });
  
  const d3Container = useRef(null);
  const wsRef = useRef(null);

 // WebSocket 메시지 처리
 const handleWebSocketMessage = useCallback((data) => {
  switch (data.type) {
    case 'wordcloud_update':
      if (data.data && data.data[selectedWindow]) {
        setWordCloudData(data.data[selectedWindow]);
        if (data.data[selectedWindow].words) {
          updateRankings(data.data[selectedWindow].words);
          
          // 타임라인 업데이트
          setTimeline(prev => {
            const count = data.data[selectedWindow].words?.length || 0;
            const newTimeline = [...(prev || []).slice(1), count];
            return newTimeline;
          });
        }
      }
      break;
      
    case 'stats_update':
      setStats(data.data);
      break;
      
    case 'new_keywords':
      // 새 키워드 알림
      if (data.data.keywords && data.data.keywords.length > 0) {
        const newAlert = {
          id: Date.now(),
          title: data.data.title,
          keywords: data.data.keywords.slice(0, 3),
          timestamp: new Date().toLocaleTimeString()
        };
        setAlerts(prev => [newAlert, ...prev.slice(0, 4)]);
      }
      break;
      
    default:
      break;
  }
}, [selectedWindow]);

useEffect(() => {
  let isMounted = true;
  let reconnectTimer = null;
  
  // 이 변수를 추가하여 WebSocket 인스턴스를 관리합니다.
  let wsInstance = null; 

  const connectWebSocket = () => {
    if (!isMounted) return;
    
    if (wsRef.current && wsRef.current.readyState !== WebSocket.CLOSED) {
      wsRef.current.close(1000, 'Reconnecting');
    }
    
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const host = process.env.REACT_APP_WEBSOCKET_HOST || '34.64.118.3:8001';
    const path = process.env.REACT_APP_WEBSOCKET_PATH || 'ws/keywords';
    const wsUrl = `${protocol}://${host}/${path}`;
    
    // wsInstance에 할당
    wsInstance = new WebSocket(wsUrl);
    wsRef.current = wsInstance;
    
    wsInstance.onopen = () => {
      if (!isMounted) {
        wsInstance.close();
        return;
      }
      setConnectionStatus('connected');
      
      if (wsInstance.readyState === WebSocket.OPEN) {
        wsInstance.send(JSON.stringify({ type: 'ping' }));
      }
    };
    
    wsInstance.onmessage = (event) => {
      if (!isMounted || isPaused) return;
      
      try {
        const data = JSON.parse(event.data);
        handleWebSocketMessage(data);
      } catch (error) {
        console.error('메시지 파싱 오류:', error);
      }
    };
    
    wsInstance.onerror = (error) => {
      console.error('WebSocket 오류:', error);
      setConnectionStatus('error');
    };
    
    wsInstance.onclose = () => {
      if (!isMounted) return;
      
      setConnectionStatus('disconnected');
      setWsConnection(null);
      
      reconnectTimer = setTimeout(() => {
        if (isMounted) {
          connectWebSocket();
        }
      }, 3000);
    };
    
    setWsConnection(wsInstance);
  };
  
  connectWebSocket();
  
  // --- [ 여기가 핵심 수정 부분 ] ---
  return () => {
    isMounted = false;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
    }
    
    // 로컬 변수 wsInstance를 사용하여 cleanup을 수행합니다.
    if (wsInstance) {
      // 이미 닫혔거나, 닫히는 중이거나, 연결 중인 상태에서는 닫지 않습니다.
      // 연결이 완전히 수립된(OPEN) 상태에서만 정상적으로 닫도록 합니다.
      // 또는 그냥 close()를 호출하되, readyState를 확인하는 것이 더 안전합니다.
      console.log(`Cleaning up WebSocket connection (readyState: ${wsInstance.readyState})`);
      // onclose 핸들러에서 재연결 로직이 실행되지 않도록 이벤트 핸들러를 null로 만듭니다.
      wsInstance.onclose = null; 
      wsInstance.close();
    }
  };
}, [isPaused, handleWebSocketMessage]);


  // 테마 변경 effect
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('dashboard-theme', theme);
  }, [theme]);
  
 



  // 랭킹 업데이트
  const updateRankings = (words) => {
    if (!words || !Array.isArray(words) || words.length === 0) {
      setRankings([]);
      return;
    }
    
    const sorted = [...words]
      .sort((a, b) => b.count - a.count)
      .slice(0, 10)
      .map((word, index) => ({
        ...word,
        rank: index + 1,
        trend: Math.random() > 0.5 ? 'up' : Math.random() > 0.5 ? 'down' : 'stable'
      }));
    
    setRankings(sorted);
  };
  
  const handleKeywordClick = useCallback((event, d) => {
    if (d && d.text) {
      // setSelectedCategory(d.text); // This line is reserved for future implementation
      console.log(`Keyword clicked: ${d.text}`);
    }
  }, []);

  // D3 워드클라우드 렌더링
  useEffect(() => {
    if (!wordCloudData || !wordCloudData.words || wordCloudData.words.length === 0 || !d3Container.current) {
      // 데이터가 없을 때 빈 상태 표시
      if (d3Container.current) {
        d3.select(d3Container.current).selectAll("*").remove();
        const svg = d3.select(d3Container.current)
          .append("svg")
          .attr("width", "100%")
          .attr("height", "100%");
        
        svg.append("text")
          .attr("x", "50%")
          .attr("y", "50%")
          .attr("text-anchor", "middle")
          .attr("dominant-baseline", "middle")
          .style("fill", "var(--text-secondary)")
          .style("font-size", "18px")
          .text("데이터를 기다리는 중...");
      }
      return;
    }

    const data = wordCloudData;
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

    // Color scale - 모던한 그라디언트 색상
    const colorScale = d3.scaleOrdinal()
      .domain([0, 1, 2, 3, 4, 5])
      .range(['#667eea', '#764ba2', '#f093fb', '#f5576c', '#4facfe', '#00f2fe']);

    // Font size scale
    const minCount = d3.min(data.words, d => d.count) || 1;
    const maxCount = d3.max(data.words, d => d.count) || 100;
    const fontSizeScale = d3.scaleLinear()
      .domain([minCount, maxCount])
      .range([16, 80]);

    // d3-cloud layout
    const layout = cloud()
      .size([width * 0.9, height * 0.9])
      .words(data.words.map(d => ({
        text: d.text,
        size: fontSizeScale(d.count),
        count: d.count,
        rank: d.rank || 0,
        color: colorScale(d.rank % 6)
      })))
      .padding(8)
      .rotate(() => (Math.random() - 0.5) * 30)
      .font("-apple-system, BlinkMacSystemFont, 'SF Pro Display', sans-serif")
      .fontSize(d => d.size)
      .on("end", draw);

    layout.start();

    function draw(words) {
      const text = g.selectAll("text")
        .data(words)
        .enter().append("text")
        .attr("class", "wordcloud-word")
        .style("font-size", d => `${d.size}px`)
        .style("font-family", "-apple-system, BlinkMacSystemFont, 'SF Pro Display', sans-serif")
        .style("font-weight", d => d.size > 40 ? "700" : "600")
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
            .style("transform", `translate(${d.x}px,${d.y}px) rotate(${d.rotate}deg) scale(1.1)`);
            
          tooltip.transition()
            .duration(200)
            .style("opacity", .95);
          tooltip.html(`<strong>${d.text}</strong><br/>빈도: ${d.count}회`)
            .style("left", (event.pageX + 10) + "px")
            .style("top", (event.pageY - 28) + "px");
        })
        .on("mouseout", function(event, d) {
          d3.select(this)
            .transition()
            .duration(200)
            .style("transform", `translate(${d.x}px,${d.y}px) rotate(${d.rotate}deg) scale(1)`);
            
          tooltip.transition()
            .duration(500)
            .style("opacity", 0);
        });

      // Animate entrance with stagger
      text.transition()
        .duration(800)
        .delay((d, i) => i * 20)
        .style("opacity", 1);
    }

    // Cleanup tooltip on unmount
    return () => {
      d3.select("body").selectAll(".d3-tooltip").remove();
    };
  }, [wordCloudData, handleKeywordClick]);

  const togglePause = () => {
    setIsPaused(!isPaused);
  };

  const changeWindow = (window) => {
    setSelectedWindow(window);
  };

  const toggleTheme = () => {
    setTheme(prev => prev === 'light' ? 'dark' : 'light');
  };

  // 숫자 포맷팅 함수
  const formatNumber = (num) => {
    if (num === undefined || num === null) return '0';
    if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`;
    if (num >= 1000) return `${(num / 1000).toFixed(1)}K`;
    return num.toLocaleString();
  };

  return (
    <div className="dashboard">
      <div className="controls">
        <div className="controls-left">
          <h1>실시간 키워드 트렌드</h1>
          <div className="window-toggle">
            <button 
              className={`window-btn ${selectedWindow === '30min' ? 'active' : ''}`}
              onClick={() => changeWindow('30min')}
            >
              30분
            </button>
            <button 
              className={`window-btn ${selectedWindow === '1h' ? 'active' : ''}`}
              onClick={() => changeWindow('1h')}
            >
              1시간
            </button>
            <button 
              className={`window-btn ${selectedWindow === '6h' ? 'active' : ''}`}
              onClick={() => changeWindow('6h')}
            >
              6시간
            </button>
          </div>
        </div>
        <div className="controls-right">
          <span className={`connection-status ${connectionStatus}`}>
            {connectionStatus === 'connected' ? '● 실시간' : '○ 오프라인'}
          </span>
          <button className="theme-toggle" onClick={toggleTheme} title="테마 변경">
            {theme === 'light' ? '🌙' : '☀️'}
          </button>
          <button className={`play-pause ${isPaused ? 'paused' : ''}`} onClick={togglePause}>
            {isPaused ? '▶ 재개' : '⏸ 일시정지'}
          </button>
        </div>
      </div>

      <div className="wordcloud-container" ref={d3Container}></div>

      <div className="sidebar">
        <div className="ranking">
          <h3>인기 키워드</h3>
          <div className="ranking-list">
            {rankings && rankings.length > 0 ? (
              rankings.map((item) => (
                <div key={item.text} className="ranking-item">
                  <span className="rank-number">{item.rank}</span>
                  <div className="keyword-info">
                    <div className="keyword-name">{item.text}</div>
                    <div className="keyword-count">{formatNumber(item.count)}회</div>
                  </div>
                  <span className={`trend-arrow trend-${item.trend}`}>
                    {item.trend === 'up' ? '↑' : item.trend === 'down' ? '↓' : '—'}
                  </span>
                </div>
              ))
            ) : (
              <div style={{ textAlign: 'center', color: 'var(--text-secondary)', padding: '20px' }}>
                데이터를 기다리는 중...
              </div>
            )}
          </div>
        </div>

        <div className="status">
          <h3>실시간 통계</h3>
          <div className="stat-item">
            <div className="stat-label">처리된 기사</div>
            <div className="stat-value">{formatNumber(stats.processed_articles)}</div>
          </div>
          <div className="stat-item">
            <div className="stat-label">활성 키워드</div>
            <div className="stat-value">{formatNumber(stats.active_keywords)}</div>
          </div>
          <div className="stat-item">
            <div className="stat-label">업데이트</div>
            <div className="stat-value">{formatNumber(stats.updates_received)}</div>
          </div>
        </div>
      </div>

      <div className="timeline">
        <h3>키워드 추이</h3>
        <div className="chart-container">
          <svg width="100%" height="100%" viewBox="0 0 800 100" preserveAspectRatio="none">
            <defs>
              <linearGradient id="gradient" x1="0%" y1="0%" x2="0%" y2="100%">
                <stop offset="0%" stopColor="#667eea" stopOpacity="0.8"/>
                <stop offset="100%" stopColor="#667eea" stopOpacity="0.1"/>
              </linearGradient>
            </defs>
            <path
              fill="url(#gradient)"
              stroke="#667eea"
              strokeWidth="3"
              d={timeline && timeline.length > 0 
                ? `M ${timeline.map((val, i) => 
                    `${(i / (timeline.length - 1)) * 800},${100 - ((val || 0) / Math.max(...timeline.filter(v => v !== undefined), 1)) * 90}`
                  ).join(' L ')} L 800,100 L 0,100 Z`
                : 'M 0,100 L 800,100 Z'
              }
            />
          </svg>
        </div>
      </div>
    </div>
  );
};

export default WordCloudDashboard;