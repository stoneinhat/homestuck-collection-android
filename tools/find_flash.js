// Dev helper: list the first few Flash (.swf) story pages via the leader WebView.
const WebSocket = require(require("path").join(__dirname, "..", "base", "node_modules", "ws"));
const http = require("http");

const EXPR = `(function(){
  var s = window.vm.$archive.mspa.story, out = [];
  for (var k in s) {
    if (s[k].media && s[k].media.some(function(m){ return m.indexOf(".swf") >= 0 })) {
      out.push(k + "  " + s[k].title);
      if (out.length >= 5) break;
    }
  }
  return out.join(" | ");
})()`;

http.get("http://127.0.0.1:9223/json", r => {
  let d = "";
  r.on("data", c => (d += c));
  r.on("end", () => {
    const pages = JSON.parse(d).filter(p => p.type === "page");
    const leader = pages.sort((a, b) =>
      JSON.parse(b.description).width - JSON.parse(a.description).width
    )[0];
    const ws = new WebSocket(leader.webSocketDebuggerUrl);
    ws.on("open", () => {
      ws.send(JSON.stringify({
        id: 1, method: "Runtime.evaluate",
        params: { expression: EXPR, returnByValue: true }
      }));
    });
    ws.on("message", m => {
      const res = JSON.parse(m);
      if (res.id === 1) {
        console.log(res.result.result.value);
        process.exit(0);
      }
    });
  });
});
