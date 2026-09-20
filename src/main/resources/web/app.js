"use strict";

const state = {
  stationId: null,
  points: [],
  curves: [],
  selectedCurveId: null,
  baseRevision: null,
  segments: [], // 当前编辑器中的分段
  seriesKeys: [],
  seriesKey: null,
  levels: [],
  residuals: [],
};

const $ = (id) => document.getElementById(id);

async function api(method, url, body) {
  const opt = { method, headers: {} };
  if (body !== undefined) {
    opt.headers["Content-Type"] = "application/json";
    opt.body = JSON.stringify(body);
  }
  const res = await fetch(url, opt);
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw { status: res.status, data };
  return data;
}

function toast(msg, isError) {
  const t = $("toast");
  t.textContent = msg;
  t.className = "toast" + (isError ? " error" : "");
  setTimeout(() => t.classList.add("hidden"), 2600);
  t.classList.remove("hidden");
}

function q(s) { return encodeURIComponent(s); }
function fmt(x, digits = 3) {
  if (x === null || x === undefined) return "";
  return Number.isFinite(x) ? Number(x).toFixed(digits) : String(x);
}
const QUALITY_LABEL = {
  ok: "正常",
  missing_level: "原始水位缺失",
  out_of_range: "超出标定范围",
  branch_unknown: "分支无法判定",
  no_active_curve: "无已发布曲线",
  overflow: "计算溢出",
};
const BRANCH_LABEL = { common: "通用", rising: "上涨", falling: "下落" };
const BRANCH_COLOR = { common: "#4a5566", rising: "#d35400", falling: "#2357c6" };

async function init() {
  const stations = await api("GET", "/api/stations");
  $("station").innerHTML = stations
    .map((s) => `<option value="${s.stationId}">${s.name} (${s.stationId})</option>`)
    .join("");
  state.stationId = stations[0].stationId;
  $("deadband").textContent = `涨落判定死区 ±${stations[0].branchDeadband} m`;
  await reloadAll();

  $("station").onchange = async (e) => {
    state.stationId = e.target.value;
    await reloadAll();
  };
  bindControls();
}

async function reloadAll() {
  await Promise.all([loadPoints(), loadCurves(), loadSeriesKeys(), loadPeriods()]);
  renderChart();
  renderSegments();
}

async function loadPoints() {
  state.points = await api("GET", `/api/points?stationId=${q(state.stationId)}`);
  renderResidualTable(state.points.map((p) => ({ placeholder: true, p })));
}

async function loadCurves() {
  state.curves = await api("GET", `/api/curves?stationId=${q(state.stationId)}`);
  const sel = $("curveSelect");
  sel.innerHTML = state.curves
    .map((c) => `<option value="${c.id}">rev ${c.revision} · ${c.status === "published" ? "已发布" : "草稿"}${c.reason ? " · " + c.reason : ""}</option>`)
    .join("");
  const target = state.curves.find((c) => String(c.id) === String(state.selectedCurveId)) || state.curves[state.curves.length - 1];
  selectCurve(target ? target.id : null);
}

function selectCurve(id) {
  const c = state.curves.find((x) => x.id === id);
  state.selectedCurveId = id;
  $("curveSelect").value = id || "";
  state.baseRevision = c ? c.revision : 0;
  state.segments = c ? JSON.parse(JSON.stringify(c.segments)) : [];
  $("curveMeta").textContent = c
    ? `rev ${c.revision} / ${c.status === "published" ? "已发布" : "草稿"} / ${c.reason || "（无修订理由）"}`
    : "尚无曲线，可新建。";
  renderSegments();
  renderChart();
}

async function loadSeriesKeys() {
  state.seriesKeys = await api("GET", `/api/series?stationId=${q(state.stationId)}`);
  $("series").innerHTML = state.seriesKeys.map((k) => `<option>${k}</option>`).join("");
  state.seriesKey = state.seriesKeys[0] || null;
}

async function loadPeriods() {
  const periods = await api("GET", `/api/periods?stationId=${q(state.stationId)}`);
  $("periodTable").querySelector("tbody").innerHTML = periods
    .map((p) => `<tr><td>rev ${p.revision}</td><td>${p.startAt}</td><td>${p.openEnded ? "（持续生效）" : p.endAt}</td></tr>`)
    .join("") || `<tr><td colspan="3" class="muted">尚无生效期</td></tr>`;
}

// ---------------- SVG 图 ----------------
function domain() {
  const hs = [];
  state.points.forEach((p) => hs.push(p.level));
  state.levels.forEach((l) => { if (l.level !== null && Number.isFinite(l.level)) hs.push(l.level); });
  state.segments.forEach((s) => { hs.push(s.levelLow, s.levelHigh); });
  if (!hs.length) return { lo: 0, hi: 10 };
  let lo = Math.min(...hs), hi = Math.max(...hs);
  const pad = Math.max(0.2, (hi - lo) * 0.08);
  return { lo: lo - pad, hi: hi + pad };
}

function evalPoly(coeffs, h) {
  let acc = 0;
  for (let i = coeffs.length - 1; i >= 0; i--) acc = acc * h + coeffs[i];
  return acc;
}

function renderChart() {
  const W = 1180, H = 420, M = { l: 56, r: 20, t: 18, b: 40 };
  const d = domain();
  const qs = state.points.map((p) => p.discharge);
  const allQ = [...qs];
  // 让曲线也参与纵轴尺度
  const branches = [...new Set(state.segments.map((s) => s.branch))];
  branches.forEach((b) => {
    state.segments.filter((s) => s.branch === b).forEach((s) => {
      [s.levelLow, (s.levelLow + s.levelHigh) / 2, s.levelHigh].forEach((h) => allQ.push(evalPoly(s.coeffs, h)));
    });
  });
  const qLo = Math.min(...allQ, 0), qHi = Math.max(...allQ, 1);
  const x = (h) => M.l + ((h - d.lo) / (d.hi - d.lo)) * (W - M.l - M.r);
  const y = (q) => H - M.b - ((q - qLo) / (qHi - qLo)) * (H - M.t - M.b);

  let svg = `<svg viewBox="0 0 ${W} ${H}" width="100%" role="img">`;
  // 网格 + 轴
  for (let i = 0; i <= 5; i++) {
    const hx = d.lo + ((d.hi - d.lo) * i) / 5;
    const px = x(hx);
    svg += `<line x1="${px}" y1="${M.t}" x2="${px}" y2="${H - M.b}" stroke="#eef1f6"/>`;
    svg += `<text x="${px}" y="${H - M.b + 16}" text-anchor="middle">${fmt(hx, 2)}</text>`;
    const qv = qLo + ((qHi - qLo) * i) / 5;
    const py = y(qv);
    svg += `<line x1="${M.l}" y1="${py}" x2="${W - M.r}" y2="${py}" stroke="#eef1f6"/>`;
    svg += `<text x="${M.l - 8}" y="${py + 4}" text-anchor="end">${fmt(qv, 1)}</text>`;
  }
  svg += `<text x="${(W) / 2}" y="${H - 6}" text-anchor="middle">水位 h (m)</text>`;
  svg += `<text transform="translate(16,${H / 2}) rotate(-90)" text-anchor="middle">流量 Q</text>`;

  // 超出标定范围的背景带（以当前编辑曲线的 common/整体并集为准）
  const allSegs = state.segments;
  if (allSegs.length) {
    const minLow = Math.min(...allSegs.map((s) => s.levelLow));
    const maxHigh = Math.max(...allSegs.map((s) => s.levelHigh));
    if (minLow > d.lo) svg += band(x(d.lo), x(minLow));
    if (maxHigh < d.hi) svg += band(x(maxHigh), x(d.hi));
  }

  // 曲线段（逐分支，按段画，端点处略内缩以表达开闭）
  branches.forEach((b) => {
    const segs = allSegs.filter((s) => s.branch === b).sort((a, c) => a.levelLow - c.levelLow);
    segs.forEach((s) => {
      const pts = [];
      const N = 40;
      for (let i = 0; i <= N; i++) {
        const h = s.levelLow + ((s.levelHigh - s.levelLow) * i) / N;
        pts.push(`${x(h)},${y(evalPoly(s.coeffs, h))}`);
      }
      svg += `<polyline points="${pts.join(" ")}" fill="none" stroke="${BRANCH_COLOR[b]}" stroke-width="2.2"/>`;
      // 连接点标记：闭端实心，开端空心
      [[s.levelLow, s.lowOpen], [s.levelHigh, s.highOpen]].forEach(([h, ep]) => {
        const closed = ep === "closed";
        svg += `<circle cx="${x(h)}" cy="${y(evalPoly(s.coeffs, h))}" r="4" fill="${closed ? BRANCH_COLOR[b] : "#fff"}" stroke="${BRANCH_COLOR[b]}" stroke-width="2"/>`;
      });
    });
  });

  // 实测点
  state.points.forEach((p) => {
    const color = p.excluded ? "#c0392b" : "#2c3e50";
    svg += `<circle cx="${x(p.level)}" cy="${y(p.discharge)}" r="${p.excluded ? 5 : 4}" fill="${color}" fill-opacity="${p.excluded ? 0.85 : 0.9}"><title>${p.observedAt}\nh=${fmt(p.level, 2)} Q=${fmt(p.discharge, 2)} ${p.trend ? "(" + BRANCH_LABEL[p.trend] + ")" : ""}${p.excluded ? " 已排除:" + (p.excludeReason || "") : ""}</title></circle>`;
  });

  svg += `</svg>`;
  $("chart").innerHTML = svg;
  const out = state.points.some((p) => p.level < Math.min(...allSegs.map((s) => s.levelLow), Infinity) || p.level > Math.max(...allSegs.map((s) => s.levelHigh), -Infinity));
  $("chartHint").textContent = allSegs.length
    ? `粉色区域为当前编辑曲线的标定范围之外（${fmt(Math.min(...allSegs.map((s) => s.levelLow)), 2)} ~ ${fmt(Math.max(...allSegs.map((s) => s.levelHigh)), 2)} m）；实心端点为闭、空心为开。${out ? "存在落在范围外的实测点/水位。" : ""}`
    : "尚未编辑分段。";

  function band(x1, x2) {
    return `<rect x="${Math.min(x1, x2)}" y="${M.t}" width="${Math.abs(x2 - x1)}" height="${H - M.t - M.b}" fill="#fdecea" stroke="none"/>`;
  }
}

// ---------------- 分段表 ----------------
function renderSegments() {
  const tbody = $("segmentTable").querySelector("tbody");
  if (!state.segments.length) {
    tbody.innerHTML = `<tr><td colspan="8" class="muted">还没有分段。点“新增分段”或用“拟合”生成。</td></tr>`;
    renderValidation();
    return;
  }
  const epOpt = (val) =>
    `<option value="closed" ${val === "closed" ? "selected" : ""}>闭</option>` +
    `<option value="open" ${val === "open" ? "selected" : ""}>开</option>`;
  tbody.innerHTML = state.segments
    .map((s, i) => `
      <tr>
        <td>
          <select data-k="branch" data-i="${i}">
            ${["common", "rising", "falling"].map((b) => `<option ${s.branch === b ? "selected" : ""}>${b}</option>`).join("")}
          </select>
        </td>
        <td><input type="number" step="0.001" data-k="levelLow" data-i="${i}" value="${s.levelLow}"/></td>
        <td><select data-k="lowOpen" data-i="${i}">${epOpt(s.lowOpen)}</select></td>
        <td><input type="number" step="0.001" data-k="levelHigh" data-i="${i}" value="${s.levelHigh}"/></td>
        <td><select data-k="highOpen" data-i="${i}">${epOpt(s.highOpen)}</select></td>
        <td><input type="text" data-k="coeffs" data-i="${i}" value="${s.coeffs.join(",")}"/></td>
        <td><input type="text" data-k="note" data-i="${i}" value="${s.note || ""}"/></td>
        <td><button class="danger" data-del="${i}">删除</button></td>
      </tr>`)
    .join("");

  tbody.querySelectorAll("[data-k]").forEach((el) => {
    el.onchange = () => {
      const i = +el.dataset.i, k = el.dataset.k;
      if (k === "coeffs") {
        state.segments[i].coeffs = el.value.split(",").map((v) => v.trim()).filter(Boolean).map(Number);
      } else if (k === "levelLow" || k === "levelHigh") {
        state.segments[i][k] = parseFloat(el.value);
      } else {
        state.segments[i][k] = el.value;
      }
      renderValidation();
      renderChart();
    };
  });
  tbody.querySelectorAll("[data-del]").forEach((b) => {
    b.onclick = () => {
      state.segments.splice(+b.dataset.del, 1);
      renderSegments();
      renderChart();
    };
  });
  renderValidation();
}

// 前端轻量校验（服务端是权威）：仅给出可读提示
function clientValidate(segs) {
  const issues = [];
  if (!segs.length) return ["曲线没有任何分段"];
  const branches = [...new Set(segs.map((s) => s.branch))];
  const okSet =
    (branches.length === 1 && branches[0] === "common") ||
    (branches.length === 2 && branches.includes("rising") && branches.includes("falling"));
  if (!okSet) issues.push(`分支集合非法：只能是 {common} 或 {rising,falling}，当前 {${branches.join(",")}}`);

  branches.forEach((b) => {
    const rows = segs.map((s, i) => ({ s, i })).filter((r) => r.s.branch === b)
      .sort((a, c) => a.s.levelLow - c.s.levelLow);
    rows.forEach(({ s, i }) => {
      if (!(s.levelLow < s.levelHigh)) issues.push(`#${i + 1}(${b}) 需要 levelLow < levelHigh`);
      if (!s.coeffs.length || s.coeffs.some((c) => !Number.isFinite(c)))
        issues.push(`#${i + 1}(${b}) 系数必须为逗号分隔的有限数字`);
    });
    for (let k = 0; k < rows.length - 1; k++) {
      const left = rows[k].s, right = rows[k + 1].s;
      if (right.levelLow < left.levelHigh - 1e-9) { issues.push(`${b}: 分段在 ${fmt(right.levelLow, 2)} 重叠`); continue; }
      if (Math.abs(right.levelLow - left.levelHigh) > 1e-9) { issues.push(`${b}: ${fmt(left.levelHigh, 2)} 与 ${fmt(right.levelLow, 2)} 之间有缝隙`); continue; }
      const lIn = left.highOpen === "closed", rIn = right.lowOpen === "closed";
      if (lIn && rIn) issues.push(`${b}: 连接点 ${fmt(left.levelHigh, 2)} 两端皆闭，会重复命中`);
      if (!lIn && !rIn) issues.push(`${b}: 连接点 ${fmt(left.levelHigh, 2)} 两端皆开，会漏掉该水位`);
    }
  });
  return issues;
}

function renderValidation() {
  const box = $("validationBox");
  const issues = clientValidate(state.segments);
  if (!issues.length) {
    box.innerHTML = `<span class="valid">✔ 分段数学校验通过（半开端点恰好接续）</span>`;
  } else {
    box.innerHTML = issues.map((m) => `<div class="issue">✗ ${m}</div>`).join("");
  }
}

async function saveCurve() {
  const issues = clientValidate(state.segments);
  if (issues.length) { toast("分段校验未通过，无法保存", true); return; }
  try {
    const saved = await api("POST", "/api/curves/save", {
      stationId: state.stationId,
      curveId: state.selectedCurveId,
      baseRevision: state.baseRevision,
      segments: state.segments,
      reason: $("reason").value || null,
    });
    toast(`已保存为 rev ${saved.revision}`);
    state.selectedCurveId = saved.id;
    await loadCurves();
    await loadPeriods();
    hideConflict();
  } catch (e) {
    if (e.status === 400 && e.data.details) {
      toast("服务端校验失败", true);
      $("validationBox").innerHTML = e.data.details.map((m) => `<div class="issue">✗ ${m}</div>`).join("");
    } else if (e.status === 409 && e.data.serverSegments) {
      showConflict(e.data);
    } else {
      toast(e.data && e.data.error || "保存失败", true);
    }
  }
}

function showConflict(c) {
  const rows = [
    `<div><b>版本冲突：</b>你基于 rev ${c.baseRevision}，服务端已是 rev ${c.currentRevision}。差异如下：</div>`,
    ...c.onlyInClient.map((s) => `<div>· 仅你这边有：${s.branch} [${fmt(s.levelLow, 2)},${fmt(s.levelHigh, 2)}]</div>`),
    ...c.onlyOnServer.map((s) => `<div>· 仅服务端有：${s.branch} [${fmt(s.levelLow, 2)},${fmt(s.levelHigh, 2)}]</div>`),
    ...c.changedInPlace.map((p) => `<div>· 同段被改动：${p.server.branch} [${fmt(p.server.levelLow, 2)},${fmt(p.server.levelHigh, 2)}]，服务端系数 ${p.server.coeffs.map((x) => fmt(x, 2)).join(",")}</div>`),
    `<div style="margin-top:8px"><button id="takeServer" class="secondary">放弃本地，采用服务端版本</button></div>`,
  ];
  const box = $("conflictBanner");
  box.innerHTML = rows.join("");
  box.classList.remove("hidden");
  $("takeServer").onclick = async () => {
    state.segments = JSON.parse(JSON.stringify(c.serverSegments));
    state.baseRevision = c.currentRevision;
    renderSegments(); renderChart(); hideConflict();
    toast("已切换到服务端版本，请在此基础上再改");
  };
}
function hideConflict() { $("conflictBanner").classList.add("hidden"); }

async function publishCurve() {
  if (!state.selectedCurveId) { toast("请先选择或保存曲线", true); return; }
  const issues = clientValidate(state.segments);
  if (issues.length) { toast("分段校验未通过，无法发布", true); return; }
  $("pubStart").value = new Date().toISOString().slice(0, 10) + "T00:00:00Z";
  $("pubEnd").value = "";
  $("publishModal").classList.remove("hidden");
}

// ---------------- 残差表 ----------------
function renderResidualTable(rows) {
  const tbody = $("residualTable").querySelector("tbody");
  tbody.innerHTML = state.points
    .map((p) => {
      const r = (state.residuals || []).find((x) => x.pointId === p.id);
      return `<tr class="${p.excluded ? "excluded-row" : ""}">
        <td>${p.observedAt.replace("T", " ").replace("Z", "")}</td>
        <td>${p.trend ? BRANCH_LABEL[p.trend] || p.trend : "—"}</td>
        <td>${fmt(p.level, 2)}</td>
        <td>${fmt(p.discharge, 2)}</td>
        <td>${r && r.predictedDischarge !== null && r.predictedDischarge !== undefined ? fmt(r.predictedDischarge, 2) : "—"}</td>
        <td>${r && r.residual !== null && r.residual !== undefined ? fmt(r.residual, 2) : "—"}</td>
        <td>${r ? `<span class="q-${r.quality}">${QUALITY_LABEL[r.quality] || r.quality}</span>` : ""}</td>
        <td>
          <label style="display:flex;align-items:center;gap:4px">
            <input type="checkbox" data-exclude="${p.id}" ${p.excluded ? "checked" : ""}/>
            ${p.excluded ? p.excludeReason || "回水" : "正常"}
          </label>
        </td>
      </tr>`;
    })
    .join("");
  tbody.querySelectorAll("[data-exclude]").forEach((cb) => {
    cb.onchange = async () => {
      const id = +cb.dataset.exclude;
      const reason = cb.checked ? "回水顶托" : null;
      await api("POST", `/api/points/${id}/excluded`, { excluded: cb.checked, reason });
      await loadPoints();
      renderChart();
      toast(cb.checked ? "已标记为回水排除（不参与拟合）" : "已恢复为采用点");
    };
  });
}

async function calcResiduals() {
  if (!state.segments.length) { toast("请先选择或编辑曲线", true); return; }
  try {
    // 始终以编辑器中的当前分段为准（可能尚未保存）；后端在给出 curveId 时会优先用库中版本。
    state.residuals = await api("POST", "/api/curves/residuals", {
      stationId: state.stationId,
      segments: state.segments,
    });
    renderResidualTable();
    const bad = state.residuals.filter((r) => r.quality !== "ok" && !state.points.find((p) => p.id === r.pointId).excluded);
    toast(`残差已计算，${bad.length} 个未排除点不在正常标定结果内`);
  } catch (e) {
    toast(e.data && e.data.error || "残差计算失败", true);
  }
}

// ---------------- 影响预览 / 重算 ----------------
function qCell(q) {
  return q ? `<span class="q-${q}">${QUALITY_LABEL[q] || q}</span>` : "—";
}

async function preview() {
  if (!state.seriesKey) { toast("没有水位序列", true); return; }
  try {
    const resp = await api("POST", "/api/preview", {
      stationId: state.stationId,
      seriesKey: state.seriesKey,
      segments: state.segments,
    });
    renderPreviewTable(resp);
  } catch (e) {
    toast(e.data && e.data.error || "预览失败", true);
  }
}

function renderPreviewTable(resp) {
  $("previewSummary").innerHTML =
    `共 ${resp.total} 点，预计 <b>${resp.changed}</b> 点会改变。` +
    Object.entries(resp.byQuality).map(([k, n]) => ` ${QUALITY_LABEL[k] || k} ${n}`).join("；");
  $("previewTable").querySelector("tbody").innerHTML = resp.points
    .map((p) => `<tr class="${p.changed ? "changed" : ""}">
      <td>${p.observedAt.replace("T", " ").replace("Z", "")}</td>
      <td>${p.level === null || p.level === undefined ? "—" : fmt(p.level, 2)}</td>
      <td>${p.branchUsed ? BRANCH_LABEL[p.branchUsed] || p.branchUsed : "—"}</td>
      <td>${p.oldDischarge === null || p.oldDischarge === undefined ? "—" : fmt(p.oldDischarge, 2)}</td>
      <td>${qCell(p.oldQuality)}</td>
      <td>${p.newDischarge === null || p.newDischarge === undefined ? "—" : fmt(p.newDischarge, 2)}</td>
      <td>${qCell(p.newQuality)}</td>
      <td>${p.changed ? "会改变" : "不变"}</td>
    </tr>`)
    .join("");
}

async function recompute() {
  if (!state.seriesKey) { toast("没有水位序列", true); return; }
  const jobUuid = (window.__jobSeq = (window.__jobSeq || 0) + 1, "manual-" + Date.now() + "-" + window.__jobSeq);
  try {
    const job = await api("POST", "/api/recompute", {
      jobUuid, stationId: state.stationId, seriesKey: state.seriesKey,
    });
    toast(`重算完成：${job.pointCount} 点，其中 ${job.changedPoints} 点改变${job.retried ? "（同一作业 ID 重试，未重复生成）" : ""}`);
    await preview(); // 重算后再预览，旧值=新值
  } catch (e) {
    toast(e.data && e.data.error || "重算失败", true);
  }
}

// ---------------- 拟合对话框 ----------------
let lastFit = null;
function openFit() {
  $("fitResult").textContent = "";
  lastFit = null;
  $("fitModal").classList.remove("hidden");
}
async function runFit() {
  const body = {
    branch: $("fitBranch").value,
    degree: parseInt($("fitDegree").value, 10),
    levelLow: $("fitLow").value === "" ? null : parseFloat($("fitLow").value),
    levelHigh: $("fitHigh").value === "" ? null : parseFloat($("fitHigh").value),
    lowOpen: $("fitLowOpen").value,
    highOpen: $("fitHighOpen").value,
  };
  try {
    const resp = await api("POST", `/api/curves/fit?stationId=${q(state.stationId)}`, body);
    lastFit = resp.segment;
    $("fitResult").innerHTML = `系数：<b>${resp.segment.coeffs.map((c) => fmt(c, 4)).join(", ")}</b><br/>使用实测点 ${resp.usedPointIds.length} 个（已排除回水点）`;
  } catch (e) {
    $("fitResult").textContent = (e.data && e.data.error) || "拟合失败";
  }
}
function appendFit() {
  if (!lastFit) { toast("请先计算", true); return; }
  state.segments.push(lastFit);
  $("fitModal").classList.add("hidden");
  renderSegments();
  renderChart();
  toast("已加入分段表，请检查相邻端点开闭并保存");
}

// ---------------- 控件绑定 ----------------
function bindControls() {
  $("curveSelect").onchange = (e) => selectCurve(parseInt(e.target.value, 10));
  $("newCurveBtn").onclick = () => {
    state.selectedCurveId = null;
    state.baseRevision = 0;
    state.segments = [];
    renderSegments();
    renderChart();
    $("curveMeta").textContent = "新建曲线（保存时自动分配下一个 revision）";
  };
  $("addSegmentBtn").onclick = () => {
    const b = $("addBranch").value;
    state.segments.push({
      branch: b, levelLow: 1, levelHigh: 3,
      lowOpen: "closed", highOpen: "open", coeffs: [0, 1], note: "",
    });
    renderSegments();
  };
  $("saveBtn").onclick = saveCurve;
  $("publishBtn").onclick = publishCurve;
  $("pubCancel").onclick = () => $("publishModal").classList.add("hidden");
  $("pubConfirm").onclick = async () => {
    // 发布的是“当前编辑器对应曲线”；若有未保存改动，先保存再发布。
    try {
      let curveId = state.selectedCurveId;
      if ($("reason").value || !curveId) {
        await saveCurve();
        curveId = state.selectedCurveId;
      }
      await api("POST", "/api/curves/publish", {
        stationId: state.stationId,
        curveId,
        startAt: $("pubStart").value,
        endAt: $("pubEnd").value || null,
      });
      $("publishModal").classList.add("hidden");
      toast("已发布（仅影响之后显式重算的序列）");
      await loadCurves();
      await loadPeriods();
    } catch (e) {
      toast((e.data && e.data.error) || "发布失败", true);
    }
  };

  $("fitBtn").onclick = openFit;
  $("fitCancel").onclick = () => $("fitModal").classList.add("hidden");
  $("fitRun").onclick = runFit;
  $("fitAppend").onclick = appendFit;

  $("residualBtn").onclick = calcResiduals;
  $("previewBtn").onclick = preview;
  $("recomputeBtn").onclick = recompute;
}

init().catch((e) => {
  console.error(e);
  toast("初始化失败：" + ((e.data && e.data.error) || e.message || e), true);
});
