const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const {test} = require('node:test');
const {runInNewContext} = require('node:vm');

const script = readFileSync(join(__dirname, '../../main/resources/static/compact-summary.js'), 'utf8');
const articleLink = 'https://example.test/article?x=1&y=2';

function element(text = '', hidden = false) {
  const listeners = new Map();
  const classes = new Set();
  return {
    textContent: text, hidden, disabled: false, attributes: {},
    set innerHTML(_) { throw new Error('Summary content must remain literal text'); },
    classList: {toggle(name, enabled) { enabled ? classes.add(name) : classes.delete(name); }},
    setAttribute(name, value) { this.attributes[name] = value; },
    addEventListener(name, listener) { listeners.set(name, listener); },
    click() { return listeners.get('click')?.(); },
    focus() { this.focused = true; }
  };
}

function summaryRegion(state, link = articleLink, text = '') {
  const label = element();
  const status = element();
  status.querySelector = () => label;
  const fields = {
    '.reader-summary-status': status,
    '[data-compact-summary-text]': element(text, state !== 'success'),
    '[data-compact-summary-error]': element(state === 'error' ? 'Saved generation failure' : '', state !== 'error'),
    '[data-compact-summary-check]': element('重新查看状态', true),
    '[data-compact-summary-recovery]': element('', state !== 'error')
  };
  return {
    dataset: {summaryState: state, link}, label, ...fields,
    querySelector(selector) { return fields[selector]; }
  };
}

function page(regions, reply) {
  const requests = [], timers = new Map(), events = new Map();
  let nextTimer = 0;
  const document = {readyState: 'complete', activeElement: null, querySelectorAll() { return regions; }};
  runInNewContext(script, {
    document, window: {addEventListener(name, listener) { events.set(name, listener); }},
    URLSearchParams, AbortController,
    setTimeout(callback, delay) { const id = ++nextTimer; timers.set(id, {callback, delay}); return id; },
    clearTimeout(id) { timers.delete(id); },
    fetch(path, options) { requests.push({path, options}); return reply(path, options); }
  });
  return {
    requests, timers, document,
    hide() { events.get('pagehide')?.(); },
    tick(delay) {
      const found = [...timers].find(([, timer]) => timer.delay === delay);
      assert.ok(found, 'Expected a scheduled timer');
      timers.delete(found[0]);
      return found[1].callback();
    }
  };
}

const response = result => ({ok: true, json: async () => result});
const settled = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

test('pending summaries display running progress and literal saved text, then stop polling', async () => {
  const region = summaryRegion('queued');
  const results = [{state: 'running', summary: null, error: null},
    {state: 'success', summary: '<img src=x onerror=bad()> Saved summary', error: null}];
  const view = page([region], async () => response(results.shift()));
  await settled();
  assert.equal(view.requests.length, 1);
  assert.equal(region.dataset.summaryState, 'running');
  assert.match(region.label.textContent, /正在生成/);
  await view.tick(2000);
  assert.equal(region.dataset.summaryState, 'success');
  assert.equal(region['[data-compact-summary-text]'].textContent, '<img src=x onerror=bad()> Saved summary');
  assert.equal(region['[data-compact-summary-text]'].hidden, false);
  assert.equal(view.timers.size, 0);
  assert.equal(view.requests.length, 2);
  for (const {path, options} of view.requests) {
    assert.equal(path, '/reader/summary-status?link=https%3A%2F%2Fexample.test%2Farticle%3Fx%3D1%26y%3D2');
    assert.equal(options.method, 'GET');
    assert.equal(options.body, undefined);
  }
});

test('generation errors stop polling and expose the article recovery route', async () => {
  const region = summaryRegion('running');
  const view = page([region], async () => response({state: 'error', summary: null, error: 'Provider unavailable'}));
  await settled();
  assert.equal(region.dataset.summaryState, 'error');
  assert.equal(region['[data-compact-summary-error]'].textContent, 'Provider unavailable');
  assert.equal(region['[data-compact-summary-recovery]'].hidden, false);
  assert.equal(view.timers.size, 0);
});

test('unconfirmed polling stops and explicit status checking resumes without submitting work', async () => {
  for (const failure of [
    async () => { throw new Error('Offline'); },
    async () => ({ok: false, json: async () => ({state: 'error', error: 'Service unavailable'})}),
    async () => response({state: 'success', summary: ' ', error: null}),
    async () => response({state: 'mystery', summary: null, error: null})
  ]) {
    const region = summaryRegion('queued');
    let fail = true;
    const view = page([region], (...args) => fail ? failure(...args) : Promise.resolve(response({state: 'success', summary: 'Recovered summary', error: null})));
    await settled();
    assert.equal(region['[data-compact-summary-check]'].hidden, false);
    assert.equal(region['[data-compact-summary-recovery]'].hidden, false);
    assert.equal(region['[data-compact-summary-text]'].hidden, true);
    assert.equal(view.timers.size, 0);
    fail = false;
    await region['[data-compact-summary-check]'].click();
    assert.equal(region['[data-compact-summary-text]'].textContent, 'Recovered summary');
    assert.equal(region['[data-compact-summary-check]'].hidden, true);
    assert.equal(view.requests.length, 2);
    assert.ok(view.requests.every(request => request.options.method === 'GET'));
    assert.equal(view.timers.size, 0);
  }
});

test('cached success stays visible and initial errors do not start background work', async () => {
  const cached = summaryRegion('success', articleLink, 'Saved while AI was enabled');
  const failed = summaryRegion('error', 'https://example.test/failed');
  const view = page([cached, failed], async () => { throw new Error('No request expected'); });
  await settled();
  assert.equal(cached['[data-compact-summary-text]'].textContent, 'Saved while AI was enabled');
  assert.equal(cached['[data-compact-summary-text]'].hidden, false);
  assert.equal(failed['[data-compact-summary-recovery]'].hidden, false);
  assert.equal(view.requests.length, 0);
  assert.equal(view.timers.size, 0);
});

test('hanging status requests time out, coalesce repeated checks, and release recovery controls', async () => {
  const region = summaryRegion('queued');
  const view = page([region], (path, options) => new Promise((resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new Error('Timed out')), {once: true});
  }));
  await region['[data-compact-summary-check]'].click();
  assert.equal(view.requests.length, 1);
  view.tick(15000);
  await settled();
  assert.equal(region['[data-compact-summary-check]'].hidden, false);
  assert.equal(region['[data-compact-summary-check]'].disabled, false);
  assert.equal(view.timers.size, 0);
});

test('leaving the page aborts all primary and related polls and ignores their late results', async () => {
  const primary = summaryRegion('queued');
  const related = summaryRegion('running', 'https://example.test/related');
  const completions = [];
  const view = page([primary, related], () => new Promise(resolve => completions.push(resolve)));
  assert.equal(view.requests.length, 2);
  view.hide();
  assert.ok(view.requests.every(request => request.options.signal.aborted));
  completions.forEach(resolve => resolve(response({state: 'success', summary: 'Late result', error: null})));
  await settled();
  assert.equal(primary.dataset.summaryState, 'queued');
  assert.equal(related.dataset.summaryState, 'running');
  assert.equal(view.timers.size, 0);
});
