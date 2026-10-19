(() => {
  const feedback = document.getElementById('radar-feedback');
  const status = document.querySelector('[data-state][data-run-id]');
  const form = document.getElementById('radar-generate');
  let polling = false;
  const message = text => { feedback.textContent = text; };
  async function poll(id) {
    if (polling) return;
    polling = true;
    try {
      const response = await fetch(`/reader/radar/runs/${encodeURIComponent(id)}`, {signal: AbortSignal.timeout(15000)});
      if (!response.ok) throw new Error();
      const run = await response.json();
      if (['queued','running'].includes(run.state)) {
        message(`后台处理：${run.stage} · 模型调用 ${run.calls}`);
        polling = false;
        setTimeout(() => poll(id), 2000);
      } else { location.reload(); }
    } catch (_) { polling = false; message('暂时无法查询状态，请刷新页面查看。刷新不会重新生成。'); }
  }
  async function submit(url, body, button) {
    button.disabled = true;
    try {
      const response = await fetch(url, {method:'POST',body,signal:AbortSignal.timeout(15000)});
      const result = await response.json();
      if (!response.ok) { message(result.error || '提交失败'); button.disabled = false; return; }
      message('已提交，正在后台处理。');
      poll(result.runId);
    } catch (_) { message('无法确认提交结果，请刷新页面查询任务。不要重复生成。'); }
  }
  if (form) form.addEventListener('submit', event => {
    event.preventDefault();
    submit(form.action, new URLSearchParams(new FormData(form)), form.querySelector('button'));
  });
  const resume = document.getElementById('radar-resume');
  if (resume) resume.addEventListener('click', () => submit(`/reader/radar/runs/${encodeURIComponent(resume.dataset.runId)}/resume`, null, resume));
  if (status && ['queued','running'].includes(status.dataset.state)) poll(status.dataset.runId);
})();
