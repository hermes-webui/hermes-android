// Minimal Chrome DevTools Protocol client over Node's built-in WebSocket (Node 22+).
export async function connect(port) {
  const deadline = Date.now() + 10_000;
  let page;
  while (Date.now() < deadline) {
    let list;
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json`, {
        signal: AbortSignal.timeout(Math.min(1000, Math.max(1, deadline - Date.now()))),
      });
      if (!response.ok) throw new Error(`WebView DevTools returned HTTP ${response.status}`);
      list = await response.json();
    } catch (error) {
      if (!(error instanceof TypeError && error.message === 'fetch failed') &&
          error.name !== 'TimeoutError') throw error;
    }
    page = list?.find((p) => p.type === 'page' && p.webSocketDebuggerUrl);
    if (page) break;
    await new Promise(resolve => setTimeout(resolve, 150));
  }
  if (!page) {
    throw new Error('WebView DevTools did not expose a page within 10 seconds. Open the debug app and check ADB forwarding.');
  }
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((res, rej) => {
    ws.onopen = res;
    ws.onerror = () => rej(new Error('DevTools socket error'));
  });
  let id = 0;
  const pending = new Map();
  ws.onmessage = (m) => {
    const d = JSON.parse(m.data);
    if (d.id && pending.has(d.id)) {
      pending.get(d.id)(d);
      pending.delete(d.id);
    }
  };
  const send = (method, params = {}) =>
    new Promise((res) => {
      const i = ++id;
      pending.set(i, res);
      ws.send(JSON.stringify({ id: i, method, params }));
    });
  return {
    pageUrl: page.url,
    async evaluate(expression) {
      const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      if (r.result?.exceptionDetails) {
        throw new Error(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text);
      }
      return r.result?.result?.value;
    },
    /** Poll a JS expression until it returns a truthy value or the timeout elapses. Returns the last value. */
    async waitFor(expression, timeoutMs = 4000, intervalMs = 150) {
      const end = Date.now() + timeoutMs;
      let last;
      while (Date.now() < end) {
        last = await this.evaluate(expression);
        if (last) return last;
        await new Promise((r) => setTimeout(r, intervalMs));
      }
      return last;
    },
    close() {
      try {
        ws.close();
      } catch {
        /* already closed */
      }
    },
  };
}
