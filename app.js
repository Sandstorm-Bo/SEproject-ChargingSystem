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

  /* ---- 时钟 ----
     优先显示服务器广播的仿真时间（/topic/status 的 simTime，随倍速流逝）；
     3 秒内没有新广播则回退为本机真实时间。 */
  let _simTime = null, _simTimeAt = 0;
  function noteSimTime(t) {
    if (t) { _simTime = t; _simTimeAt = Date.now(); }
  }
  function startClock(el) {
    const node = typeof el === 'string' ? document.getElementById(el) : el;
    if (!node) return;
    const tick = () => {
      if (_simTime && Date.now() - _simTimeAt < 3000) {
        node.textContent = _simTime;
        node.title = '仿真时间';
        return;
      }
      const d = new Date();
      const p = (n) => String(n).padStart(2, '0');
      node.textContent = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
      node.title = '本机时间';
    };
    tick(); setInterval(tick, 500);
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
            try {
              const data = JSON.parse(msg.body);
              noteSimTime(data.simTime);
              onData(data);
            } catch (e) { /* ignore */ }
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
  async function put(path, params) {
    const qs = params ? '?' + new URLSearchParams(params).toString() : '';
    const r = await fetch(api(path) + qs, { method: 'PUT' });
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

  /* ---- 管理员登录门（调度盘 / 运维控制台共用，YL-024/025/026/027） ----
     登录态存 localStorage('ionAdmin')，仅 role=ADMIN 的账号可进入管理页面；
     用 localStorage（而非 sessionStorage）使登录态在调度盘/运维控制台之间、
     以及新标签页中均保持，切换界面无需重复登录；浮层与"管理员·退出"徽标
     由本函数动态注入，页面只需调用 ION.adminGate()。 */
  function adminGate() {
    let admin = null;
    try { admin = JSON.parse(localStorage.getItem('ionAdmin') || 'null'); } catch (e) { admin = null; }

    // 右上浮动徽标（登录后显示）
    const badge = document.createElement('div');
    badge.style.cssText = 'position:fixed;right:18px;bottom:18px;z-index:1400;display:none;align-items:center;gap:8px;';
    badge.innerHTML = `<span class="pill idle"><span class="dot"></span><span id="admWho"></span></span>
      <button class="btn btn-ghost btn-sm" id="admLogout">退出管理</button>`;
    document.body.appendChild(badge);
    badge.querySelector('#admLogout').addEventListener('click', () => {
      if (!confirm('退出管理员登录？')) return;
      sessionStorage.removeItem('ionAdmin');
      localStorage.removeItem('ionAdmin');
      location.reload();
    });

    // 登录浮层
    const mask = document.createElement('div');
    mask.className = 'mask';
    mask.innerHTML = `<div class="modal" style="max-width:380px;">
      <div class="modal-head"><h3 id="admTitle">🛡 管理员登录</h3><p>管理员客户端需登录后操作（启停桩 / 报表 / 调度 / 策略）</p></div>
      <div class="auth-tabs">
        <button class="btn btn-primary" id="admTabL">登录</button>
        <button class="btn btn-ghost" id="admTabR">注册管理员</button>
      </div>
      <div class="field"><div class="field-label">管理员用户名</div>
        <input type="text" class="input mono" id="admUser" maxlength="30" placeholder="如 admin"></div>
      <div class="field"><div class="field-label">密码 <span class="hint">至少 4 位</span></div>
        <input type="password" class="input mono" id="admPass" maxlength="50" placeholder="••••••"></div>
      <div class="modal-foot"><button class="btn btn-primary btn-block" id="admGo">登 录</button></div>
    </div>`;
    document.body.appendChild(mask);

    let mode = 'login';
    const q = s => mask.querySelector(s);
    function setMode(m) {
      mode = m;
      q('#admTabL').className = 'btn ' + (m === 'login' ? 'btn-primary' : 'btn-ghost');
      q('#admTabR').className = 'btn ' + (m === 'register' ? 'btn-primary' : 'btn-ghost');
      q('#admGo').textContent = m === 'login' ? '登 录' : '注 册';
      q('#admTitle').textContent = m === 'login' ? '🛡 管理员登录' : '🛡 注册管理员';
    }
    q('#admTabL').addEventListener('click', () => setMode('login'));
    q('#admTabR').addEventListener('click', () => setMode('register'));

    function apply() {
      const ok = admin && admin.role === 'ADMIN';
      mask.classList.toggle('show', !ok);
      badge.style.display = ok ? 'flex' : 'none';
      if (ok) badge.querySelector('#admWho').textContent = `管理员 ${admin.username}`;
    }
    async function go() {
      const username = q('#admUser').value.trim();
      const password = q('#admPass').value;
      if (!username) return toast('请输入用户名', 'error');
      if (!password || password.length < 4) return toast('密码至少 4 位', 'error');
      try {
        const body = mode === 'register' ? { username, password, role: 'ADMIN' } : { username, password };
        const r = await postJson('/auth/' + mode, body);
        if (!r.success) return toast(r.message || '操作失败', 'error');
        if (r.data.role !== 'ADMIN') return toast('该账号不是管理员，请用管理员账号登录', 'error');
        admin = r.data;
        localStorage.setItem('ionAdmin', JSON.stringify(admin));
        toast(mode === 'login' ? `欢迎，管理员 ${admin.username}` : '管理员注册成功，已登录', 'success');
        apply();
      } catch (e) { toast('请求失败，请检查服务器连接', 'error'); }
    }
    q('#admGo').addEventListener('click', go);
    q('#admPass').addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
    apply();
    return () => admin;
  }

  window.ION = {
    serverBase, setServer, api, fmt, toast, startClock,
    initServerBar, applyServer, setConnState, connectStatus,
    post, put, postJson, getJson, adminGate
  };
})();
