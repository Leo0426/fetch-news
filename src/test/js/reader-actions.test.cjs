const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const {test} = require('node:test');
const {runInNewContext} = require('node:vm');

const script = readFileSync(join(__dirname, '../../main/resources/static/reader.js'), 'utf8');
const articleLink = 'https://example.com/article?x=1&y=2';

function reader(reply, {queueMode = false, read = false, bookmarked = false} = {}) {
  const requests = [];
  const notifications = [];
  const timers = new Map();
  let timerId = 0;
  const card = {dataset: {link: articleLink, title: 'Saved article', routePath: '/source'},
    closest() { return null; }};
  const document = {
    body: {dataset: {readerMode: queueMode ? 'unread' : '', queueTotal: '1'}},
    activeElement: null,
    addEventListener() {},
    querySelector() { return null; },
    querySelectorAll() { return [card]; }
  };
  const state = runInNewContext(script + '\nreaderState();', {
    document,
    window: {FetchNewsUI: {storage: {get() { return null; }}, notify: (...args) => notifications.push(args)}},
    _fnReads: read ? [articleLink] : [],
    _fnBookmarks: bookmarked ? [articleLink] : [],
    URLSearchParams, AbortController,
    setTimeout(callback, delay) { const id = ++timerId; timers.set(id, {callback, delay}); return id; },
    clearTimeout(id) { timers.delete(id); },
    fetch: async (path, options) => { requests.push({path, options}); return reply(path, options); }
  });
  return {state, requests, notifications, timers};
}

function savedState({read = false, bookmarked = false, loseResponse = false} = {}) {
  const server = {read, bookmarked};
  server.reply = async (path, options) => {
    const values = new URLSearchParams(options.body);
    let result;
    if (path === '/reader/bookmark') {
      server.bookmarked = !server.bookmarked;
      result = server.bookmarked ? 'bookmarked' : 'removed';
    } else if (path === '/reader/article/bookmark' && options.method === 'PUT') {
      server.bookmarked = values.get('bookmarked') === 'true';
      result = server.bookmarked ? 'bookmarked' : 'removed';
    } else if (path === '/reader/read') {
      server.read = options.method === 'PUT' ? values.get('read') === 'true' : !server.read;
      result = server.read ? 'read' : 'unread';
    } else if (path === '/reader/read-all') {
      server.read = true;
      result = 'ok';
    } else throw new Error('Unexpected request: ' + path);
    if (loseResponse) {
      loseResponse = false;
      throw new Error('Connection lost after commit');
    }
    return {ok: true, text: async () => result};
  };
  return server;
}

test('retrying a bookmark after its saved response is lost keeps the intended state', async () => {
  for (const previous of [false, true]) {
    const server = savedState({bookmarked: previous, loseResponse: true});
    const view = reader(server.reply, {bookmarked: previous});
    await view.state.toggleBookmark(articleLink, 'Saved article', '/source');
    assert.equal(server.bookmarked, !previous);
    assert.equal(view.state.isBookmarked(articleLink), previous);
    await view.state.toggleBookmark(articleLink, 'Saved article', '/source');
    assert.equal(server.bookmarked, !previous);
    assert.equal(view.state.isBookmarked(articleLink), !previous);
  }
});

test('retrying either read selection after a lost response preserves the intended state', async () => {
  for (const previous of [false, true]) {
    const server = savedState({read: previous, loseResponse: true});
    const view = reader(server.reply, {read: previous});
    await view.state.toggleRead(articleLink);
    assert.equal(server.read, !previous);
    assert.equal(view.state.isRead(articleLink), previous);
    await view.state.toggleRead(articleLink);
    assert.equal(server.read, !previous);
    assert.equal(view.state.isRead(articleLink), !previous);
  }
});

test('hanging read, bookmark and bulk saves time out and release unread navigation', async () => {
  for (const action of ['read', 'bookmark', 'bulk']) {
    const view = reader((path, options) => new Promise((resolve, reject) => {
      options.signal?.addEventListener('abort', () => reject(new Error('Timed out')), {once: true});
    }), {queueMode: true});
    const completion = action === 'read' ? view.state.toggleRead(articleLink)
      : action === 'bookmark' ? view.state.toggleBookmark(articleLink, 'Saved article', '/source')
      : view.state.markAllRead();
    const navigation = () => ({
      target: {closest() { return {target: '', getAttribute() { return '/reader/bookmarks'; }}; }},
      defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; }
    });
    const blocked = navigation();
    view.state.guardQueueNavigation(blocked);
    assert.equal(blocked.defaultPrevented, true);
    assert.equal(view.state.queueBusy(), true);
    assert.equal(view.timers.size, 1);
    const timeout = [...view.timers.values()][0];
    assert.equal(timeout.delay, 15000);
    timeout.callback();
    await completion;
    assert.equal(view.state.queueBusy(), false);
    assert.equal(view.state.isRead(articleLink), false);
    assert.equal(view.state.isBookmarked(articleLink), false);
    assert.equal(view.timers.size, 0);
    assert.match(view.notifications.at(-1)[0], /无法确认/);
    const available = navigation();
    view.state.guardQueueNavigation(available);
    assert.equal(available.defaultPrevented, false);
  }
});

test('stale lists submit their visible intended read and bookmark selections', async () => {
  for (const previous of [false, true]) {
    const server = savedState({read: !previous, bookmarked: !previous});
    const view = reader(server.reply, {read: previous, bookmarked: previous});
    await view.state.toggleRead(articleLink);
    await view.state.toggleBookmark(articleLink, 'Stale title', '/stale-source');
    assert.equal(server.read, !previous);
    assert.equal(server.bookmarked, !previous);
    assert.equal(view.state.isRead(articleLink), !previous);
    assert.equal(view.state.isBookmarked(articleLink), !previous);
  }
  const alreadyRead = savedState({read: true});
  const inbox = reader(alreadyRead.reply, {queueMode: true});
  await inbox.state.toggleRead(articleLink);
  assert.equal(alreadyRead.read, true);
  assert.equal(inbox.state.isRead(articleLink), true);
});

test('a response whose body hangs is also bounded and pending clicks coalesce', async () => {
  const view = reader(async (path, options) => ({
    ok: true,
    text: () => new Promise((resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new Error('Body timed out')), {once: true});
    })
  }), {queueMode: true});
  const pending = view.state.toggleBookmark(articleLink);
  await Promise.resolve();
  await Promise.resolve();
  await view.state.toggleBookmark(articleLink);
  assert.equal(view.requests.length, 1);
  assert.equal(view.timers.size, 1);
  [...view.timers.values()][0].callback();
  await pending;
  assert.equal(view.state.queueBusy(), false);
  assert.equal(view.state.isBookmarked(articleLink), false);
  assert.equal(view.timers.size, 0);
});
