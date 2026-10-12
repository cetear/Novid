"use strict";
// 登录凭证仅保留内存，禁止存入URL、localStorage或图数据。
let token = null;
let detailGeneration = 0;
let nodeGeneration = 0;
let selectedRun = null;
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
  const requestedToken = token;
  try {
    const runs = await api("/api/v1/runs?size=50"); if (requestedToken !== token) return; byId("runs").replaceChildren();
    for (const run of runs) { const button = text("button", run.createdAt + " · " + run.status + " · " + (run.incomplete ? "链路不完整" : "已持久化")); button.onclick = () => show(run.traceId); byId("runs").append(button); }
    byId("message").textContent = runs.length ? "最近50条；任务每次恢复对应独立运行。" : "没有本人运行记录。";
  } catch (error) { if (requestedToken === token) byId("message").textContent = error.message; }
}
// 显示实际节点时间线，模型用量只显示叶节点，未知值不填零。
async function show(id) {
  // 费用查询会异步等待；退出／换账号或选择另一运行后，旧响应不得重新写回私人详情。
  const requestedToken = token, generation = ++detailGeneration;
  selectedRun = id; ++nodeGeneration;
  const current = () => token === requestedToken && generation === detailGeneration;
  byId("detail").replaceChildren(); byId("nodes").replaceChildren(); byId("graph").replaceChildren();
  byId("node-detail").replaceChildren(); byId("node-detail").hidden = true;
  try {
    const g = await api("/api/v1/runs/" + encodeURIComponent(id) + "/graph?view=summary");
    if (!current()) return;
    const allCalls = text("button", "查看本次已登记调用（含图中缺失的调用）");
    allCalls.onclick = () => showCalls(id, null); byId("detail").append(allCalls);
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
      const inspect = text("button", "查看输入、输出和耗时"); inspect.onclick = () => selectNode(node);
      if (node.observation) article.append(text("p", "调用：" + node.observation.callStatus + "；校验：" + node.observation.validationStatus + "；HTTP " + node.observation.httpMillis + "ms；校验 " + node.observation.validationMillis + "ms"));
      article.append(inspect); byId("nodes").append(article);
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
async function selectNode(summary) {
  const id = selectedRun, requestedToken = token, runGeneration = detailGeneration, generation = ++nodeGeneration;
  const current = () => token === requestedToken && runGeneration === detailGeneration && generation === nodeGeneration;
  const panel = byId("node-detail"); panel.replaceChildren(); panel.hidden = false;
  panel.append(text("p", "正在读取节点详情…"));
  let node;
  try { node = await api("/api/v1/runs/" + encodeURIComponent(id) + "/nodes/" + encodeURIComponent(summary.spanId)); }
  catch (error) { if (current()) panel.replaceChildren(text("p", error.message)); return; }
  if (!current()) return;
  panel.replaceChildren();
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
  diagnostics(panel, node.diagnostic);
  if (node.observation) {
    const o = node.observation;
    panel.append(text("p", "调用：" + o.callStatus + "；校验：" + o.validationStatus + "；HTTP " + o.httpMillis + "ms；校验 " + o.validationMillis + "ms；归档 " + o.archiveMillis + "ms"), text("p", "输入归档：" + (o.input?.state ?? "已隐藏") + "；输出归档：" + (o.output?.state ?? "已隐藏")));
  }
  if (node.observation?.callId && node.observation.input?.state !== "DISABLED") {
    const open = text("button", "查看本次模型调用全文与校验"); open.onclick = () => showCall(id, node.observation.callId); panel.append(open);
  } else if (node.type === "AGENT") {
    const attempts = text("button", "展开业务节点的模型尝试"); attempts.onclick = () => showCalls(id, node.name); panel.append(attempts);
  }
  for (const group of byId("graph").querySelectorAll("g[role=button]")) group.setAttribute("aria-pressed", String(group.dataset.spanId === node.spanId));
  panel.scrollIntoView({block: "nearest"}); panel.focus({preventScroll: true});
}
function diagnostics(panel, diagnostic) {
  if (!diagnostic) return;
  panel.append(text("h3", "校验失败诊断 · " + diagnostic.origin));
  if (diagnostic.collectionTruncated) panel.append(text("p", "问题数量达到收集上限，可能还有未列出的错误。"));
  if (diagnostic.stoppedEarly) panel.append(text("p", "前置结构校验未通过，后续业务规则尚未执行。"));
  diagnostic.items.forEach((item, i) => {
    const box = document.createElement("details"); box.open = i === 0;
    box.append(text("summary", item.issue.path + " · " + item.issue.rule), text("p", "期望：" + item.issue.expected + "；实际类型：" + item.actualType + (item.issue.actual == null ? "" : "；实际值/计数：" + item.issue.actual) + (item.issue.maximum == null ? "" : "；上限：" + item.issue.maximum + " " + item.issue.unit)));
    box.append(text("p", "定位：" + item.pointer + "；范围：" + item.start + "–" + item.end + "；依据：" + item.locationBasis + (item.line ? "；行/列：" + item.line + "/" + item.column : "")), text("pre", item.excerptRaw));
    if (item.excerptTruncated) box.append(text("p", "摘录已截断，可查看本次完整输出。"));
    panel.append(box);
  });
}
function callPanel() {
  const requestedToken = token, runGeneration = detailGeneration, generation = ++nodeGeneration;
  const panel = byId("node-detail"); panel.hidden = false; panel.replaceChildren();
  return {panel, current: () => token === requestedToken && runGeneration === detailGeneration && generation === nodeGeneration};
}
async function showCalls(id, node, page = 0) {
  const {panel, current} = callPanel(); panel.append(text("h2", "本次调用登记"));
  try {
    const calls = await api("/api/v1/runs/" + encodeURIComponent(id) + "/calls?size=20&page=" + page + (node ? "&nodeId=" + encodeURIComponent(node) : ""));
    if (!current()) return;
    if (!calls.length) panel.append(text("p", "本页没有已登记调用；历史调用无法补录全文。"));
    for (const detail of calls) { const c = detail.call; const button = text("button", c.context.businessNodeId + " · " + c.modelId + " · 尝试 " + c.attempt + " · " + c.callStatus + " · 校验 " + c.validationStatus); button.onclick = () => showCall(id, c.callId); panel.append(button); }
    if (page > 0) { const previous = text("button", "上一页调用"); previous.onclick = () => showCalls(id, node, page - 1); panel.append(previous); }
    if (calls.length === 20) { const next = text("button", "下一页调用"); next.onclick = () => showCalls(id, node, page + 1); panel.append(next); }
  } catch (error) { if (current()) panel.append(text("p", error.message)); }
}
async function showCall(id, callId) {
  const {panel, current} = callPanel();
  try {
    const detail = await api("/api/v1/runs/" + encodeURIComponent(id) + "/calls/" + encodeURIComponent(callId));
    if (!current()) return;
    const c = detail.call;
    panel.append(text("h2", c.context.businessNodeId + " · " + c.modelId), text("p", "调用：" + c.callStatus + "；校验：" + c.validationStatus + "；发送：" + c.sendState + "；阶段：" + c.context.stage + "；操作：" + c.context.operation), text("p", "HTTP " + c.httpMillis + "ms；校验 " + c.validationMillis + "ms；契约版本 " + c.context.schemaVersion));
    diagnostics(panel, c.diagnostic);
    for (const [direction, label, ref] of [["INPUT", "本次完整输入", detail.input], ["OUTPUT", "本次完整输出", detail.output]]) {
      panel.append(text("h3", label), text("p", "归档：" + ref.state + "；原文字节：" + ref.rawBytes + "；字符：" + ref.charCount + (ref.redacted ? "；凭证内容已脱敏" : "")));
      if (ref.preview) panel.append(text("pre", ref.preview));
      if (ref.state !== "READY") continue;
      const preview = text("button", "分页查看"), download = text("button", "下载全文");
      preview.onclick = () => showPayload(id, callId, direction, null, "raw");
      download.onclick = () => downloadPayload(id, callId, direction, current, panel);
      panel.append(preview, download);
      if (direction === "OUTPUT") { const model = text("button", "查看模型输出文本"); model.onclick = () => showPayload(id, callId, direction, null, "model"); panel.append(model); }
    }
  } catch (error) { if (current()) panel.append(text("p", error.message)); }
}
async function showPayload(id, callId, direction, cursor, view) {
  const {panel, current} = callPanel();
  try {
    const page = await api("/api/v1/runs/" + encodeURIComponent(id) + "/calls/" + encodeURIComponent(callId) + "/payloads/" + direction + "?limit=8192&view=" + view + (cursor ? "&cursor=" + encodeURIComponent(cursor) : ""));
    if (!current()) return;
    panel.append(text("h2", view === "model" ? "模型输出文本" : direction === "INPUT" ? "调用输入正文" : "原始响应正文"), text("p", "按页读取完整登记内容，本页最多8192字符。"), text("pre", page.content));
    const back = text("button", "返回调用详情"); back.onclick = () => showCall(id, callId); panel.append(back);
    if (page.nextCursor) { const next = text("button", "下一页正文"); next.onclick = () => showPayload(id, callId, direction, page.nextCursor, view); panel.append(next); }
  } catch (error) { if (current()) panel.append(text("p", error.message)); }
}
async function downloadPayload(id, callId, direction, current, panel) {
  try {
    const response = await fetch("/api/v1/runs/" + encodeURIComponent(id) + "/calls/" + encodeURIComponent(callId) + "/payloads/" + direction + "/download", {cache: "no-store", headers: {Authorization: "Bearer " + token}});
    if (!response.ok) throw new Error("下载失败：" + response.status);
    const body = await response.blob(); if (!current()) return;
    const url = URL.createObjectURL(body), link = document.createElement("a");
    try { link.href = url; link.download = "model-call-" + callId + "-" + direction + ".txt"; document.body.append(link); link.click(); }
    finally { link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000); }
  } catch (error) { if (current()) panel.append(text("p", error.message)); }
}
// 登录成功后清空密码，token只存当前页面生命周期。
byId("login").onsubmit = async event => {
  event.preventDefault();
  try { const result = await api("/api/v1/auth/login", {method: "POST", body: JSON.stringify({username: byId("username").value, password: byId("password").value})}); token = result.token; byId("password").value = ""; byId("login").hidden = true; byId("logout").hidden = false; byId("refresh").hidden = false; await refresh(); }
  catch (error) { byId("password").value = ""; byId("message").textContent = error.message; }
};
// 注销后清除页面中所有私人数据，无论远程注销是否成功。
byId("logout").onclick = async () => {
  const previousToken = token; token = null; selectedRun = null; ++detailGeneration; ++nodeGeneration;
  for (const id of ["runs", "detail", "nodes", "graph", "node-detail"]) byId(id).replaceChildren();
  byId("node-detail").hidden = true; byId("login").hidden = false; byId("logout").hidden = true; byId("refresh").hidden = true;
  byId("message").textContent = "已退出，页面私人内容已清除。";
  try { await api("/api/v1/auth/logout", {method: "POST", headers: {Authorization: "Bearer " + previousToken}}); } catch (_) { /* 本地内容已立即清除。 */ }
};
byId("refresh").onclick = refresh;
