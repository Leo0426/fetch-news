const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const {test} = require('node:test');
const {runInNewContext} = require('node:vm');

const script = readFileSync(join(__dirname, '../../main/resources/static/article.js'), 'utf8');

function page(storage, {path = '/reader', links = ['first', 'second', 'third'], reader = true,
  filter = 'unread', disclosures = {}, hidden = [], searchInput} = {}) {
  const listeners = new Map();
  const windowListeners = new Map();
  const url = new URL(path, 'http://localhost');
  let focused;
  let scroll = 500;
  const details = new Map(Object.entries(disclosures).map(([id, open]) => [id, {open}]));
  const anchors = links.map((id, index) => ({
    href: 'http://localhost/reader/article?link=' + encodeURIComponent('https://example.test/' + id),
    getClientRects: () => !hidden.includes(id) && (details.get(id)?.open ?? true) ? [1] : [],
    getBoundingClientRect: () => ({top: 100 + index * 150}),
    focus: () => { focused = id; },
    closest: selector => selector === 'details:not([open])' && details.get(id)?.open === false
      ? details.get(id) : null
  }));
  const document = {
    readyState: 'loading',
    body: {hasAttribute: name => reader && name === 'data-reader'},
    addEventListener(name, listener) { listeners.set(name, listener); },
    getElementById: id => id === 'reader-search' && searchInput !== undefined ? {value: searchInput} : null,
    querySelectorAll: selector => selector === '[data-article-link]' ? anchors : [],
    querySelector: () => null
  };
  const window = {
    location: url, sessionStorage: storage,
    addEventListener(name, listener) { windowListeners.set(name, listener); },
    get scrollY() { return scroll; },
    scrollTo: (x, y) => { scroll = y; }
  };
  runInNewContext(script, {window, document, URL, URLSearchParams, setTimeout, clearTimeout});
  listeners.get('DOMContentLoaded')();
  const state = {filter, activeTag: 'all', searchQuery: '',
    reads: {first: true}, bookmarks: {}, $nextTick: callback => callback()};
  window.FetchNewsReadingContext?.attach(state);
  return {
    state,
    expanded: id => details.get(id)?.open,
    open(id) {
      const anchor = anchors.find(a => new URL(a.href, url).searchParams.get('link').endsWith('/' + id));
      listeners.get('click')({target: {closest: () => anchor}, defaultPrevented: false});
    },
    get focused() { return focused; },
    get scroll() { return scroll; },
    get returnTo() { return new URL(anchors[0].href, url).searchParams.get('returnTo'); }
  };
}

function memory() {
  const values = new Map();
  return {getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key)};
}

test('return restores browsing filters and next visible article while retaining fresh server state', () => {
  const storage = memory();
  const original = page(storage, {path: '/reader?hours=72#source'});
  Object.assign(original.state, {filter: 'bookmarked', activeTag: '科学', searchQuery: '卫星'});
  original.open('second');
  assert.equal(original.returnTo, '/reader?hours=72#source');

  const returned = page(storage, {path: '/reader?hours=72#source', links: ['first', 'third']});
  assert.equal(returned.state.filter, 'bookmarked');
  assert.equal(returned.state.activeTag, '科学');
  assert.equal(returned.state.searchQuery, '卫星');
  assert.equal(returned.state.reads.first, true);
  assert.deepEqual(returned.state.bookmarks, {});
  assert.equal(returned.focused, 'third');
  assert.equal(returned.scroll, 500);
});

test('ordinary return focuses the same article and keeps its previous viewport position', () => {
  const storage = memory();
  page(storage).open('second');
  const returned = page(storage, {links: ['new', 'first', 'second', 'third']});
  assert.equal(returned.focused, 'second');
  assert.equal(returned.scroll, 650);
});

test('clicking before search debounce completes preserves the text currently in the search field', () => {
  const storage = memory();
  page(storage, {searchInput: '卫星'}).open('second');
  assert.equal(page(storage).state.searchQuery, '卫星');
});

test('a consumed return snapshot does not override later filter choices on reload', () => {
  const storage = memory();
  const original = page(storage);
  original.state.searchQuery = '卫星';
  original.open('second');
  assert.equal(page(storage).state.searchQuery, '卫星');
  const reloaded = page(storage, {filter: 'all'});
  assert.equal(reloaded.state.filter, 'all');
  assert.equal(reloaded.state.searchQuery, '');
  assert.equal(reloaded.focused, undefined);
});

test('saved view is isolated from different filters, lists and browser tabs', () => {
  const storage = memory();
  const original = page(storage, {path: '/reader?hours=72'});
  original.state.searchQuery = '卫星';
  original.open('first');
  assert.equal(page(storage, {path: '/reader?hours=24'}).state.searchQuery, '');
  assert.equal(page(storage, {path: '/reader/bookmarks', reader: false}).focused, undefined);
  assert.equal(page(memory(), {path: '/reader?hours=72'}).state.searchQuery, '');
});

test('blocked storage and malformed saved state leave navigation usable', () => {
  const denied = {getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); }};
  assert.doesNotThrow(() => page(denied).open('first'));
  const broken = {getItem: () => '{broken', setItem() {}};
  const view = page(broken);
  assert.equal(view.state.searchQuery, '');
  assert.equal(view.returnTo, '/reader');
});

test('bookmarks and search also restore the selected article without Alpine', () => {
  const storage = memory();
  page(storage, {path: '/reader/search?q=卫星', reader: false}).open('second');
  const returned = page(storage, {path: '/reader/search?q=卫星', reader: false});
  assert.equal(returned.focused, 'second');
});

test('returning to a related briefing article reopens its disclosure before restoring focus', () => {
  const storage = memory();
  page(storage, {path: '/reader/briefing', reader: false, disclosures: {second: true}}).open('second');
  const returned = page(storage, {path: '/reader/briefing', reader: false, disclosures: {second: false}});
  assert.equal(returned.expanded('second'), true);
  assert.equal(returned.focused, 'second');
  assert.equal(returned.scroll, 500);
});

test('opening disclosures does not restore an article hidden by the current filters', () => {
  const storage = memory();
  page(storage, {disclosures: {second: true}}).open('second');
  const returned = page(storage, {disclosures: {second: false}, hidden: ['second']});
  assert.equal(returned.expanded('second'), false);
  assert.equal(returned.focused, 'third');
});
