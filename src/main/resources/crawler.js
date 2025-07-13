window.extractContent = function([regexString, target]) {
    const currentUrl = new URL(window.location.href);
    const baseUrl = `${currentUrl.protocol}//${currentUrl.host}`;
    const linkRegex = new RegExp(regexString);
    const targetElement = document.querySelector(target);

    function traverse(node, insideTarget) {
        let links = [];

        if (node.nodeType === Node.TEXT_NODE) {
            // Do not trim here so we can do the whitespace collapsing later.
            const rawText = node.textContent;
            return {text: insideTarget ? rawText : "", links: []};
        }

        if (node.nodeType !== Node.ELEMENT_NODE) {
            return {text: "", links: []};
        }

        if (["SCRIPT", "STYLE", "NOSCRIPT"].includes(node.tagName)) {
            return {text: "", links: []};
        }

        const newInsideTarget = insideTarget || (node === targetElement);

        if (node.tagName === "A") {
            const aHref = node.getAttribute("href");
            const aText = (node.innerText || "").replace(/[\u00A0\s]+/g, ' ').trim();
            let aLinks = [];
            if (aHref && !/^\/\//.test(aHref) && linkRegex.test(aHref)) {
                const fullHref = aHref.startsWith("/") ? `${baseUrl}${aHref}` : aHref;
                aLinks.push({href: fullHref, text: aText});
            }
            return {text: newInsideTarget ? aText : "", links: aLinks};
        }

        let childText = "";
        for (let i = 0; i < node.childNodes.length; i++) {
            const result = traverse(node.childNodes[i], newInsideTarget);
            childText += result.text;
            links = links.concat(result.links);
        }
        return {text: childText, links: links};
    }

    const result = traverse(document.body, false);
    result.text = result.text.replace(/[\u00A0\s]+/g, ' ').trim();
    return result;
}