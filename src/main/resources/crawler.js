window.extractContent = function ([regexString, target, opts]) {
    const options = Object.assign({
        withinOnly: true,           // text only from inside target
        includeShadowDom: false,    // traverse shadow roots
        includeIframes: false,      // traverse same-origin iframes
        dedupeLinks: true,         // remove duplicate (href,text) pairs
        maxLinks: 500,              // cap to avoid runaway pages
        useBaseElement: false,      // use <base href> for URL resolution (original = false)
        allowProtocolRelative: false// keep //host/path links? (original = false)
    }, opts || {});

    const base = options.useBaseElement ? (document.baseURI || window.location.href) : `${location.protocol}//${location.host}`;

    let linkRegex = null;
    if (typeof regexString === "string" && regexString.length > 0) {
        try {
            linkRegex = new RegExp(regexString);
        } catch { /* ignore invalid regex pattern */
        }
    }

    const targetElement = target ? document.querySelector(target) : null;

    const isSkippableTag = (el) => el && (el.tagName === "SCRIPT" || el.tagName === "STYLE" || el.tagName === "NOSCRIPT");

    const isInsideTarget = (node) => !!targetElement && (node === targetElement || targetElement.contains(node));

    const shouldKeepHref = (href) => {
        if (!href) return false;
        if (!options.allowProtocolRelative && /^\/\//.test(href)) return false;   // original behavior
        const low = href.trim().toLowerCase();
        return !(low.startsWith("javascript:") || low.startsWith("mailto:") || low.startsWith("tel:"));

    };

    const resolveHref = (href) => {
        try {
            if (options.useBaseElement) return new URL(href, base).href;
            return href.startsWith("/") ? `${base}${href}` : href;
        } catch {
            return null;
        }
    };

    const links = [];
    const linkSet = new Set(); // for dedupe if enabled
    const pushLink = (href, text) => {
        const key = JSON.stringify({href, text});
        if (!options.dedupeLinks || !linkSet.has(key)) {
            links.push({href, text});
            if (options.dedupeLinks) linkSet.add(key);
        }
    };

    const textBuf = [];

    function walkRoot(root) {
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
            acceptNode(node) {
                if (node.nodeType === Node.ELEMENT_NODE && isSkippableTag(node)) {
                    return NodeFilter.FILTER_REJECT; // skip the whole subtree
                }
                return NodeFilter.FILTER_ACCEPT;
            }
        });

        for (let node = walker.currentNode; node; node = walker.nextNode()) {
            if (node.nodeType === Node.TEXT_NODE) {
                if (options.withinOnly ? isInsideTarget(node) : (!targetElement || isInsideTarget(node))) textBuf.push(node.nodeValue || "");
                continue;
            }

            const el = node;

            if (el.tagName === "A") {
                const hrefRaw = el.getAttribute("href");
                if (shouldKeepHref(hrefRaw)) {
                    const pass = !linkRegex || linkRegex.test(hrefRaw);
                    if (pass) {
                        const full = resolveHref(hrefRaw);
                        if (full) {
                            const aText = (el.innerText || "").replace(/[\u00A0\s]+/g, " ").trim();
                            pushLink(full, aText);
                        }
                    }
                }
                if (isInsideTarget(el)) {
                    const aText = (el.innerText || "").replace(/[\u00A0\s]+/g, " ").trim();
                    if (aText) textBuf.push(aText);
                }
            }

            if (options.includeShadowDom && el.shadowRoot) {
                walkRoot(el.shadowRoot);
            }
            if (links.length >= options.maxLinks) break;
        }
    }

    walkRoot(document.body);

    // optional: same-origin iframes
    if (options.includeIframes) {
        const iframes = Array.from(document.querySelectorAll("iframe"));
        for (const f of iframes) {
            try {
                if (f.contentDocument && f.contentDocument.body) {
                    walkRoot(f.contentDocument.body);
                }
            } catch { /* cross-origin — skip */
            }
            if (links.length >= options.maxLinks) break;
        }
    }

    const text = textBuf.join("").replace(/[\u00A0\s]+/g, " ").trim();
    return {text, links};
};