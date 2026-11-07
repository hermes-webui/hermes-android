// Minimal Chrome DevTools Protocol client over Node's built-in WebSocket (Node 22+).
export async function connect(port) {
  const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  const page = list.find((p) => p.type === 'page');
  if (!page) throw new Error('No WebView page target found');
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
