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
  var state = {
    role: null,
    reporting: false,
    lastSent: null
  };

  var CSS_PANEL = [
    "/* dual-screen: text lives on the bottom screen */",
    ".pageBody .textContent { display: none !important; }",
    ".pageBody .footnotesContainer { display: none !important; }"
  ].join("\n");

  var CSS_TEXT = [
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
    setCss(CSS_PANEL);
    state.reporting = true;
    state.lastSent = null;
    whenVm(function (vm) {
      vm.dsRole = "panel";
      try {
        window.TuhcDs.ready();
      } catch (e) {}
      report();
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
