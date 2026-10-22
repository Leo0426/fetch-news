(() => {
  function initializeSummary(region) {
    const status = region.querySelector('.reader-summary-status');
    const label = status.querySelector('span');
    const text = region.querySelector('[data-compact-summary-text]');
    const errorText = region.querySelector('[data-compact-summary-error]');
    const check = region.querySelector('[data-compact-summary-check]');
    const recovery = region.querySelector('[data-compact-summary-recovery]');
    let state = region.dataset.summaryState;
    let error = state === 'error' ? errorText.textContent : '';
    let uncertain = false;
    let active = true;
    let timer;
    let request;

    function pending() {
      return state === 'queued' || state === 'running';
    }

    function render() {
      const focused = document.activeElement;
      region.dataset.summaryState = state;
      const message = uncertain ? '摘要状态暂时无法确认' : ({
        queued: '排队中', running: '正在生成摘要', success: 'AI 摘要 · 已保存',
        error: '摘要生成失败', none: '尚无摘要'
      }[state]);
      if (label.textContent !== message) label.textContent = message;
      status.classList.toggle('running', pending() && !uncertain);
      status.classList.toggle('err', state === 'error' || uncertain);
      text.hidden = state !== 'success';
      errorText.hidden = state !== 'error' && !uncertain;
      const detail = uncertain ? '暂时无法连接，可重新查看任务状态。' : error;
      if (errorText.textContent !== detail) errorText.textContent = detail;
      check.hidden = !uncertain && state !== 'error';
      check.disabled = !!request;
      check.textContent = request ? '正在查看…' : '重新查看状态';
      check.setAttribute('aria-busy', String(!!request));
      recovery.hidden = !uncertain && !['error', 'none'].includes(state);
      if (focused === check && check.hidden) status.focus({preventScroll: true});
    }

    function apply(result) {
      if (!result || !['none', 'queued', 'running', 'success', 'error'].includes(result.state)
          || (result.state === 'success' && (typeof result.summary !== 'string' || !result.summary.trim()))
          || (result.error != null && typeof result.error !== 'string')) {
        throw new Error('Unexpected summary status');
      }
      state = result.state;
      error = state === 'error' ? (result.error || '摘要未完成，请到文章页重试。') : '';
      uncertain = false;
      if (state === 'success') text.textContent = result.summary;
    }

    async function checkStatus() {
      if (!active || request) return;
      clearTimeout(timer);
      const controller = new AbortController();
      const current = {controller, timeout: setTimeout(() => controller.abort(), 15000)};
      request = current;
      render();
      try {
        const response = await fetch('/reader/summary-status?' + new URLSearchParams({link: region.dataset.link}), {
          method: 'GET', cache: 'no-store', signal: controller.signal
        });
        if (!response.ok) throw new Error('Status request failed');
        const result = await response.json();
        if (active && request === current) apply(result);
      } catch (_) {
        if (active && request === current) uncertain = true;
      } finally {
        clearTimeout(current.timeout);
        if (active && request === current) {
          request = null;
          render();
          if (pending() && !uncertain) timer = setTimeout(checkStatus, 2000);
        }
      }
    }

    check.addEventListener('click', checkStatus);
    render();
    if (pending()) checkStatus();
    return () => {
      active = false;
      clearTimeout(timer);
      if (request) {
        clearTimeout(request.timeout);
        request.controller.abort();
      }
      request = null;
    };
  }

  function initialize() {
    const dispose = [...document.querySelectorAll('[data-compact-summary]')].map(initializeSummary);
    window.addEventListener('pagehide', () => dispose.forEach(stop => stop()));
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize);
  else initialize();
})();
