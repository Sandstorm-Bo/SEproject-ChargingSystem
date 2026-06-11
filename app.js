/* =====================================================================
   ION · 共享前端逻辑：服务器配置 / WebSocket / 格式化 / Toast / 时钟
   三个页面共用，挂载到全局 window.ION
   ===================================================================== */
(function () {
  const SRV_KEY = 'serverBase';

  function serverBase() {
    return localStorage.getItem(SRV_KEY) || 'http://localhost:8080';
  }
  function setServer(v) {
    v = (v || '').trim();
    if (v) {
      if (!/^https?:\/\//i.test(v)) v = 'http://' + v;
      localStorage.setItem(SRV_KEY, v.replace(/\/+$/, ''));
    } else {
      localStorage.removeItem(SRV_KEY);
    }
  }
  function api(path) { return serverBase() + '/api' + path; }

  /* ---- 格式化 ---- */
  const fmt = {
    num(v, d = 0) {
      if (v == null || isNaN(v)) return '0';
      return Number(v).toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d });
    },
    money(v) {
      if (v == null || isNaN(v)) return '0.00';
      return Number(v).toFixed(2);
    },
    dur(min) { // 分钟 -> 时长
      if (!min || isNaN(min)) return '0h';
      const h = min / 60;
      return h >= 1 ? h.toFixed(1) + 'h' : Math.round(min) + 'm';
    },
    pileType(t) { return t === 'FAST' ? '快充' : '慢充'; },
    pileTypeMeta(t, power) {
      return (t === 'FAST' ? '快充' : '慢充') + ' · ' + (power || (t === 'FAST' ? 30 : 10)) + '度/h';
    },
    carState(s) {
      return ({
        WAITING: '等候区', CALLED: '已叫号', QUEUED_AT_PILE: '桩前排队',
        CHARGING: '充电中', FINISHED: '已完成', CANCELLED: '已取消', INTERRUPTED: '故障中断', IDLE: '空闲'
      })[s] || s || '—';
    },
    period(p) { return ({ PEAK: '峰时', FLAT: '平时', VALLEY: '谷时' })[p] || '—'; },
    periodClass(p) { return ({ PEAK: 'peak', FLAT: 'flat', VALLEY: 'valley' })[p] || 'flat'; }
  };

  /* ---- Toast ---- */
  function toast(msg, type = 'info') {
    let wrap = document.querySelector('.toast-wrap');
    if (!wrap) { wrap = document.createElement('div'); wrap.className = 'toast-wrap'; document.body.appendChild(wrap); }
    const el = document.createElement('div');
    const ic = type === 'success' ? '✓' : type === 'error' ? '!' : 'i';
    el.className = 'toast ' + type;
    el.innerHTML = `<div class="ic">${ic}</div><div>${msg}</div>`;
    wrap.appendChild(el);
    setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 300); }, 3200);
  }

  /* ---- 时钟 ---- */
  function startClock(el) {
    const node = typeof el === 'string' ? document.getElementById(el) : el;
    if (!node) return;
    const tick = () => {
      const d = new Date();
      const p = (n) => String(n).padStart(2, '0');
      node.textContent = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
    };
    tick(); setInterval(tick, 1000);
  }

  /* ---- 服务器配置栏：需要 .srv-pill / #srvInput / #srvPanel / #srvText ---- */
  let _barNudged = false;
  function nudgeServerBar() {
    // 懒人启动：自动弹开地址栏，引导先填服务器 IP（只弹一次，避免重连时反复打扰）
    const panel = document.getElementById('srvPanel');
    if (panel && !_barNudged) {
      panel.classList.add('show');
      _barNudged = true;
      const input = document.getElementById('srvInput');
      if (input) setTimeout(() => input.focus(), 100);
    }
  }
  function initServerBar() {
    const input = document.getElementById('srvInput');
    if (input) input.value = serverBase();
    const pill = document.querySelector('.srv-pill');
    const panel = document.getElementById('srvPanel');
    if (pill && panel) pill.addEventListener('click', () => panel.classList.toggle('show'));
    // 首次打开（从未配置过服务器）自动展开地址栏
    if (!localStorage.getItem(SRV_KEY)) nudgeServerBar();
  }
  function applyServer() {
    const input = document.getElementById('srvInput');
    setServer(input ? input.value : '');
    location.reload();
  }
  function setConnState(ok) {
    const pill = document.querySelector('.srv-pill');
    const txt = document.getElementById('srvText');
    if (pill) { pill.classList.toggle('ok', !!ok); pill.classList.toggle('bad', ok === false); }
    if (txt) txt.textContent = ok ? '已连接' : ok === false ? '连接失败' : '未连接';
    const lb = document.getElementById('liveBadge');
    if (lb) lb.style.opacity = ok ? '1' : '.4';
    // 连不上时自动弹开地址栏，提示检查/重填服务器 IP
    if (ok === false) nudgeServerBar();
  }

  /* ---- WebSocket 状态流 ---- */
  function connectStatus(onData) {
    function open() {
      try {
        const socket = new SockJS(serverBase() + '/api/ws');
        const client = Stomp.over(socket);
        client.debug = null;
        client.connect({}, () => {
          setConnState(true);
          client.subscribe('/topic/status', (msg) => {
            try { onData(JSON.parse(msg.body)); } catch (e) { /* ignore */ }
          });
        }, () => { setConnState(false); setTimeout(open, 3000); });
      } catch (e) { setConnState(false); setTimeout(open, 3000); }
    }
    open();
  }

  async function post(path, params) {
    const qs = params ? '?' + new URLSearchParams(params).toString() : '';
    const r = await fetch(api(path) + qs, { method: 'POST' });
    try { return await r.json(); } catch (e) { return { success: r.ok }; }
  }
  async function postJson(path, body) {
    const r = await fetch(api(path), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    return r.json();
  }
  async function getJson(path) {
    const r = await fetch(api(path));
    return r.json();
  }

  window.ION = {
    serverBase, setServer, api, fmt, toast, startClock,
    initServerBar, applyServer, setConnState, connectStatus,
    post, postJson, getJson
  };
})();
