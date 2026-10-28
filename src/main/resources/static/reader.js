function readerState() {
  const ui = window.FetchNewsUI;
  const savedFilter = ui.storage.get('fn-filter');
  const queueMode = document.body.dataset.readerMode === 'unread';
  const summaryRequests = new Map();
  const summaryTimers = new Map();
  let summaryActive = true;
  let pauseSummaries;
  let resumeSummaries;
  return {
    reads: Object.fromEntries((_fnReads || []).map(link => [link, true])),
    bookmarks: Object.fromEntries((_fnBookmarks || []).map(link => [link, true])),
    filter: !queueMode && ['all', 'unread', 'read', 'bookmarked'].includes(savedFilter) ? savedFilter : 'unread',
    queueMode,
    queueBookmarkedOnly: queueMode && document.body.dataset.bookmarkedOnly === 'true',
    queueTotal: Number(document.body.dataset.queueTotal || 0),
    activeTag: 'all',
    pickFilter: 'all',
    pickVerdicts: {},
    pickVotes: {},
    searchQuery: '',
    listView: !!ui.storage.get('fn-list-view'),
    readPending: {},
    bookmarkPending: {},
    readAllPending: false,
    summarizing: {},
    aiStates: {},
    aiEnabled: {},
    aiSummaries: {},
    aiErrors: {},
    aiUncertain: {},

    init() {
      window.FetchNewsReadingContext?.attach(this);
      this.cards().forEach(card => {
        const link = card.dataset.link;
        this.aiStates[link] = card.dataset.summaryState || 'none';
        this.aiEnabled[link] = card.dataset.aiEnabled === 'true';
        this.pickVerdicts[link + '|must'] = card.dataset.verdictMust || '';
        this.pickVerdicts[link + '|quality'] = card.dataset.verdictQuality || '';
        if (this.aiStates[link] === 'error') this.aiErrors[link] = card.dataset.summaryError || '摘要未完成，可以重试。';
        if (['queued', 'running'].includes(this.aiStates[link])) this.scheduleSummaryCheck(link);
      });
      pauseSummaries = () => this.stopSummaryChecks();
      resumeSummaries = () => {
        summaryActive = true;
        this.cards().forEach(card => {
          const link = card.dataset.link;
          if (['queued', 'running'].includes(this.summaryState(link)) || this.aiUncertain[link]) {
            this.checkSummary(link);
          }
        });
      };
      window.addEventListener('pagehide', pauseSummaries);
      window.addEventListener('pageshow', resumeSummaries);
      this.$nextTick(() => this.buildToc());
      ['filter', 'activeTag', 'pickFilter', 'searchQuery', 'reads', 'bookmarks', 'listView'].forEach(key => {
        this.$watch(key, () => this.$nextTick(() => this.updateToc()));
      });
    },

    cards(scope = document) {
      return [...scope.querySelectorAll('.rd-card[data-link]')];
    },
    toggleListView() {
      this.listView = !this.listView;
      this.listView ? ui.storage.set('fn-list-view', '1') : ui.storage.remove('fn-list-view');
    },
    setFilter(filter) {
      this.filter = filter;
      ui.storage.set('fn-filter', filter);
    },
    resetFilters() {
      this.searchQuery = '';
      this.activeTag = 'all';
      this.pickFilter = 'all';
      this.setFilter('all');
    },
    itemVisible(card) {
      const tag = card.closest('.rd-section')?.dataset.tag;
      const query = this.searchQuery.trim().toLowerCase();
      const link = card.dataset.link;
      return (this.activeTag === 'all' || tag === this.activeTag)
        && (this.pickFilter === 'all' || ((card.dataset.picks || '').split(' ').includes(this.pickFilter)
          && this.pickVerdict(link, this.pickFilter) !== 'down'))
        && (!query || (card.dataset.title || '').toLowerCase().includes(query))
        && (!this.queueBookmarkedOnly || this.isBookmarked(link))
        && (this.filter === 'all'
          || (this.filter === 'unread' && !this.isRead(link))
          || (this.filter === 'read' && this.isRead(link))
          || (this.filter === 'bookmarked' && this.isBookmarked(link)));
    },
    visibleCount(scope = document) {
      return this.cards(scope).filter(card => this.itemVisible(card)).length;
    },
    queueRemainingCount() {
      const removed = this.cards().filter(card => this.isRead(card.dataset.link)
        || (this.queueBookmarkedOnly && !this.isBookmarked(card.dataset.link))).length;
      return Math.max(0, this.queueTotal - removed);
    },
    queueBusy() {
      return this.anyReadPending() || Object.values(this.bookmarkPending).some(Boolean);
    },
    guardQueueNavigation(event) {
      if (!this.queueMode || !this.queueBusy() || event.ctrlKey || event.metaKey || event.shiftKey) return;
      const link = event.target.closest('a[href]');
      if (link && link.target !== '_blank' && !link.getAttribute('href').startsWith('#')) {
        event.preventDefault();
      }
    },
    sectionVisible(section) {
      return this.visibleCount(section) > 0;
    },
    captureReadingFocus(link) {
      const element = document.activeElement;
      const card = element?.closest('.rd-card[data-link]');
      if (!card || card.dataset.link !== link || !this.itemVisible(card)) return null;
      return {element, card, cards: this.cards().filter(candidate => this.itemVisible(candidate))};
    },
    advanceReadingFocus(previous) {
      if (!previous || this.itemVisible(previous.card)) return;
      this.$nextTick(() => {
        const active = document.activeElement;
        if (this.itemVisible(previous.card)
            || (active !== document.body && active !== previous.element && !previous.card.contains(active))) return;
        const index = previous.cards.indexOf(previous.card);
        const candidates = [...previous.cards.slice(index + 1), ...previous.cards.slice(0, index).reverse()];
        const next = candidates.find(card => card.isConnected && this.itemVisible(card));
        this.cards().forEach(card => card.classList.remove('fn-focused'));
        if (next) {
          next.classList.add('fn-focused');
          next.querySelector('.rd-card-title')?.focus({preventScroll: true});
          next.scrollIntoView({block: 'nearest', behavior: ui.motionBehavior()});
        } else {
          document.querySelector('.rd-empty .reader-empty-action')?.focus();
        }
      });
    },
    isRead(link) { return !!this.reads[link]; },
    isBookmarked(link) { return !!this.bookmarks[link]; },
    unreadCount() { return this.cards().filter(card => !this.isRead(card.dataset.link)).length; },
    bookmarkCount() { return this.cards().filter(card => this.isBookmarked(card.dataset.link)).length; },
    readBusy(link) { return this.readAllPending || !!this.readPending[link]; },
    anyReadPending() { return this.readAllPending || Object.values(this.readPending).some(Boolean); },

    async save(path, values, method = 'POST') {
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
    },
    async setReadState(link, markOnly) {
      if (this.readBusy(link) || (markOnly && this.isRead(link))) return;
      const previous = this.isRead(link);
      const previousFocus = this.captureReadingFocus(link);
      this.readPending = {...this.readPending, [link]: true};
      this.reads = {...this.reads, [link]: !previous};
      this.advanceReadingFocus(previousFocus);
      try {
        const result = await this.save('/reader/read', {link, read: String(markOnly || !previous)}, 'PUT');
        if (!['read', 'unread'].includes(result)) throw new Error('Unexpected response');
        this.reads = {...this.reads, [link]: result === 'read'};
      } catch (error) {
        this.reads = {...this.reads, [link]: previous};
        ui.notify('暂时无法确认已读状态，请重试。', 'error');
      } finally {
        this.readPending = {...this.readPending, [link]: false};
      }
    },
    markRead(link) { return this.setReadState(link, true); },
    toggleRead(link) { return this.setReadState(link, this.queueMode); },
    async markAllRead() {
      if (this.queueMode ? this.queueBusy() : this.anyReadPending()) return;
      const links = [...new Set(this.cards().filter(card => !this.queueMode || this.itemVisible(card))
        .map(card => card.dataset.link))];
      if (!links.length || !this.unreadCount()) return;
      const previous = {...this.reads};
      this.readAllPending = true;
      this.reads = {...this.reads, ...Object.fromEntries(links.map(link => [link, true]))};
      try {
        const result = await this.save('/reader/read-all', links.map(link => ['links', link]));
        if (result !== 'ok') throw new Error('Unexpected response');
        ui.notify(this.queueMode ? '本页文章已标为已读，可以继续下一页。' : '当前时间范围内的文章已全部标为已读。');
      } catch (error) {
        this.reads = previous;
        ui.notify('暂时无法确认批量已读状态，请重试。', 'error');
      } finally {
        this.readAllPending = false;
      }
    },
    async toggleBookmark(link) {
      if (this.bookmarkPending[link]) return;
      const previous = this.isBookmarked(link);
      const previousFocus = this.captureReadingFocus(link);
      this.bookmarkPending = {...this.bookmarkPending, [link]: true};
      this.bookmarks = {...this.bookmarks, [link]: !previous};
      this.advanceReadingFocus(previousFocus);
      try {
        const result = await this.save('/reader/article/bookmark', {link, bookmarked: String(!previous)}, 'PUT');
        if (!['bookmarked', 'removed'].includes(result)) throw new Error('Unexpected response');
        this.bookmarks = {...this.bookmarks, [link]: result === 'bookmarked'};
        ui.notify(result === 'bookmarked' ? '已加入收藏夹。' : '已取消收藏。');
      } catch (error) {
        this.bookmarks = {...this.bookmarks, [link]: previous};
        ui.notify('暂时无法确认收藏状态，请重试。', 'error');
      } finally {
        this.bookmarkPending = {...this.bookmarkPending, [link]: false};
      }
    },
    pickVerdict(link, kind) { return this.pickVerdicts[link + '|' + kind] || ''; },
    pickVotePending(link, kind) { return !!this.pickVotes[link + '|' + kind]; },
    async votePick(link, kind, verdict) {
      const key = link + '|' + kind;
      if (this.pickVotes[key]) return;
      const previous = this.pickVerdict(link, kind);
      const next = previous === verdict ? '' : verdict;
      this.pickVotes = {...this.pickVotes, [key]: true};
      this.pickVerdicts = {...this.pickVerdicts, [key]: next};
      try {
        const result = await this.save('/reader/picks/feedback', {link, kind, verdict: next || 'none'}, 'PUT');
        if (result !== (next || 'none')) throw new Error('Unexpected response');
        if (next === 'down') ui.notify('已记下，这篇不再标记，之后的挑选会参考你的反馈。');
      } catch (error) {
        this.pickVerdicts = {...this.pickVerdicts, [key]: previous};
        ui.notify('反馈暂时无法保存，请重试。', 'error');
      } finally {
        this.pickVotes = {...this.pickVotes, [key]: false};
      }
    },
    summaryState(link) { return this.aiStates[link] || 'none'; },
    isSummarizing(link) {
      return !!this.summarizing[link] || ['queued', 'running'].includes(this.summaryState(link));
    },
    summary(link) { return this.aiSummaries[link] || null; },
    summaryError(link) { return this.aiErrors[link] || null; },
    summaryLabel(link) {
      if (this.aiUncertain[link]) return '状态暂时无法确认';
      // Background polls of a pending job must keep the current label, or it flickers every 2s.
      if (this.summarizing[link] && !['queued', 'running'].includes(this.summaryState(link))) return '正在确认摘要状态…';
      return {queued: '已加入队列 · 排队中', running: '正在生成摘要', success: 'AI 摘要', error: '摘要生成失败'}[this.summaryState(link)] || '';
    },
    scheduleSummaryCheck(link) {
      clearTimeout(summaryTimers.get(link));
      summaryTimers.delete(link);
      if (!summaryActive || !['queued', 'running'].includes(this.summaryState(link))) return;
      summaryTimers.set(link, setTimeout(() => {
        summaryTimers.delete(link);
        this.checkSummary(link);
      }, 2000));
    },
    applySummaryStatus(link, result) {
      if (!result || !['none', 'queued', 'running', 'success', 'error'].includes(result.state)
          || (result.state === 'success' && (typeof result.summary !== 'string' || !result.summary.trim()))
          || (result.error != null && typeof result.error !== 'string')) {
        throw new Error('Unexpected summary status');
      }
      this.aiStates = {...this.aiStates, [link]: result.state};
      this.aiErrors = {...this.aiErrors, [link]: result.state === 'error' ? (result.error || '摘要未完成，可以重试。') : null};
      this.aiUncertain = {...this.aiUncertain, [link]: null};
      if (result.state === 'success') this.aiSummaries = {...this.aiSummaries, [link]: result.summary};
      this.scheduleSummaryCheck(link);
    },
    async requestSummary(link, title, submit) {
      if (!summaryActive || summaryRequests.has(link)) return;
      clearTimeout(summaryTimers.get(link));
      summaryTimers.delete(link);
      const controller = new AbortController();
      const request = {controller, submit, timeout: setTimeout(() => controller.abort(), 15000)};
      summaryRequests.set(link, request);
      this.summarizing = {...this.summarizing, [link]: true};
      try {
        const response = await fetch(submit ? '/reader/summarize' : '/reader/summary-status?' + new URLSearchParams({link}), {
          method: submit ? 'POST' : 'GET',
          ...(submit ? {
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: new URLSearchParams({link, title: title || ''}).toString()
          } : {}),
          signal: controller.signal
        });
        const result = await response.json();
        if (!response.ok && (!submit || ![400, 404].includes(response.status) || result.state !== 'error')) throw new Error('Request failed');
        if (summaryActive && summaryRequests.get(link) === request) this.applySummaryStatus(link, result);
      } catch (error) {
        if (summaryActive && summaryRequests.get(link) === request) {
          this.aiUncertain = {...this.aiUncertain, [link]: '状态暂时无法确认，请重新查看状态。'};
        }
      } finally {
        clearTimeout(request.timeout);
        if (summaryRequests.get(link) === request) {
          summaryRequests.delete(link);
          this.summarizing = {...this.summarizing, [link]: false};
        }
      }
    },
    checkSummary(link) { return this.requestSummary(link, '', false); },
    summarize(link, title) {
      if (!this.aiEnabled[link] || this.isSummarizing(link) || this.aiUncertain[link] || this.summaryState(link) === 'success') return;
      return this.requestSummary(link, title, true);
    },
    stopSummaryChecks() {
      summaryActive = false;
      summaryTimers.forEach(timer => clearTimeout(timer));
      summaryTimers.clear();
      summaryRequests.forEach((request, link) => {
        clearTimeout(request.timeout);
        this.aiUncertain = {...this.aiUncertain, [link]: '状态暂时无法确认，请重新查看状态。'};
        if (!request.submit) request.controller.abort();
      });
      summaryRequests.clear();
      this.summarizing = {};
    },
    destroy() {
      this.stopSummaryChecks();
      window.removeEventListener('pagehide', pauseSummaries);
      window.removeEventListener('pageshow', resumeSummaries);
    },
    buildToc() {
      const toc = document.getElementById('rd-toc');
      if (!toc) return;
      const label = document.createElement('div');
      label.className = 'rd-toc-label';
      label.textContent = '阅读来源';
      toc.append(label);
      let topic;
      document.querySelectorAll('.rd-section[id]').forEach(section => {
        const sectionTopic = section.dataset.tag || '未分类';
        if (sectionTopic !== topic) {
          topic = sectionTopic;
          const heading = document.createElement('div');
          heading.className = 'rd-toc-group';
          heading.textContent = topic;
          toc.append(heading);
        }
        const link = document.createElement('a');
        link.href = '#' + section.id;
        link.className = 'rd-toc-link';
        link.textContent = section.querySelector('.rd-section-name')?.textContent.trim() || '';
        link.addEventListener('click', event => {
          event.preventDefault();
          section.scrollIntoView({behavior: ui.motionBehavior(), block: 'start'});
          section.querySelector('.rd-section-name')?.focus({preventScroll: true});
        });
        toc.append(link);
      });
      let scheduled = false;
      const schedule = () => {
        if (scheduled) return;
        scheduled = true;
        requestAnimationFrame(() => {
          this.updateActiveToc();
          scheduled = false;
        });
      };
      window.addEventListener('scroll', schedule, {passive: true});
      window.addEventListener('resize', schedule);
      const content = document.getElementById('rd-main');
      if (content && 'ResizeObserver' in window) new ResizeObserver(schedule).observe(content);
      this.updateToc();
    },
    updateActiveToc() {
      const toc = document.getElementById('rd-toc');
      if (!toc) return;
      const sections = [...document.querySelectorAll('.rd-section[id]')]
        .filter(section => section.getClientRects().length > 0);
      let current = sections[0];
      for (const section of sections) {
        const offset = parseFloat(getComputedStyle(section).scrollMarginTop) || 0;
        if (section.getBoundingClientRect().top > offset + 1) break;
        current = section;
      }
      toc.querySelectorAll('.rd-toc-link').forEach(link => {
        const active = !!current && !link.hidden && link.hash === '#' + current.id;
        link.classList.toggle('active', active);
        active ? link.setAttribute('aria-current', 'location') : link.removeAttribute('aria-current');
      });
    },
    updateToc() {
      const toc = document.getElementById('rd-toc');
      if (!toc) return;
      let count = 0;
      toc.querySelectorAll('.rd-toc-link').forEach(link => {
        const section = document.getElementById(link.hash.slice(1));
        link.hidden = !section || !this.sectionVisible(section);
        if (!link.hidden) count++;
      });
      toc.querySelectorAll('.rd-toc-group').forEach(heading => {
        let visible = false;
        for (let el = heading.nextElementSibling; el?.classList.contains('rd-toc-link'); el = el.nextElementSibling) {
          if (!el.hidden) visible = true;
        }
        heading.hidden = !visible;
      });
      toc.hidden = count < 2;
      document.querySelector('.rd-layout')?.classList.toggle('rd-layout--single', count < 2);
      this.updateActiveToc();
    }
  };
}
