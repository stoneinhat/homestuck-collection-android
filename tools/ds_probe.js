/*
 * Dev-time probe: connects to the app's WebView DevTools sockets, reports
 * the dual-screen JS state in each WebView, and saves screenshots.
 * Usage: node tools/ds_probe.js [--shot]
 */
const WebSocket = require(require("path").join(
  __dirname, "..", "base", "node_modules", "ws"
));
const fs = require("fs");
const http = require("http");

const PROBE = `JSON.stringify({
  loaded: !!window.__TUHC_DS_LOADED__,
  presetRole: window.__TUHC_DS_ROLE__ || null,
  vmMounted: !!window.vm,
  dsRole: window.vm ? window.vm.dsRole : "(no vm)",
  styleTag: !!document.getElementById("tuhc-ds-style"),
  bridge: typeof window.TuhcDs,
  path: location.pathname,
  mediaContentVisible: (function () {
    var el = document.querySelector(".pageBody .mediaContent");
    if (!el) return "(none)";
    return getComputedStyle(el).display !== "none";
  })(),
  textContentVisible: (function () {
    var el = document.querySelector(".pageBody .textContent");
    if (!el) return "(none)";
    return getComputedStyle(el).display !== "none";
  })(),
  iframes: document.querySelectorAll("iframe").length,
  size: window.innerWidth + "x" + window.innerHeight
})`;

function getJson(url) {
  return new Promise((resolve, reject) => {
    http.get(url, res => {
      let d = "";
      res.on("data", c => (d += c));
      res.on("end", () => resolve(JSON.parse(d)));
    }).on("error", reject);
  });
}

function rpc(ws, id, method, params) {
  return new Promise(resolve => {
    const onMsg = data => {
      const msg = JSON.parse(data);
      if (msg.id === id) {
        ws.off("message", onMsg);
        resolve(msg.result);
      }
    };
    ws.on("message", onMsg);
    ws.send(JSON.stringify({ id, method, params }));
  });
}

(async () => {
  const takeShots = process.argv.includes("--shot");
  const navIdx = process.argv.indexOf("--nav");
  const navUrl = navIdx >= 0 ? process.argv[navIdx + 1] : null;
  const pages = await getJson("http://127.0.0.1:9223/json");

  const clickIdx = process.argv.indexOf("--click");
  const clickSel = clickIdx >= 0 ? process.argv[clickIdx + 1] : null;
  if (clickSel) {
    // Click a link on the follower (the narrowest WebView); the tap should
    // route through the leader and then sync back.
    const follower = pages
      .filter(p => p.type === "page")
      .sort((a, b) =>
        JSON.parse(a.description).width - JSON.parse(b.description).width
      )[0];
    const ws = new WebSocket(follower.webSocketDebuggerUrl);
    await new Promise(r => ws.on("open", r));
    const res = await rpc(ws, 1, "Runtime.evaluate", {
      expression: `(function(){var el=document.querySelector(${JSON.stringify(clickSel)});if(!el)return "no element";el.click();return "clicked "+el.textContent.trim();})()`,
      returnByValue: true
    });
    console.log(`follower: ${res.result.value}; waiting for round-trip...`);
    ws.close();
    await new Promise(r => setTimeout(r, 3000));
  }

  if (navUrl) {
    // Navigate the leader (the widest WebView); the follower should sync.
    const leader = pages
      .filter(p => p.type === "page")
      .sort((a, b) =>
        JSON.parse(b.description).width - JSON.parse(a.description).width
      )[0];
    const ws = new WebSocket(leader.webSocketDebuggerUrl);
    await new Promise(r => ws.on("open", r));
    await rpc(ws, 1, "Runtime.evaluate", {
      expression: `window.vm.$pushURL(${JSON.stringify(navUrl)})`,
      returnByValue: true
    });
    ws.close();
    console.log(`leader navigated to ${navUrl}; waiting for sync...`);
    await new Promise(r => setTimeout(r, 3000));
  }
  for (const page of pages.filter(p => p.type === "page")) {
    const desc = JSON.parse(page.description || "{}");
    const label = `${desc.width}x${desc.height}`;
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise(r => ws.on("open", r));
    const evalRes = await rpc(ws, 1, "Runtime.evaluate", {
      expression: PROBE,
      returnByValue: true
    });
    console.log(`\n=== WebView ${label} (${page.url}) ===`);
    console.log(evalRes.result.value);
    if (takeShots) {
      const shot = await rpc(ws, 2, "Page.captureScreenshot", { format: "png" });
      const file = `webview-${label}.png`;
      fs.writeFileSync(file, Buffer.from(shot.data, "base64"));
      console.log(`saved ${file}`);
    }
    ws.close();
  }
  process.exit(0);
})().catch(e => {
  console.error(e);
  process.exit(1);
});
