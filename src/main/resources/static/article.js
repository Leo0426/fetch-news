(() => {
  const contextKey = 'fn-reading-context';
  let readerState;

  function currentListUrl() {
    return window.location.pathname + window.location.search + window.location.hash;
  }

  function savedContext() {
    try {
      const saved = JSON.parse(window.sessionStorage.getItem(contextKey));
      return saved?.url === currentListUrl() && Number.isFinite(saved.savedAt)
        && Date.now() - saved.savedAt < 86400000 ? saved : null;
    } catch (_) {
      return null;
    }
  }

  function articleId(link) {
    try {
      const url = new URL(link.href, window.location.href);
      return url.origin === window.location.origin && url.pathname === '/reader/article'
        ? url.searchParams.get('link') : null;
    } catch (_) {
      return null;
    }
  }

  function visibleArticleLinks() {
    return [...document.querySelectorAll('[data-article-link]')]
      .filter(link => link.getClientRects().length && articleId(link));
  }

  function rememberContext(link) {
    if (!link || !articleId(link)) return;
    const links = visibleArticleLinks();
    const index = links.indexOf(link);
    if (index < 0) return;
    const candidates = [...links.slice(index), ...links.slice(0, index).reverse()];
    const context = {
      url: currentListUrl(), savedAt: Date.now(),
      candidates: [...new Set(candidates.map(articleId))].slice(0, 200),
      offset: link.getBoundingClientRect().top,
      filters: readerState ? {
        filter: readerState.filter, activeTag: readerState.activeTag,
        searchQuery: document.getElementById('reader-search')?.value ?? readerState.searchQuery
      } : null
    };
    try { window.sessionStorage.setItem(contextKey, JSON.stringify(context)); } catch (_) {}
  }

  function restorePosition() {
    const saved = savedContext();
    if (saved) {
      try { window.sessionStorage.removeItem(contextKey); } catch (_) {}
    }
    if (!Array.isArray(saved?.candidates) || !Number.isFinite(saved.offset)) return;
    const links = [...document.querySelectorAll('[data-article-link]')];
    const target = saved.candidates.flatMap(id => links.filter(link => articleId(link) === id)).find(link => {
      const opened = [];
      for (let details = link.closest('details:not([open])'); details;
           details = link.closest('details:not([open])')) {
        details.open = true;
        opened.push(details);
      }
      if (link.getClientRects().length) return true;
      opened.forEach(details => { details.open = false; });
      return false;
    });
    if (!target) return;
    window.scrollTo(0, Math.max(0, window.scrollY + target.getBoundingClientRect().top - saved.offset));
    target.focus({preventScroll: true});
    target.closest('.rd-card')?.classList.add('fn-focused');
  }

  window.FetchNewsReadingContext = {
    attach(state) {
      readerState = state;
      const filters = savedContext()?.filters;
      if (filters) {
        if (['all', 'unread', 'read', 'bookmarked'].includes(filters.filter)) state.filter = filters.filter;
        if (typeof filters.activeTag === 'string' && filters.activeTag.length <= 200) state.activeTag = filters.activeTag;
        if (typeof filters.searchQuery === 'string' && filters.searchQuery.length <= 200) state.searchQuery = filters.searchQuery;
      }
      state.$nextTick(restorePosition);
    }
  };

  window.addEventListener('pageshow', event => {
    if (event.persisted) window.location.reload();
  });

  function updateArticleLink(link) {
    if (!link) return;
    const target = new URL(link.href, window.location.href);
    if (target.origin !== window.location.origin || target.pathname !== '/reader/article') return;
    target.searchParams.set('returnTo', window.location.pathname + window.location.search + window.location.hash);
    link.href = target.pathname + target.search;
  }

  function initializeArticleSummary(article) {
    const region = article.querySelector('[data-article-summary]');
    if (!region) return;
    const text = region.querySelector('[data-article-summary-text]');
    const message = region.querySelector('[data-article-summary-message]');
    const errorText = region.querySelector('[data-article-summary-error]');
    const controls = region.querySelector('[data-article-summary-controls]');
    const generate = region.querySelector('[data-article-summary-generate]');
    const check = region.querySelector('[data-article-summary-check]');
    const settings = region.querySelector('[data-article-summary-settings]');
    const aiEnabled = region.dataset.aiEnabled === 'true';
    let state = region.dataset.summaryState;
    let error = state === 'error' ? errorText.textContent.trim() : '';
    let uncertain = state === 'unknown';
    let active = true;
    let timer;
    let request;

    function isPending() {
      return state === 'queued' || state === 'running';
    }

    function renderSummary() {
      const focused = document.activeElement;
      region.dataset.summaryState = state;
      text.hidden = state !== 'success';
      errorText.hidden = !uncertain && state !== 'error';
      const errorMessage = uncertain ? '结果暂时无法确认，请先查看状态，避免重复提交。' : error;
      if (errorText.textContent !== errorMessage) errorText.textContent = errorMessage;
      const statusMessage = uncertain
        ? (request ? '正在确认摘要状态…' : '摘要状态暂时无法确认。')
        : request?.submit ? '正在提交摘要任务…'
        : ({success: '摘要已保存。', queued: aiEnabled
            ? '已加入队列，完成后自动显示。可以继续阅读正文。' : 'AI 已关闭，任务保留在队列中。',
          running: '正在生成摘要，完成后自动显示。可以继续阅读正文。', error: '摘要生成失败。'}[state]
          || (aiEnabled ? '按需生成，快速了解这篇文章。' : '开启 AI 后，可以为文章生成摘要。'));
      if (message.textContent !== statusMessage) message.textContent = statusMessage;
      generate.hidden = !aiEnabled || uncertain || !['none', 'error'].includes(state);
      generate.disabled = !!request;
      generate.textContent = request?.submit ? '正在提交…' : (state === 'error' ? '重试生成' : '生成摘要');
      generate.setAttribute('aria-busy', String(!!request?.submit));
      check.hidden = !uncertain;
      check.disabled = !!request;
      check.textContent = request && !request.submit ? '正在查看…' : '查看状态';
      check.setAttribute('aria-busy', String(!!request && !request.submit));
      controls.hidden = generate.hidden && check.hidden;
      settings.hidden = state === 'success' || (aiEnabled && state !== 'error' && !uncertain);
      if (controls.contains(focused) && focused.hidden) {
        (!check.hidden ? check : !generate.hidden ? generate : message).focus({preventScroll: true});
      }
    }

    function applySummary(result) {
      if (!result || !['none', 'queued', 'running', 'success', 'error'].includes(result.state)
          || (result.state === 'success' && (typeof result.summary !== 'string' || !result.summary.trim()))
          || (result.error != null && typeof result.error !== 'string')) {
        throw new Error('Unexpected summary status');
      }
      state = result.state;
      error = state === 'error' ? (result.error || '摘要未完成，可以稍后重试。') : '';
      uncertain = false;
      if (state === 'success') text.textContent = result.summary;
    }

    async function requestSummary(submit) {
      if (!active || request || (submit && (!aiEnabled || uncertain || isPending() || state === 'success'))) return;
      clearTimeout(timer);
      const controller = new AbortController();
      const current = {controller, submit, timeout: setTimeout(() => controller.abort(), 15000)};
      request = current;
      renderSummary();
      try {
        const response = await fetch(submit ? '/reader/summarize' : '/reader/summary-status?' + new URLSearchParams({link: article.dataset.link}), {
          method: submit ? 'POST' : 'GET',
          ...(submit ? {
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: new URLSearchParams({link: article.dataset.link}).toString()
          } : {}),
          signal: controller.signal
        });
        const result = await response.json();
        if (!response.ok && (!submit || ![400, 404].includes(response.status) || result.state !== 'error')) {
          throw new Error('Request failed');
        }
        if (active && request === current) applySummary(result);
      } catch (_) {
        if (active && request === current) uncertain = true;
      } finally {
        clearTimeout(current.timeout);
        if (active && request === current) {
          request = null;
          renderSummary();
          if (isPending() && !uncertain) timer = setTimeout(() => requestSummary(false), 2000);
        }
      }
    }

    generate.addEventListener('click', () => requestSummary(true));
    check.addEventListener('click', () => requestSummary(false));
    window.addEventListener('pagehide', () => {
      active = false;
      clearTimeout(timer);
      if (request) {
        clearTimeout(request.timeout);
        if (!request.submit) request.controller.abort();
      }
      request = null;
    });
    renderSummary();
    if (isPending()) requestSummary(false);
  }

  function initialize() {
    document.querySelectorAll('[data-article-link]').forEach(updateArticleLink);
    ['pointerdown', 'focusin', 'contextmenu', 'click', 'auxclick'].forEach(eventName => {
      document.addEventListener(eventName, event => {
        const link = event.target.closest?.('[data-article-link]');
        updateArticleLink(link);
        if (['click', 'auxclick', 'contextmenu'].includes(eventName) && !event.defaultPrevented) rememberContext(link);
      }, true);
    });
    window.addEventListener('hashchange', () => {
      document.querySelectorAll('[data-article-link]').forEach(updateArticleLink);
    });

    const article = document.querySelector('[data-article]');
    if (!article) {
      if (!document.body.hasAttribute('data-reader')) restorePosition();
      return;
    }
    initializeArticleSummary(article);
    const ui = window.FetchNewsUI;
    const markButton = article.querySelector('[data-article-mark-read]');
    const finishButton = article.querySelector('[data-article-finish]');
    const bookmarkButton = article.querySelector('[data-article-bookmark]');
    const bookmarkLabel = article.querySelector('[data-article-bookmark-label]');
    const readState = article.querySelector('[data-article-read-state]');
    const status = article.querySelector('[data-article-save-status]');
    const sizeButtons = [...article.querySelectorAll('[data-article-size]')];
    let read = article.dataset.read === 'true';
    let bookmarked = article.dataset.bookmarked === 'true';
    let pending = null;

    function setSize(size) {
      const validSize = ['small', 'standard', 'large'].includes(size) ? size : 'standard';
      document.body.dataset.articleSize = validSize;
      sizeButtons.forEach(button => button.setAttribute('aria-pressed', String(button.dataset.articleSize === validSize)));
      return validSize;
    }
    setSize(ui?.storage.get('fn-article-font-size'));
    sizeButtons.forEach(button => button.addEventListener('click', () => {
      ui?.storage.set('fn-article-font-size', setSize(button.dataset.articleSize));
    }));

    function render() {
      markButton.disabled = !!pending || read;
      finishButton.disabled = !!pending;
      bookmarkButton.disabled = !!pending;
      markButton.setAttribute('aria-busy', String(pending === 'read'));
      finishButton.setAttribute('aria-busy', String(pending === 'finish'));
      bookmarkButton.setAttribute('aria-busy', String(pending === 'bookmark'));
      markButton.textContent = pending === 'read' ? '保存中…' : (read ? '已标为已读' : '标为已读');
      finishButton.textContent = pending === 'finish' ? '保存后返回…' : '完成阅读并返回 →';
      bookmarkLabel.textContent = pending === 'bookmark' ? '保存中…' : (bookmarked ? '已收藏' : '收藏');
      bookmarkButton.setAttribute('aria-pressed', String(bookmarked));
      bookmarkButton.setAttribute('aria-label', bookmarked ? '取消收藏' : '收藏文章');
      readState.textContent = read ? '已读' : '未读';
      readState.classList.toggle('is-read', read);
    }

    function feedback(message, error = false) {
      status.textContent = message;
      status.classList.toggle('is-error', error);
      if (error) ui?.notify(message, 'error');
    }

    async function save(path, values, method = 'POST') {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 15000);
      try {
        const response = await fetch(path, {
          method,
          headers: {'Content-Type': 'application/x-www-form-urlencoded'},
          body: new URLSearchParams(values).toString(),
          signal: controller.signal
        });
        if (!response.ok) throw new Error('Request failed');
        return await response.text();
      } finally {
        clearTimeout(timeout);
      }
    }

    async function markRead(andReturn) {
      if (pending || (!andReturn && read)) return;
      pending = andReturn ? 'finish' : 'read';
      feedback('正在保存阅读状态…');
      render();
      try {
        const result = await save('/reader/read-all', {links: article.dataset.link});
        if (result !== 'ok') throw new Error('Unexpected response');
        read = true;
        feedback('已标为已读。');
        if (andReturn) window.location.assign(article.dataset.returnUrl);
      } catch (_) {
        feedback('暂时无法确认已读状态，请重试。', true);
      } finally {
        pending = null;
        render();
      }
    }

    markButton.addEventListener('click', () => markRead(false));
    finishButton.addEventListener('click', () => markRead(true));
    bookmarkButton.addEventListener('click', async () => {
      if (pending) return;
      pending = 'bookmark';
      feedback('正在保存收藏状态…');
      render();
      try {
        const result = await save('/reader/article/bookmark', {
          link: article.dataset.link,
          bookmarked: String(!bookmarked)
        }, 'PUT');
        if (!['bookmarked', 'removed'].includes(result)) throw new Error('Unexpected response');
        bookmarked = result === 'bookmarked';
        feedback(bookmarked ? '已加入收藏夹。' : '已取消收藏。');
      } catch (_) {
        feedback('暂时无法确认收藏状态，请重试。', true);
      } finally {
        pending = null;
        render();
      }
    });
    document.querySelectorAll('[data-article-return]').forEach(link => link.addEventListener('click', event => {
      if (!pending) return;
      event.preventDefault();
      feedback('正在保存，请稍候再返回。');
    }));
    render();
    document.querySelectorAll('[data-article-control]').forEach(control => control.hidden = false);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize);
  else initialize();
})();
