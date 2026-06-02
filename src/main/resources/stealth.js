// Minimal stealth patches for headless Chromium. Runs before any
// page script via Playwright's addInitScript, so Akamai / Cloudflare
// bot scripts see real-browser-shaped values when they probe.
(() => {
  // navigator.webdriver: headless Chrome sets this to true. Real
  // browsers leave it undefined.
  try {
    Object.defineProperty(Navigator.prototype, "webdriver", {
      get: () => undefined,
      configurable: true,
    });
  } catch (_) {}

  // navigator.plugins: headless reports length 0. Fake three plausible
  // entries — bot scripts typically check plugins.length > 0.
  try {
    const fakePlugins = [
      { name: "PDF Viewer", filename: "internal-pdf-viewer", description: "Portable Document Format" },
      { name: "Chrome PDF Viewer", filename: "internal-pdf-viewer", description: "Portable Document Format" },
      { name: "Chromium PDF Viewer", filename: "internal-pdf-viewer", description: "Portable Document Format" },
    ];
    Object.defineProperty(Navigator.prototype, "plugins", {
      get: () => fakePlugins,
      configurable: true,
    });
  } catch (_) {}

  // window.chrome: headless lacks this object entirely. Provide a
  // minimal stub — fingerprinters probe chrome.runtime existence.
  try {
    if (!window.chrome) {
      window.chrome = { runtime: {} };
    } else if (!window.chrome.runtime) {
      window.chrome.runtime = {};
    }
  } catch (_) {}

  // Permissions API returns "denied" for notifications in headless
  // even when state is "default" in real browsers. Patch the mismatch.
  try {
    const origQuery = navigator.permissions && navigator.permissions.query;
    if (origQuery) {
      navigator.permissions.query = (params) =>
        params && params.name === "notifications"
          ? Promise.resolve({ state: Notification.permission })
          : origQuery.call(navigator.permissions, params);
    }
  } catch (_) {}
})();
