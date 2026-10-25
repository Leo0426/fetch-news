// Reader page behaviour outside the Alpine component: keyboard shortcuts and the bookmark grid.
document.addEventListener('keydown', event => {
  if (!document.body.hasAttribute('data-reader') || event.defaultPrevented || event.isComposing
      || event.ctrlKey || event.metaKey || event.altKey
      || event.target.closest('input, textarea, select, [contenteditable="true"], [role="dialog"]')) return;
  if (event.key === '?') {
    event.preventDefault();
    document.getElementById('reader-shortcuts')?.click();
    return;
  }
  const cards = [...document.querySelectorAll('.rd-card[data-link]')].filter(card => card.getClientRects().length);
  let index = cards.findIndex(card => card.contains(document.activeElement));
  if (index < 0) index = cards.findIndex(card => card.classList.contains('fn-focused'));
  if (event.key === 'j' || event.key === 'k') {
    if (!cards.length) return;
    event.preventDefault();
    const next = event.key === 'j' ? Math.min(index + 1, cards.length - 1) : Math.max(index - 1, 0);
    cards.forEach(card => card.classList.remove('fn-focused'));
    cards[next].classList.add('fn-focused');
    cards[next].querySelector('.rd-card-title')?.focus({preventScroll: true});
    cards[next].scrollIntoView({block: 'nearest', behavior: window.FetchNewsUI.motionBehavior()});
  } else if (index >= 0 && ['o', 'b', 'r'].includes(event.key)) {
    event.preventDefault();
    const selector = {o: '.rd-card-title', b: '.rd-bookmark-btn', r: '.rd-read-dot'}[event.key];
    cards[index].querySelector(selector)?.click();
  }
});

const bookmarkGrid = document.querySelector('[data-bookmark-grid]');
if (bookmarkGrid) {
  const readRequests = new Set();
  async function markBookmarkRead(card) {
    const link = card.dataset.link;
    if (!link || readRequests.has(link)) return;
    readRequests.add(link);
    const state = card.querySelector('[data-bookmark-read-state]');
    const error = card.querySelector('[data-bookmark-read-error]');
    const retry = card.querySelector('[data-bookmark-read-retry]');
    const previousLabel = state.textContent;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    state.textContent = '保存已读中…';
    state.setAttribute('aria-busy', 'true');
    error.hidden = true;
    retry.disabled = true;
    try {
      const response = await fetch('/reader/read-all', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: new URLSearchParams({links: link}).toString(),
        keepalive: true,
        signal: controller.signal
      });
      if (!response.ok || await response.text() !== 'ok') throw new Error('Read state was not saved');
      card.dataset.read = 'true';
      state.textContent = '已读';
    } catch (failure) {
      state.textContent = previousLabel;
      error.hidden = false;
      window.FetchNewsUI.notify('暂时无法确认已读状态，请重试，或进入站内阅读后标记。', 'error');
    } finally {
      clearTimeout(timeout);
      readRequests.delete(link);
      retry.disabled = false;
      state.removeAttribute('aria-busy');
    }
  }
  bookmarkGrid.addEventListener('click', event => {
    if (event.defaultPrevented || event.button !== 0) return;
    const target = event.target.closest('.bm-card-title, [data-bookmark-read-retry]');
    const card = target?.closest('.bm-card[data-link]');
    if (card && bookmarkGrid.contains(card)) markBookmarkRead(card);
  });
  bookmarkGrid.addEventListener('auxclick', event => {
    if (event.defaultPrevented || event.button !== 1) return;
    const card = event.target.closest('.bm-card-title')?.closest('.bm-card[data-link]');
    if (card && bookmarkGrid.contains(card)) markBookmarkRead(card);
  });
  const focusAfterRemoval = new WeakMap();
  const reportedFailures = new WeakSet();
  document.addEventListener('htmx:beforeRequest', event => {
    const button = event.detail.elt;
    if (!button?.matches('.bm-remove')) return;
    reportedFailures.delete(button);
    const card = button.closest('.bm-card');
    const cards = [...bookmarkGrid.querySelectorAll('.bm-card')];
    const index = cards.indexOf(card);
    focusAfterRemoval.set(card, cards[index + 1]?.querySelector('.bm-remove')
      || cards[index - 1]?.querySelector('.bm-remove')
      || document.querySelector('#bookmark-empty a'));
  });
  ['htmx:responseError', 'htmx:sendError', 'htmx:timeout'].forEach(eventName => {
    document.addEventListener(eventName, event => {
      const button = event.detail.elt;
      if (!button?.matches('.bm-remove') || reportedFailures.has(button)) return;
      reportedFailures.add(button);
      focusAfterRemoval.delete(button.closest('.bm-card'));
      window.FetchNewsUI.notify('取消收藏失败，文章仍在收藏夹中，请重试。', 'error');
    });
  });
  new MutationObserver(records => {
    const count = bookmarkGrid.querySelectorAll('.bm-card').length;
    document.getElementById('bookmark-count').textContent = count + ' 条收藏';
    document.getElementById('bookmark-empty').hidden = count > 0;
    bookmarkGrid.hidden = count === 0;
    if (document.activeElement !== document.body) return;
    const candidate = records.flatMap(record => [...record.removedNodes])
      .map(card => focusAfterRemoval.get(card)).find(button => button?.isConnected);
    const next = candidate || bookmarkGrid.querySelector('.bm-remove') || document.querySelector('#bookmark-empty a');
    next?.focus();
  }).observe(bookmarkGrid, {childList: true});
}
