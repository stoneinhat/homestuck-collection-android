/*
 * Dual-screen glue injected by the Android shell into both WebViews.
 *
 * Roles:
 *   "panel" - top screen (leader). Keeps panels/Flash, hides story text,
 *             owns navigation history and persistence, reports every SPA
 *             navigation to Android via window.TuhcDs.
 *   "text"  - bottom screen (follower). Hides panels/Flash, shows story
 *             text, never persists to the shared localStorage, and routes
 *             link taps through the leader so both screens stay in sync.
 *
 * The script is idempotent: it defines window.__tuhcDsActivate /
 * window.__tuhcDsDeactivate once and self-activates when
 * window.__TUHC_DS_ROLE__ was preset (the follower WebView).
 */
(function () {
  "use strict";
  if (window.__TUHC_DS_LOADED__) {
    return;
  }
  window.__TUHC_DS_LOADED__ = true;

  var STYLE_ID = "tuhc-ds-style";
  var VIEWPORT_DEFAULT = "width=650";
  var VIEWPORT_PANEL = "width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no";
  var state = {
    role: null,
    reporting: false,
    lastSent: null,
    fitTarget: null,
    fitTimer: null,
    observer: null
  };

  var CSS_SHARED = [
    "html, body, #window, #app, a, button, div {",
    "  -webkit-tap-highlight-color: transparent !important;",
    "  -webkit-focus-ring-color: transparent !important;",
    "  outline: none !important;",
    "}"
  ].join("\n");

  var CSS_PANEL = [
    CSS_SHARED,
    "html, body, #window, #app { background: #000 !important; }",
    ".pageBody .textContent { display: none !important; }",
    ".pageBody .footnotesContainer { display: none !important; }",
    ".pageBody .pageFooter, .footer { display: none !important; }",
    ".pageBody.customStyles {",
    "  background: #000 !important;",
    "  min-height: 100vh !important;",
    "  padding-bottom: 0 !important;",
    "  align-items: center !important;",
    "}",
    ".pageBody .pageFrame {",
    "  width: auto !important; max-width: 100% !important;",
    "  background: transparent !important; padding: 0 !important;",
    "  margin: 0 auto !important;",
    "}",
    ".pageBody .pageContent {",
    "  width: auto !important; min-width: 0 !important; max-width: 100% !important;",
    "  background: transparent !important;",
    "}"
  ].join("\n");

  var CSS_TEXT = [
    CSS_SHARED,
    "/* dual-screen: panels live on the top screen */",
    ".pageBody .mediaContent { display: none !important; }",
    ".pageBody .intro-overlay { display: none !important; }",
    "#touchControls { display: none !important; }",
    "#appHeader { display: none !important; }",
    ".pageBody .navBanner { display: none !important; }",
    ".pageBody .pageFrame { width: 100% !important; max-width: 100% !important; padding-top: 0 !important; }",
    ".pageBody .pageContent { width: 100% !important; max-width: 100% !important; min-width: 0 !important; }",
    ".pageBody .textContent { width: min(620px, 96%) !important; margin: 10px auto !important; }"
  ].join("\n");

  function setCss(cssText) {
    var el = document.getElementById(STYLE_ID);
    if (!cssText) {
      if (el) el.remove();
      return;
    }
    if (!el) {
      el = document.createElement("style");
      el.id = STYLE_ID;
      document.documentElement.appendChild(el);
    }
    el.textContent = cssText;
  }

  function setViewport(content) {
    var metas = document.querySelectorAll('meta[name="viewport"]');
    var meta = metas[0];
    if (!meta) {
      meta = document.createElement("meta");
      meta.setAttribute("name", "viewport");
      if (document.head) document.head.appendChild(meta);
      else document.documentElement.appendChild(meta);
    }
    meta.setAttribute("content", content);
    for (var i = 1; i < metas.length; i++) metas[i].remove();
  }

  function whenVm(fn) {
    if (window.vm) {
      fn(window.vm);
    } else {
      setTimeout(function () {
        whenVm(fn);
      }, 250);
    }
  }

  function currentPath() {
    return location.pathname + location.search;
  }

  function report() {
    if (!state.reporting) return;
    var url = currentPath();
    if (url === state.lastSent) return;
    state.lastSent = url;
    try {
      window.TuhcDs.notifyUrl(url);
    } catch (e) {
      /* bridge unavailable; nothing to sync with */
    }
    scheduleFit();
  }

  function clearFit() {
    if (state.fitTimer) {
      clearTimeout(state.fitTimer);
      state.fitTimer = null;
    }
    if (state.fitTarget) {
      state.fitTarget.style.zoom = "";
      state.fitTarget = null;
    }
    document.documentElement.style.overflow = "";
    if (document.body) document.body.style.overflow = "";
  }

  function findFitTarget() {
    return document.querySelector(".pageBody .media");
  }

  function chromeHeight() {
    var h = 0;
    var nodes = document.querySelectorAll(
      "#appHeader, .navBanner, .pageBody .pageTitle, .pageBody .bannerDiv"
    );
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (!el || !el.getBoundingClientRect) continue;
      var st = window.getComputedStyle(el);
      if (st.display === "none" || st.visibility === "hidden") continue;
      h += el.getBoundingClientRect().height;
    }
    return h;
  }

  function fitMedia() {
    if (state.role !== "panel") return;
    var target = findFitTarget();
    if (!target) {
      document.documentElement.style.overflow = "";
      if (document.body) document.body.style.overflow = "";
      return;
    }

    if (state.fitTarget && state.fitTarget !== target) {
      state.fitTarget.style.zoom = "";
    }
    target.style.zoom = "1";
    state.fitTarget = target;

    var availW = window.innerWidth - 16;
    var availH = window.innerHeight - chromeHeight() - 16;
    if (availW < 32 || availH < 32) return;

    var r = target.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return;

    var scale = Math.min(availW / r.width, availH / r.height);
    if (!isFinite(scale) || scale <= 0) return;
    target.style.zoom = String(scale);
    document.documentElement.style.overflow = "hidden";
    if (document.body) document.body.style.overflow = "hidden";
  }

  function scheduleFit() {
    if (state.role !== "panel") return;
    if (state.fitTimer) clearTimeout(state.fitTimer);
    state.fitTimer = setTimeout(fitMedia, 50);
  }

  function startFitWatch() {
    stopFitWatch();
    window.addEventListener("resize", scheduleFit);
    document.addEventListener("load", scheduleFit, true);
    if (window.MutationObserver) {
      state.observer = new MutationObserver(scheduleFit);
      state.observer.observe(document.documentElement, {
        childList: true,
        subtree: true
      });
    }
    scheduleFit();
  }

  function stopFitWatch() {
    window.removeEventListener("resize", scheduleFit);
    document.removeEventListener("load", scheduleFit, true);
    if (state.observer) {
      state.observer.disconnect();
      state.observer = null;
    }
    clearFit();
  }

  // History hooks are installed once; report() is a no-op until the
  // panel role is activated.
  var origPushState = history.pushState.bind(history);
  history.pushState = function () {
    var r = origPushState.apply(null, arguments);
    report();
    return r;
  };
  var origReplaceState = history.replaceState.bind(history);
  history.replaceState = function () {
    var r = origReplaceState.apply(null, arguments);
    report();
    return r;
  };
  window.addEventListener("popstate", report);

  function activatePanel() {
    state.role = "panel";
    setViewport(VIEWPORT_PANEL);
    setCss(CSS_PANEL);
    state.reporting = true;
    state.lastSent = null;
    startFitWatch();
    whenVm(function (vm) {
      vm.dsRole = "panel";
      try {
        window.TuhcDs.ready();
      } catch (e) {}
      report();
      scheduleFit();
    });
  }

  function activateText() {
    state.role = "text";
    setCss(CSS_TEXT);
    whenVm(function (vm) {
      vm.dsRole = "text";

      // The follower shares localStorage with the leader; it must never
      // write, or the two Vue apps race and corrupt reading progress.
      var root = vm.$localData && vm.$localData.root;
      if (root) {
        root.saveLocalStorage = function () {};
        root._saveLocalStorage = function () {};
        root.applySaveIfPending = function () {};
      }

      // Called by Android when the leader navigates.
      window.__tuhcDsFollowNav = function (url) {
        try {
          var tabData = vm.$localData.tabData;
          var active = tabData.tabs[tabData.activeTabKey];
          if (active && active.url === url) return;
        } catch (e) {}
        vm.$pushURL(url);
      };

      try {
        window.TuhcDs.ready();
      } catch (e) {}
    });

    // Route internal link taps through the leader instead of navigating
    // locally, so the top screen turns the page too.
    document.addEventListener("click", followerClickInterceptor, true);
  }

  function followerClickInterceptor(e) {
    if (state.role !== "text") return;
    var a = e.target && e.target.closest ? e.target.closest("a[href]") : null;
    if (!a) return;
    var href = a.getAttribute("href") || "";
    if (!href || href.charAt(0) === "#") return;
    var url;
    try {
      var parsed = new URL(href, location.href);
      if (parsed.origin !== location.origin) return; // external link
      url = parsed.pathname + parsed.search;
    } catch (err) {
      return;
    }
    e.preventDefault();
    e.stopPropagation();
    try {
      window.TuhcDs.requestNav(url);
    } catch (err) {
      // No bridge; fall back to navigating locally.
      window.__tuhcDsFollowNav && window.__tuhcDsFollowNav(url);
    }
  }

  window.__tuhcDsActivate = function (role) {
    if (role === "text") {
      activateText();
    } else {
      activatePanel();
    }
  };

  window.__tuhcDsDeactivate = function () {
    state.role = null;
    state.reporting = false;
    stopFitWatch();
    setViewport(VIEWPORT_DEFAULT);
    setCss(null);
    whenVm(function (vm) {
      vm.dsRole = null;
    });
  };

  // The follower WebView presets the role before injecting this script.
  if (window.__TUHC_DS_ROLE__ === "text") {
    activateText();
  }
})();
