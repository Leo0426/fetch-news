(() => {
  'use strict';

  const pages = {
    stats: ['/index/stats', '数据统计'],
    ai: ['/index/ai', 'AI 设置'],
    radar: ['/index/radar', '新闻雷达配置'],
    'radar-tasks': ['/index/radar/tasks', 'AI 任务'],
    'radar-evaluation': ['/index/radar/evaluation', '模型评测'],
    credentials: ['/index/credentials', '凭据配置'],
    feishu: ['/index/feishu', '飞书推送'],
    health: ['/index/health', 'Feed 健康'],
    logs: ['/index/logs', '抓取日志']
  };
  const activeRequests = new Set();
  const failedRequests = new WeakSet();
  const contentRequests = new WeakMap();
  const summaryRequests = new WeakMap();
  const mobileQuery = window.matchMedia('(max-width: 860px)');
  let shell;
  let opener;
  let navigationVersion = 0;
  let pendingNavigation;
  let loadedTimer;
  let previousOverflow;

  function focusable(container) {
    return Array.from(container.querySelectorAll(
      'a[href], summary, button:not([disabled]), input:not([type="hidden"]):not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
    )).filter(element => element.getClientRects().length > 0 && !element.closest('[inert]'));
  }

  function focusModal() {
    const modal = document.querySelector('.modal');
    const error = modal.querySelector('.form-msg.err');
    const input = focusable(modal).find(element => element.matches('input:not([readonly]), select, textarea'));
    (error || input || focusable(modal)[0] || modal).focus({ preventScroll: true });
  }

  function updateOverlays() {
    const menuOpen = shell.isMobile && shell.sbOpen;
    document.getElementById('app-shell').inert = shell.modal;
    document.getElementById('console-main').inert = menuOpen;
    const locked = shell.modal || menuOpen;
    if (locked && previousOverflow === undefined) {
      previousOverflow = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
    } else if (!locked && previousOverflow !== undefined) {
      document.body.style.overflow = previousOverflow;
      previousOverflow = undefined;
    }
  }

  function updateLoading() {
    const loading = activeRequests.size > 0;
    document.getElementById('main-content').setAttribute('aria-busy', String(loading));
    const topbar = document.querySelector('.topbar');
    topbar.classList.toggle('htmx-loading', loading);
    if (loading) {
      clearTimeout(loadedTimer);
      topbar.classList.remove('htmx-loaded');
    }
  }

  function summaryRequest(elt) {
    if (!elt) return null;
    if (elt.getAttribute('hx-post') === '/index/items/summarize-batch') {
      const view = elt.closest('.items-view');
      return {kind: 'batch', button: elt, view,
        path: view?.dataset.path || elt.closest('form')?.querySelector('input[name="path"]')?.value,
        page: Number(view?.dataset.page || 0)};
    }
    const region = elt.closest('.item-summary-gen');
    if (!region) return null;
    const post = elt.getAttribute('hx-post');
    const get = elt.getAttribute('hx-get') || region.getAttribute('hx-get');
    if (post !== '/index/items/summarize' && !get) return null;
    let link = region.querySelector('input[name="link"]')?.value;
    if (!link && get) {
      try {
        const url = new URL(get, window.location.href);
        if (url.pathname !== '/index/items/summary-status' || url.origin !== window.location.origin) return null;
        link = url.searchParams.get('link');
      } catch (_) {
        return null;
      }
    }
    return {kind: 'item', region, link};
  }

  function replaceRequestElement(previous, replacement) {
    const hadFocus = previous.contains(document.activeElement);
    previous.before(replacement);
    htmx.remove(previous);
    htmx.process(replacement);
    if (hadFocus) (replacement.matches('button') ? replacement : replacement.querySelector('button'))?.focus({preventScroll: true});
  }

  function statusCheckButton(url, label, target, className) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = className;
    button.textContent = label;
    button.setAttribute('hx-get', url);
    button.setAttribute('hx-target', target);
    button.setAttribute('hx-swap', target === '#main-content' ? 'innerHTML' : 'outerHTML');
    button.setAttribute('hx-disabled-elt', 'this');
    return button;
  }

  function batchItemsUrl(request) {
    return '/index/items?' + new URLSearchParams({path: request.path || '', page: request.page});
  }

  function recoverSummaryRequest(event) {
    const request = summaryRequests.get(event.detail.xhr) || summaryRequest(event.detail.elt);
    if (!request) return false;
    const content = contentRequests.get(event.detail.xhr);
    if (content && content.version !== navigationVersion) return true;
    if (request.kind === 'batch') {
      if (!request.button.isConnected || (request.view && !request.view.isConnected)) return true;
      const message = request.button.closest('.items-header')?.querySelector('#batch-sum-msg');
      if (message) {
        message.textContent = '提交结果暂时无法确认，请刷新列表查看已排队任务。';
        message.setAttribute('role', 'alert');
      }
      replaceRequestElement(request.button, statusCheckButton(batchItemsUrl(request), '刷新列表确认', '#main-content', 'btn'));
      return true;
    }
    if (!request.region.isConnected) return true;
    const replacement = document.createElement('div');
    replacement.className = 'item-summary-gen';
    const message = document.createElement('p');
    message.className = 'item-summary-error';
    message.setAttribute('role', 'alert');
    message.textContent = request.link
      ? '摘要状态暂时无法确认，请重新查看状态。'
      : '摘要状态暂时无法确认，请刷新列表查看。';
    replacement.append(message);
    if (request.link) {
      const url = '/index/items/summary-status?' + new URLSearchParams({link: request.link});
      replacement.append(statusCheckButton(url, '重新查看状态', 'closest .item-summary-gen', 'sum-gen-btn'));
    }
    replaceRequestElement(request.region, replacement);
    return true;
  }

  function notifyFailure(event) {
    const xhr = event.detail.xhr;
    if (xhr && failedRequests.has(xhr)) return;
    if (xhr) failedRequests.add(xhr);
    if (recoverSummaryRequest(event)) return;
    if (event.detail.elt?.closest('[data-ai-settings]') && xhr?.responseText) {
      const error = new DOMParser().parseFromString(xhr.responseText, 'text/html').querySelector('.form-msg.err');
      if (error?.textContent) { window.FetchNewsUI.notify(error.textContent, 'error'); return; }
    }
    const message = xhr?.status
      ? `操作未完成（${xhr.status}），请重试。`
      : '连接中断，请检查网络后重试。';
    window.FetchNewsUI.notify(message, 'error');
  }

  document.addEventListener('alpine:init', () => {
    Alpine.data('appShell', () => ({
      modal: false,
      activePage: 'stats',
      pageTitle: '数据统计',
      sbOpen: false,
      isMobile: mobileQuery.matches,
      init() {
        shell = this;
        this.$watch('modal', open => {
          updateOverlays();
          this.$nextTick(() => {
            if (open) focusModal();
            else if (opener?.isConnected) opener.focus({ preventScroll: true });
            else document.getElementById('main-content').focus({ preventScroll: true });
          });
        });
        this.$watch('sbOpen', open => {
          updateOverlays();
          this.$nextTick(() => {
            if (open && this.isMobile) {
              const sidebar = document.getElementById('console-sidebar');
              (sidebar.querySelector('[aria-current="page"]') || focusable(sidebar)[0])?.focus();
            } else if (document.getElementById('console-sidebar').contains(document.activeElement) && this.isMobile) {
              document.querySelector('.hamburger').focus({ preventScroll: true });
            }
          });
        });
        mobileQuery.addEventListener('change', event => {
          this.isMobile = event.matches;
          this.sbOpen = false;
          updateOverlays();
        });
        window.addEventListener('hashchange', () => this.loadHash());
        this.$nextTick(() => this.loadHash(true));
      },
      navigate(event, hash) {
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        this.sbOpen = false;
        if (window.location.hash.slice(1) === hash) this.loadHash();
        else window.location.hash = hash;
      },
      loadHash(initial = false) {
        const hash = window.location.hash.slice(1);
        if (hash === 'main-content') return;
        let key = hash || 'stats';
        let page = Object.hasOwn(pages, key) ? pages[key] : null;
        if (hash.startsWith('cat=')) {
          try {
            const category = decodeURIComponent(hash.slice(4));
            if (category && Array.from(document.querySelectorAll('[data-cat]')).some(link => link.dataset.cat === category)) {
              key = 'cat:' + category;
              page = ['/index/category?name=' + encodeURIComponent(category), category];
            }
          } catch (_) {
            window.FetchNewsUI.notify('分类链接无效，已返回数据统计。', 'error');
          }
        }
        if (!page) {
          key = 'stats';
          page = pages.stats;
          history.replaceState(null, '', window.location.pathname + window.location.search + '#stats');
        }
        this.sbOpen = false;
        if (initial && key === 'stats') {
          document.title = this.pageTitle + ' · fetch-news';
          return;
        }
        navigationVersion += 1;
        pendingNavigation = { key, title: page[1] };
        htmx.ajax('GET', page[0], {
          source: '#console-sidebar', target: '#main-content', swap: 'innerHTML'
        }).catch(() => {});
      }
    }));
  });

  document.addEventListener('htmx:beforeRequest', event => {
    const { target, xhr, elt } = event.detail;
    const summary = summaryRequest(elt);
    if (summary && xhr) summaryRequests.set(xhr, summary);
    if (target?.id === 'modal-inner' && !shell.modal) opener = document.activeElement;
    if (target && (target.id === 'main-content' || document.getElementById('main-content').contains(target))) {
      contentRequests.set(xhr, {
        version: navigationVersion,
        navigation: elt?.id === 'console-sidebar' ? pendingNavigation : null
      });
      activeRequests.add(xhr);
      updateLoading();
    }
  });

  document.addEventListener('htmx:beforeSwap', event => {
    const request = contentRequests.get(event.detail.xhr);
    if (request && request.version !== navigationVersion) event.detail.shouldSwap = false;
  });

  document.addEventListener('htmx:afterRequest', event => {
    const { xhr, successful } = event.detail;
    if (!activeRequests.delete(xhr)) return;
    updateLoading();
    if (activeRequests.size === 0 && successful) {
      const topbar = document.querySelector('.topbar');
      topbar.classList.add('htmx-loaded');
      loadedTimer = setTimeout(() => topbar.classList.remove('htmx-loaded'), 500);
    }
  });

  document.addEventListener('htmx:afterSwap', event => {
    if (event.detail.target?.id === 'modal-inner' && event.detail.target.innerHTML.trim()) {
      const alreadyOpen = shell.modal;
      shell.modal = true;
      if (alreadyOpen) shell.$nextTick(focusModal);
    }
    const request = contentRequests.get(event.detail.xhr);
    const summary = summaryRequests.get(event.detail.xhr);
    if (summary?.kind === 'batch' && event.detail.target?.id === 'batch-sum-msg'
        && summary.button.isConnected && (!summary.view || summary.view.isConnected)
        && (!request || request.version === navigationVersion)) {
      summaryRequests.delete(event.detail.xhr);
      const result = event.detail.target;
      if (result.querySelector('.sum-status.ok')) {
        window.FetchNewsUI.notify(result.textContent.trim());
        replaceRequestElement(summary.button, statusCheckButton(batchItemsUrl(summary), '刷新列表确认', '#main-content', 'btn'));
        htmx.ajax('GET', batchItemsUrl(summary), {source: '#main-content', target: '#main-content', swap: 'innerHTML'}).catch(() => {});
      } else if (result.querySelector('.sum-status.err')) {
        replaceRequestElement(summary.button, statusCheckButton(batchItemsUrl(summary), '刷新列表确认', '#main-content', 'btn'));
      }
    }
    if (event.detail.target?.id === 'main-content' && request?.navigation && request.version === navigationVersion) {
      shell.activePage = request.navigation.key;
      shell.pageTitle = request.navigation.title;
      document.title = request.navigation.title + ' · fetch-news';
      shell.$nextTick(() => {
        event.detail.target.focus({ preventScroll: true });
        window.scrollTo({ top: 0, behavior: window.FetchNewsUI.motionBehavior() });
      });
    }
  });

  for (const eventName of ['htmx:responseError', 'htmx:sendError', 'htmx:timeout']) {
    document.addEventListener(eventName, notifyFailure);
  }
  window.addEventListener('closemodal', () => { shell.modal = false; });
  document.addEventListener('keydown', event => {
    if (!shell) return;
    if (event.key === 'Escape') {
      if (shell.modal) shell.modal = false;
      else shell.sbOpen = false;
    }
    if (event.key !== 'Tab') return;
    const container = shell.modal ? document.querySelector('.modal')
      : shell.isMobile && shell.sbOpen ? document.getElementById('console-sidebar') : null;
    if (!container) return;
    const elements = focusable(container);
    const first = elements[0] || container;
    const last = elements[elements.length - 1] || container;
    if (!elements.includes(document.activeElement) || (event.shiftKey && document.activeElement === first)) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  });
})();
