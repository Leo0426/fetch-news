(() => {
  'use strict';

  const pages = {
    stats: ['/index/stats', '数据统计'],
    subscriptions: ['/index/subscriptions', '订阅来源'],
    ai: ['/index/ai', 'AI 设置'],
    radar: ['/index/radar', '新闻雷达配置'],
    'radar-tasks': ['/index/radar/tasks', 'AI 任务'],
    'radar-evaluation': ['/index/radar/evaluation', '模型评测'],
    'radar-delivery': ['/index/radar/delivery', '日报投递'],
    credentials: ['/index/credentials', '凭据配置'],
    feishu: ['/index/feishu', '飞书推送'],
    health: ['/index/health', 'Feed 健康'],
    logs: ['/index/logs', '抓取日志']
  };
  const activeRequests = new Set();
  const failedRequests = new WeakSet();
  const contentRequests = new WeakMap();
  const summaryRequests = new WeakMap();
  const subscriptionRequests = new WeakMap();
  const pendingSubscriptions = new WeakMap();
  const mobileQuery = window.matchMedia('(max-width: 860px)');
  let shell;
  let opener;
  let modalReturnCard;
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

  function restoreModalFocus() {
    const card = modalReturnCard;
    modalReturnCard = null;
    if (shell.modal) return;
    const heading = card?.isConnected ? card.querySelector('.subscription-name') : null;
    if (heading?.isConnected) {
      heading.focus({preventScroll: true});
      card.scrollIntoView({block: 'nearest', behavior: window.FetchNewsUI.motionBehavior()});
    } else if (opener?.isConnected) {
      opener.focus({preventScroll: true});
    } else {
      document.getElementById('main-content').focus({preventScroll: true});
    }
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

  function subscriptionRequest(elt) {
    const card = elt?.closest('.subscription-card');
    if (!card) return null;
    const kind = elt.matches('[data-subscription-action]') ? 'action'
      : elt.matches('[data-subscription-check]') ? 'check'
      : elt === card ? 'poll' : null;
    return kind ? {card, kind, wasSubscribed: card.dataset.subscribed === 'true'} : null;
  }

  function recoverSubscriptionRequest(event) {
    const request = subscriptionRequests.get(event.detail.xhr) || subscriptionRequest(event.detail.elt);
    if (!request) return false;
    const {card, kind} = request;
    const content = contentRequests.get(event.detail.xhr);
    if (!card.isConnected || (content && content.version !== navigationVersion)
        || (kind === 'poll' && pendingSubscriptions.has(card))) return true;
    const error = event.detail.xhr?.responseText
      ? new DOMParser().parseFromString(event.detail.xhr.responseText, 'text/html').querySelector('.form-msg.err') : null;
    const message = error?.textContent?.trim()
      || (kind === 'action' ? '操作结果暂时无法确认，请先重新查看状态。' : '暂时无法更新抓取状态，请重新查看。');
    card.dataset.subscriptionPollPaused = 'true';
    card.querySelector('[data-subscription-error]').hidden = false;
    card.querySelector('[data-subscription-error-message]').textContent = message;
    card.querySelectorAll('[data-subscription-action] button, [data-subscription-action] select').forEach(control => { control.disabled = true; });
    if (kind !== 'poll') window.FetchNewsUI.notify(message, 'error');
    return true;
  }

  function finishSubscriptionRequest(xhr) {
    const request = subscriptionRequests.get(xhr);
    if (!request || request.kind === 'poll') return;
    const {card} = request;
    if (pendingSubscriptions.get(card) !== xhr) return;
    pendingSubscriptions.delete(card);
    if (!card.isConnected) return;
    card.removeAttribute('aria-busy');
    request.controls.forEach(({control, disabled}) => { control.disabled = disabled; });
    if (card.dataset.subscriptionPollPaused === 'true') {
      card.querySelectorAll('[data-subscription-action] button, [data-subscription-action] select').forEach(control => { control.disabled = true; });
    }
  }

  function notifyFailure(event) {
    const xhr = event.detail.xhr;
    if (xhr && failedRequests.has(xhr)) return;
    if (xhr) failedRequests.add(xhr);
    if (recoverSummaryRequest(event)) return;
    if (recoverSubscriptionRequest(event)) return;
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
    Alpine.data('aiProviderForm', () => ({
      protocol: '',
      model: '',
      models: [],
      availableModels: [],
      draftModel: '',
      modelError: '',
      modelsLoading: false,
      modelMessage: '',
      modelRequest: 0,
      modelController: null,
      init() {
        this.protocol = this.$root.dataset.protocol;
        this.model = this.$root.elements.model.value;
        this.models = Array.from(this.$root.querySelectorAll('[data-saved-model]'), field => field.value);
      },
      openModelDialog() {
        this.draftModel = '';
        this.modelError = '';
        this.$refs.modelDialog.showModal();
        this.$refs.modelId.focus();
      },
      addModel() {
        const id = this.draftModel.trim();
        if (!id) { this.modelError = '请填写模型 ID，或从接口列表中选择。'; return; }
        if (this.models.includes(id)) { this.modelError = '此模型已在列表中。'; return; }
        this.models.push(id);
        if (!this.model) this.model = id;
        this.$refs.modelDialog.close();
      },
      removeModel(id) {
        if (id !== this.model) this.models = this.models.filter(value => value !== id);
      },
      invalidateModels() {
        this.modelRequest += 1;
        this.modelController?.abort();
        this.availableModels = [];
        this.modelMessage = '';
        this.modelsLoading = false;
      },
      providerChanged(event) {
        if (['host', 'protocol', 'apiKey', 'clearKey'].includes(event.target.name)) this.invalidateModels();
      },
      destroy() {
        this.invalidateModels();
        this.$refs.modelDialog?.close();
      },
      async loadModels() {
        const form = this.$root;
        if (!form.elements.host.checkValidity()) {
          this.modelMessage = '请先关闭弹窗并填写有效的 API 基础地址。';
          return;
        }
        this.invalidateModels();
        const request = this.modelRequest;
        this.modelsLoading = true;
        this.modelController = new AbortController();
        const body = new URLSearchParams();
        for (const name of ['profileId', 'host', 'protocol', 'apiKey', 'clearKey']) {
          const field = form.elements[name];
          if (field && (field.type !== 'checkbox' || field.checked)) body.set(name, field.value);
        }
        try {
          const response = await fetch('/index/ai/models', {method: 'POST', body, signal: this.modelController.signal});
          const result = await response.json();
          if (request !== this.modelRequest) return;
          this.availableModels = response.ok && Array.isArray(result.models) ? result.models : [];
          this.modelMessage = result.message || '获取失败，请检查地址和密钥，或手动填写模型 ID。';
        } catch (error) {
          if (request === this.modelRequest) this.modelMessage = '获取失败，请检查连接后重试，或手动填写模型 ID。';
        } finally {
          if (request === this.modelRequest) this.modelsLoading = false;
        }
      }
    }));

    Alpine.data('appShell', () => ({
      modal: false,
      activePage: 'stats',
      pageTitle: '数据统计',
      sbOpen: false,
      isMobile: mobileQuery.matches,
      init() {
        shell = this;
        this.$watch('modal', open => {
          if (open) modalReturnCard = null;
          updateOverlays();
          this.$nextTick(() => {
            if (open !== this.modal) return;
            if (open) focusModal();
            else restoreModalFocus();
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
    const subscription = subscriptionRequest(elt);
    if (subscription) {
      const {card, kind} = subscription;
      if (kind !== 'check' && (card.dataset.subscriptionPollPaused === 'true' || pendingSubscriptions.has(card))) {
        event.preventDefault();
        return;
      }
      subscriptionRequests.set(xhr, subscription);
      if (kind !== 'poll') {
        pendingSubscriptions.set(card, xhr);
        subscription.controls = Array.from(card.querySelectorAll('button, select'), control => ({control, disabled: control.disabled}));
        subscription.controls.forEach(({control}) => { control.disabled = true; });
        card.setAttribute('aria-busy', 'true');
      }
    }
    const summary = summaryRequest(elt);
    if (summary && xhr) summaryRequests.set(xhr, summary);
    if (target?.id === 'modal-inner' && !shell.modal) opener = document.activeElement;
    if (target && (target.id === 'main-content' || document.getElementById('main-content').contains(target))) {
      contentRequests.set(xhr, {
        version: navigationVersion,
        navigation: elt?.id === 'console-sidebar' ? pendingNavigation : null
      });
      if (subscription?.kind !== 'poll') {
        activeRequests.add(xhr);
        updateLoading();
      }
    }
  });

  document.addEventListener('htmx:beforeSwap', event => {
    if (event.detail.requestConfig?.elt?.matches('form[data-subscription-create]')
        && [422, 503].includes(event.detail.xhr.status)
        && event.detail.target?.id === 'modal-inner') {
      event.detail.shouldSwap = true;
      event.detail.isError = false;
    }
    const request = contentRequests.get(event.detail.xhr);
    if (request && request.version !== navigationVersion) event.detail.shouldSwap = false;
    const subscription = subscriptionRequests.get(event.detail.xhr);
    if (subscription?.kind === 'poll' && pendingSubscriptions.has(subscription.card)) event.detail.shouldSwap = false;
    if (subscription && !subscription.card.isConnected) event.detail.shouldSwap = false;
    if (subscription && event.detail.shouldSwap && subscription.card.contains(document.activeElement)) {
      const focused = document.activeElement;
      subscription.focused = {
        post: focused.closest('form')?.getAttribute('hx-post'),
        get: focused.getAttribute('hx-get'),
        href: focused.getAttribute('href')
      };
    }
  });

  document.addEventListener('htmx:afterRequest', event => {
    const { xhr, successful } = event.detail;
    finishSubscriptionRequest(xhr);
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
    const subscription = subscriptionRequests.get(event.detail.xhr);
    if (subscription && !subscription.updated && (!request || request.version === navigationVersion)) {
      const replacement = document.getElementById(subscription.card.id);
      if (replacement && replacement !== subscription.card) {
        subscription.updated = true;
        const count = replacement.closest('.subscriptions-view')?.querySelector('[data-subscription-count]');
        const delta = Number(replacement.dataset.subscribed === 'true') - Number(subscription.wasSubscribed);
        if (count && delta) count.textContent = String(Math.max(0, Number(count.textContent) + delta));
        if (subscription.focused) {
          const {post, get, href} = subscription.focused;
          const control = Array.from(replacement.querySelectorAll('button:not([disabled]), a[href]')).find(element =>
            (post && element.closest('form')?.getAttribute('hx-post') === post)
            || (get && element.getAttribute('hx-get') === get)
            || (href && element.getAttribute('href') === href));
          (control || replacement.querySelector('.subscription-name')).focus({preventScroll: true});
        }
      }
    }
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
  document.addEventListener('subscriptionCreated', event => {
    const card = document.getElementById(event.detail.cardId);
    if (!shell || !card) return;
    modalReturnCard = card;
    if (shell.modal) shell.modal = false;
    else shell.$nextTick(restoreModalFocus);
  });
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
