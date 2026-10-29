const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const {test} = require('node:test');
const {runInNewContext} = require('node:vm');

const script = readFileSync(join(__dirname, '../../main/resources/static/reader-page.js'), 'utf8');
const settle = () => new Promise(resolve => setImmediate(resolve));

function page(reply, read = 'false') {
  const requests = [];
  const notifications = [];
  const listeners = new Map();
  const label = {textContent: read === 'true' ? '已读' : read === 'false' ? '未读' : '状态待确认',
    setAttribute() {}, removeAttribute() {}};
  const error = {hidden: true};
  const retry = element('retry');
  retry.disabled = false;
  const card = element('card');
  card.dataset = {link: 'https://example.com/article?x=1&y=2', read};
  card.querySelector = selector => ({
    '[data-bookmark-read-state]': label,
    '[data-bookmark-read-error]': error,
    '[data-bookmark-read-retry]': retry
  })[selector];
  const title = element('title');
  const internal = element('internal');
  for (const child of [title, internal, retry]) child.parent = card;
  const grid = {
    addEventListener(name, listener) { listeners.set(name, listener); },
    contains(node) { return [card, title, internal, retry].includes(node); },
    querySelectorAll() { return [card]; }
  };
  function element(kind) {
    return {
      kind,
      isConnected: true,
      matches(selector) {
        return selector.split(',').some(part => {
          part = part.trim();
          return (kind === 'card' && part.startsWith('.bm-card') && !part.startsWith('.bm-card-title'))
            || (kind === 'title' && part === '.bm-card-title')
            || (kind === 'retry' && part === '[data-bookmark-read-retry]');
        });
      },
      closest(selector) { return this.matches(selector) ? this : this.parent?.closest(selector); }
    };
  }
  runInNewContext(script, {
    document: {addEventListener() {}, querySelector: selector => selector === '[data-bookmark-grid]' ? grid : null},
    window: {FetchNewsUI: {notify: (...args) => notifications.push(args)}},
    MutationObserver: class {observe() {}},
    URLSearchParams, AbortController, setTimeout, clearTimeout,
    fetch: async (url, options) => { requests.push({url, options}); return reply(); }
  });
  function activate(type = 'click', target = title, options = {}) {
    const event = {target, button: type === 'auxclick' ? 1 : 0, defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; }, ...options};
    listeners.get(type)?.(event);
    return event;
  }
  return {activate, card, title, internal, retry, label, error, requests, notifications};
}

test('ordinary, modified and middle clicks keep opening the original while marking read idempotently', async () => {
  for (const [type, options] of [['click', {}], ['click', {ctrlKey: true}],
      ['click', {metaKey: true}], ['auxclick', {button: 1}]]) {
    const view = page(async () => ({ok: true, text: async () => 'ok'}));
    const event = view.activate(type, view.title, options);
    assert.equal(event.defaultPrevented, false);
    await settle();
    assert.equal(view.requests.length, 1);
    assert.equal(view.requests[0].url, '/reader/read-all');
    assert.equal(view.requests[0].options.method, 'POST');
    assert.equal(view.requests[0].options.keepalive, true);
    assert.equal(new URLSearchParams(view.requests[0].options.body).get('links'), view.card.dataset.link);
    assert.equal(view.card.dataset.read, 'true');
    assert.equal(view.label.textContent, '已读');
  }
});

test('network and unexpected-response failures preserve confirmed or unknown read state', async () => {
  for (const [read, reply] of [
    ['true', async () => { throw new Error('Network unavailable'); }],
    ['unknown', async () => ({ok: true, text: async () => 'unexpected'})]
  ]) {
    const view = page(reply, read);
    const label = view.label.textContent;
    view.activate();
    await settle();
    assert.equal(view.card.dataset.read, read);
    assert.equal(view.label.textContent, label);
    assert.equal(view.error.hidden, false);
    assert.equal(view.retry.disabled, false);
  }
});

test('failed saves keep the last confirmed state and provide a successful explicit retry', async () => {
  let available = false;
  const view = page(async () => ({ok: available, text: async () => available ? 'ok' : 'error'}));
  assert.equal(view.activate().defaultPrevented, false);
  await settle();
  assert.equal(view.card.dataset.read, 'false');
  assert.equal(view.label.textContent, '未读');
  assert.equal(view.error.hidden, false);
  assert.equal(view.retry.disabled, false);
  assert.equal(view.notifications.length, 1);
  available = true;
  view.activate('click', view.retry);
  await settle();
  assert.equal(view.requests.length, 2);
  assert.equal(view.card.dataset.read, 'true');
  assert.equal(view.error.hidden, true);
});

test('pending clicks coalesce without optimistic read state and internal reading stays read-only', async () => {
  let release;
  const view = page(() => new Promise(resolve => { release = resolve; }));
  view.activate('click', view.internal);
  view.activate('auxclick', view.title, {button: 2});
  assert.equal(view.requests.length, 0);
  assert.equal(view.activate().defaultPrevented, false);
  assert.equal(view.activate('auxclick').defaultPrevented, false);
  assert.equal(view.requests.length, 1);
  assert.equal(view.card.dataset.read, 'false');
  release({ok: true, text: async () => 'ok'});
  await settle();
  assert.equal(view.card.dataset.read, 'true');
});
