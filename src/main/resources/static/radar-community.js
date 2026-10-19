(() => {
  'use strict';
  const text = (tag, value, parent) => {
    const node = document.createElement(tag);
    node.textContent = value;
    parent.append(node);
    return node;
  };
  function link(label, value, parent) {
    try {
      const url = new URL(value);
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return;
      const anchor = text('a', label, parent);
      anchor.href = url.href; anchor.target = '_blank'; anchor.rel = 'noopener noreferrer';
    } catch (_) { /* Invalid cached links are omitted. */ }
  }
  for (const details of document.querySelectorAll('[data-community]')) {
    const form = details.querySelector('form');
    const output = details.querySelector('[data-context-output]');
    const feedback = details.querySelector('[role=status]');
    const button = form.querySelector('button');
    let loaded = false;
    let timer;
    async function cached() {
      const response = await fetch('/reader/radar/context?' + new URLSearchParams({link: form.elements.link.value}), {signal: AbortSignal.timeout(15000)});
      if (response.status === 204) { feedback.textContent = '尚无缓存，点击获取后才会访问 Hacker News。'; return; }
      if (!response.ok) throw new Error();
      const context = await response.json();
      output.replaceChildren();
      link(context.title || 'Hacker News 讨论', context.discussionUrl, output);
      text('p', '抓取时间：' + new Date(context.fetchedAt).toLocaleString() + ' · 前 10 条顶层评论样本 · 失败 ' + context.failedComments, output);
      if (context.background) text('p', context.background, output);
      for (const comment of context.comments) {
        const article = document.createElement('article'); output.append(article);
        link(comment.author + ' · 评论原文', comment.url, article);
        text('p', comment.text, article);
      }
      if (context.references.length) {
        text('h4', '讨论中的背景链接（未经核实）', output);
        for (const reference of context.references) { const p = document.createElement('p'); output.append(p); link(reference, reference, p); }
      }
      feedback.textContent = '社区观点可能有误，请结合原文核对。';
    }
    async function poll(id) {
      try {
        const response = await fetch('/reader/radar/context/jobs/' + encodeURIComponent(id), {signal: AbortSignal.timeout(15000)});
        if (!response.ok) throw new Error();
        const job = await response.json();
        if (['queued', 'running'].includes(job.state)) {
          feedback.textContent = '正在获取讨论…';
          timer = setTimeout(() => poll(id), 1500);
        } else {
          button.disabled = false;
          if (job.state === 'success') await cached();
          else feedback.textContent = '讨论获取失败或被中断。请核对讨论是否引用此原文，稍后重试；30 分钟内重复提交会保留原任务。';
        }
      } catch (_) { button.disabled = false; feedback.textContent = '暂时无法查询，请关闭后重新展开查看缓存。'; loaded = false; }
    }
    details.addEventListener('toggle', () => {
      if (details.open && !loaded) {
        loaded = true;
        cached().catch(() => { loaded = false; feedback.textContent = '缓存读取失败，请稍后重试。'; });
      }
    });
    form.addEventListener('submit', async event => {
      event.preventDefault(); clearTimeout(timer); button.disabled = true;
      try {
        const response = await fetch('/reader/radar/context', {method: 'POST', body: new URLSearchParams(new FormData(form)), signal: AbortSignal.timeout(15000)});
        const job = await response.json();
        if (!response.ok) { button.disabled = false; feedback.textContent = job.error || '提交失败'; return; }
        poll(job.id);
      } catch (_) { button.disabled = false; feedback.textContent = '提交结果未知，请稍后展开查看缓存。'; }
    });
  }
})();
