(() => {
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

  function initialize() {
    document.querySelectorAll('[data-article-link]').forEach(updateArticleLink);
    ['pointerdown', 'focusin', 'contextmenu', 'click', 'auxclick'].forEach(eventName => {
      document.addEventListener(eventName, event => {
        updateArticleLink(event.target.closest?.('[data-article-link]'));
      }, true);
    });
    window.addEventListener('hashchange', () => {
      document.querySelectorAll('[data-article-link]').forEach(updateArticleLink);
    });

    const article = document.querySelector('[data-article]');
    if (!article) return;
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
