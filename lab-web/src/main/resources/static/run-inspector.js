"use strict";
// 登录凭证仅保留内存，禁止存入URL、localStorage或图数据。
let token = null;
let detailGeneration = 0;
const byId = id => document.getElementById(id);
// 所有服务器数据通过textContent展示，不让正文或标识成为HTML／图语法。
function text(tag, value) { const node = document.createElement(tag); node.textContent = value; return node; }
// 私人查询使用Bearer，401／403停止交付并显示固定状态。
async function api(path, options = {}) {
  const response = await fetch(path, {cache: "no-store", ...options, headers: {"Content-Type": "application/json", ...(token ? {Authorization: "Bearer " + token} : {}), ...options.headers}});
  const content = await response.text();
  const body = content ? JSON.parse(content) : null;
  if (!response.ok) throw new Error(body?.message || "请求失败：" + response.status);
  return body;
}
// 列表只能使用已认证本人接口，点击运行ID不携带身份覆盖字段。
async function refresh() {
  try {
    const runs = await api("/api/v1/runs?size=50"); byId("runs").replaceChildren();
    for (const run of runs) { const button = text("button", run.createdAt + " · " + run.status + " · " + (run.incomplete ? "链路不完整" : "已持久化")); button.onclick = () => show(run.traceId); byId("runs").append(button); }
    byId("message").textContent = runs.length ? "最近50条；任务每次恢复对应独立运行。" : "没有本人运行记录。";
  } catch (error) { byId("message").textContent = error.message; }
}
// 显示实际节点时间线，模型用量只显示叶节点，未知值不填零。
async function show(id) {
  // 费用查询会异步等待；退出／换账号或选择另一运行后，旧响应不得重新写回私人详情。
  const requestedToken = token, generation = ++detailGeneration;
  const current = () => token === requestedToken && generation === detailGeneration;
  byId("detail").replaceChildren(); byId("nodes").replaceChildren(); byId("graph").replaceChildren();
  byId("node-detail").replaceChildren(); byId("node-detail").hidden = true;
  try {
    const g = await api("/api/v1/runs/" + encodeURIComponent(id) + "/graph");
    if (!current()) return;
    byId("detail").append(text("h2", "运行 " + g.run.traceId), text("p", g.incomplete ? "链路不完整（写入中、丢失、截断或旧摘要）。" : "本次记录完整。"));
    byId("detail").append(text("p", "任务：" + (g.run.taskId ?? "无") + "；会话：" + (g.run.sessionId ?? "无") + "；服务器首次放行：" + (g.run.firstDeliverableAt ?? "未知")));
    // 可靠费用单独查询，图不完整不推断免费；费用失败也不能阻断图显示。
    try {
      const fees = await api("/api/v1/runs/" + encodeURIComponent(id) + "/fees");
      if (!current()) return;
      byId("detail").append(text("p", "费用状态：" + fees.costStatus + "；已定价估算小计：" + fees.estimatedAmount + " " + (fees.currency ?? "币种未知") + "；保留预留小计：" + fees.reservedAmount + "；未知尝试：" + fees.unknownAttempts + "；待结算：" + fees.pendingAttempts + "。小计不包含未知金额，也不是供应商账单。"));
    } catch (error) { if (!current()) return; byId("detail").append(text("p", "费用记录暂不可读：" + error.message)); }
    if (!current()) return;
    if (g.run.previousTraceId) { const button = text("button", "查看上次执行"); button.onclick = () => show(g.run.previousTraceId); byId("detail").append(button); }
    const start = Date.parse(g.run.createdAt); const total = Math.max(1, Date.parse(g.run.endedAt ?? new Date().toISOString()) - start);
    for (const node of g.nodes) {
      const article = document.createElement("article");
      article.append(text("h3", node.sequence + " · " + node.type + " · " + node.name + " · " + node.status));
      const duration = node.endedAt ? Date.parse(node.endedAt) - Date.parse(node.startedAt) : null;
      const bar = document.createElement("div"); bar.className = "bar";
      bar.style.marginLeft = Math.max(0, 100 * (Date.parse(node.startedAt) - start) / total) + "%";
      bar.style.width = Math.min(100, Math.max(0, 100 * (duration ?? 0) / total)) + "%";
      article.append(bar, text("p", "开始：" + node.startedAt + "；耗时：" + (duration === null ? "未结束" : duration + "毫秒")));
      const details = document.createElement("details");
      details.append(text("summary", "查看节点详情"), text("pre", JSON.stringify(node, null, 2)));
      const inspect = text("button", "查看输入、输出和耗时"); inspect.onclick = () => selectNode(node);
      article.append(inspect, details); byId("nodes").append(article);
    }
    draw(g);
  } catch (error) { if (current()) byId("message").textContent = error.message; }
}
// 节点坐标来自服务器序号，边仅引用实际节点ID；CALL与DEPENDENCY用不同颜色。
function draw(g) {
  const svg = byId("graph"); const ns = "http://www.w3.org/2000/svg"; const positions = new Map();
  const make = (tag, attrs) => { const e = document.createElementNS(ns, tag); for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, String(v)); return e; };
  svg.setAttribute("viewBox", "0 0 1000 " + Math.max(70, g.nodes.length * 48));
  g.nodes.forEach((n, i) => positions.set(n.spanId, {x: n.type === "AGENT" ? 350 : n.type === "MODEL" ? 650 : 70, y: i * 48 + 18}));
  for (const edge of g.edges) { const a = positions.get(edge.from), b = positions.get(edge.to); if (!a || !b) continue; svg.append(make("line", {x1: a.x + 20, y1: a.y + 10, x2: b.x, y2: b.y + 10, stroke: edge.kind === "CALL" ? "#9eb0c3" : "#ce7c27", "stroke-width": edge.kind === "CALL" ? 1 : 3})); }
  for (const n of g.nodes) {
    const p = positions.get(n.spanId);
    const group = make("g", {role: "button", tabindex: 0, "aria-label": "查看节点 " + n.name, "aria-pressed": "false", "data-span-id": n.spanId});
    group.append(make("rect", {x: p.x, y: p.y, width: 260, height: 36, rx: 4}));
    const label = make("text", {x: p.x + 5, y: p.y + 23}); label.textContent = n.sequence + " " + n.name + " " + n.status; group.append(label);
    group.onclick = () => selectNode(n);
    group.onkeydown = event => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); selectNode(n); } };
    svg.append(group);
  }
}
// 内容只按纯文本交付，未记录、截断和来源隐藏分别说明。
function selectNode(node) {
  const panel = byId("node-detail"); panel.replaceChildren(); panel.hidden = false;
  panel.append(text("h2", node.name + " · " + node.status));
  const duration = node.endedAt ? Date.parse(node.endedAt) - Date.parse(node.startedAt) : null;
  panel.append(text("p", "开始：" + node.startedAt + "；结束：" + (node.endedAt ?? "未结束") + "；耗时：" + (duration === null ? "未知" : duration + "毫秒")));
  if (!node.input && !node.output && node.payloadSources?.length) panel.append(text("p", "来源当前不可访问，输入和输出内容已隐藏。"));
  for (const [label, payload] of [["节点输入", node.input], ["节点输出", node.output]]) {
    panel.append(text("h3", label));
    if (!payload) panel.append(text("p", "未记录内容；历史运行或未采集快照的节点无法补回输入和输出。"));
    else {
      if (payload.truncated) panel.append(text("p", "快照已截断：展示 " + payload.content.length + " / " + payload.originalChars + " 个字符。"));
      panel.append(text("pre", payload.content === "" ? "（空内容）" : payload.content));
    }
  }
  for (const group of byId("graph").querySelectorAll("g[role=button]")) group.setAttribute("aria-pressed", String(group.dataset.spanId === node.spanId));
  panel.scrollIntoView({block: "nearest"}); panel.focus({preventScroll: true});
}
// 登录成功后清空密码，token只存当前页面生命周期。
byId("login").onsubmit = async event => {
  event.preventDefault();
  try { const result = await api("/api/v1/auth/login", {method: "POST", body: JSON.stringify({username: byId("username").value, password: byId("password").value})}); token = result.token; byId("password").value = ""; byId("login").hidden = true; byId("logout").hidden = false; byId("refresh").hidden = false; await refresh(); }
  catch (error) { byId("password").value = ""; byId("message").textContent = error.message; }
};
// 注销后清除页面中所有私人数据，无论远程注销是否成功。
byId("logout").onclick = async () => { try { await api("/api/v1/auth/logout", {method: "POST"}); } finally { token = null; byId("runs").replaceChildren(); byId("detail").replaceChildren(); byId("nodes").replaceChildren(); byId("graph").replaceChildren(); byId("node-detail").replaceChildren(); byId("node-detail").hidden = true; byId("login").hidden = false; byId("logout").hidden = true; byId("refresh").hidden = true; } };
byId("refresh").onclick = refresh;
