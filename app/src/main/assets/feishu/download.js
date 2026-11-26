(() => {
  const bridge = window.xiaoliDownload;
  let file;
  let active = false;
  let offset = 0;
  async function offer(link) {
    if (active) return;
    active = true;
    try {
      // Retain the blob while the user chooses whether to save it.
      file = await (await fetch(link.href)).blob();
      bridge.postMessage(JSON.stringify({ op: 'offer', size: file.size, type: file.type, name: link.download || '飞书附件' }));
    } catch {
      active = false;
      bridge.postMessage('{"op":"cancel"}');
    }
  }
  const isDownload = a => a && a.hasAttribute('download') && a.href.startsWith('blob:' + location.origin + '/');
  document.addEventListener('click', e => {
    const link = e.target.closest?.('a');
    if (!isDownload(link) || e.defaultPrevented) return;
    e.preventDefault();
    offer(link);
  }, true);
  // Generated exports may click an anchor before attaching it to the DOM.
  const original = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (isDownload(this) && !this.isConnected) { offer(this); return; }
    original.call(this);
  };
  bridge.onmessage = async ({data}) => {
    if (data === 'closed') { active = false; file = null; offset = 0; return; }
    if (data !== 'next' || !file) return;
    if (offset === file.size) { bridge.postMessage('{"op":"done"}'); return; }
    try {
      const part = await file.slice(offset, offset + 65536).arrayBuffer();
      offset += part.byteLength;
      bridge.postMessage(part);
    } catch { bridge.postMessage('{"op":"cancel"}'); }
  };
})();
