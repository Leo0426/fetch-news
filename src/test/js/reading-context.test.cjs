const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const {test} = require('node:test');
const {runInNewContext} = require('node:vm');

const script = readFileSync(join(__dirname, '../../main/resources/static/article.js'), 'utf8');

function page(storage, {path = '/reader', links = ['first', 'second', 'third'], reader = true,
  filter = 'unread', disclosures = {}, hidden = [], searchInput, briefing = []} = {}) {
  const listeners = new Map();
  const windowListeners = new Map();
  const url = new URL(path, 'http://localhost');
  let focused;
  let scroll = 500;
  const details = new Map(Object.entries(disclosures).map(([id, open]) => [id, {open}]));
  const panels = briefing.map(({ids, top = 100, height = 200, initialScroll = 0}) => {
    let innerScroll = initialScroll;
    return {
      ids, clientHeight: height,
      get scrollTop() { return innerScroll; },
      set scrollTop(value) { innerScroll = Math.max(0, Math.min(ids.length * 150 - height, value)); },
      getBoundingClientRect: () => ({top: top + 500 - scroll})
    };
  });
  const anchors = links.map((id, index) => {
    const panel = panels.find(group => group.ids.includes(id));
    return {
      dataset: {articleTitle: id + ' title'},
      href: 'http://localhost/reader/article?link=' + encodeURIComponent('https://example.test/' + id),
      getClientRects: () => !hidden.includes(id) && (details.get(id)?.open ?? true) ? [1] : [],
      getBoundingClientRect: () => ({
        top: panel ? panel.getBoundingClientRect().top + panel.ids.indexOf(id) * 150 - panel.scrollTop : 100 + index * 150,
        height: 20
      }),
      focus: () => { focused = id; },
      closest: selector => selector === '.bf-items' ? panel : selector === 'details:not([open])'
        && details.get(id)?.open === false ? details.get(id) : null
    };
  });
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
    panels,
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
  Object.assign(original.state, {filter: 'bookmarked', activeTag: '科学', pickFilter: 'quality', searchQuery: '卫星'});
  original.open('second');
  assert.equal(original.returnTo, '/reader?hours=72#source');

  const returned = page(storage, {path: '/reader?hours=72#source', links: ['first', 'third']});
  assert.equal(returned.state.filter, 'bookmarked');
  assert.equal(returned.state.activeTag, '科学');
  assert.equal(returned.state.pickFilter, 'quality');
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

test('briefing return aligns the same article inside its scrolled category before restoring the window', () => {
  const storage = memory();
  const list = {path: '/reader/briefing', reader: false, links: ['first', 'second', 'third', 'fourth']};
  page(storage, {...list, briefing: [{ids: list.links, initialScroll: 220}]}).open('third');
  const links = ['new', ...list.links];
  const returned = page(storage, {...list, links, briefing: [{ids: links, top: 125}]});
  assert.equal(returned.focused, 'third');
  assert.equal(returned.panels[0].scrollTop, 370);
  assert.equal(returned.scroll, 525);
});

test('briefing return follows the current article into a different, shorter category after reading ahead', () => {
  const storage = memory();
  const firstGroup = ['a', 'b', 'c'];
  const secondGroup = ['d', 'e', 'f'];
  const list = {path: '/reader/briefing', reader: false, links: [...firstGroup, ...secondGroup]};
  page(storage, {...list, briefing: [{ids: firstGroup, initialScroll: 220}, {ids: secondGroup}]}).open('c');
  const context = JSON.parse(storage.getItem('fn-reading-context'));
  context.current = 'https://example.test/f';
  storage.setItem('fn-reading-context', JSON.stringify(context));
  const returned = page(storage, {...list, briefing: [{ids: firstGroup}, {ids: secondGroup, top: 600, height: 60}]});
  assert.equal(returned.focused, 'f');
  assert.equal(returned.panels[0].scrollTop, 0);
  assert.equal(returned.panels[1].scrollTop, 260);
  assert.equal(returned.scroll, 960);
});

test('opening disclosures does not restore an article hidden by the current filters', () => {
  const storage = memory();
  page(storage, {disclosures: {second: true}}).open('second');
  const returned = page(storage, {disclosures: {second: false}, hidden: ['second']});
  assert.equal(returned.expanded('second'), false);
  assert.equal(returned.focused, 'third');
});

function articlePage(storage, id = 'first', save = async () => ({ok: true, text: async () => 'ok'})) {
  const elements = new Map();
  function element(selector) {
    if (!elements.has(selector)) {
      elements.set(selector, {
        dataset: {}, hidden: true, textContent: '', listeners: {},
        classList: {toggle() {}}, contains() { return false; },
        setAttribute(name, value) { this[name] = value; }, querySelector: element,
        addEventListener(name, handler) { this.listeners[name] = handler; }
      });
    }
    return elements.get(selector);
  }
  const sizeButton = element('large-font');
  sizeButton.dataset.articleSize = 'large';
  const article = element('[data-article]');
  article.dataset = {link: 'https://example.test/' + id, returnUrl: '/reader'};
  const summary = element('[data-article-summary]');
  summary.dataset = {summaryState: 'none', aiEnabled: 'false'};
  summary.querySelector = selector => selector === '[data-article-summary-settings]' ? null : element(selector);
  article.querySelector = element;
  article.querySelectorAll = selector => selector === '[data-article-size]' ? [sizeButton] : [];

  const document = {
    readyState: 'complete', body: {dataset: {}}, addEventListener() {},
    querySelector: selector => selector === '[data-article]' ? article : null,
    querySelectorAll: selector => ['[data-article-control]', 'button[data-article-size]'].includes(selector) ? [sizeButton] : []
  };
  const window = {addEventListener() {}, sessionStorage: storage, location: {assign(url) { this.destination = url; }},
    FetchNewsUI: {storage: {get() {}, set() {}}, notify() {}}};
  runInNewContext(script, {window, document, URLSearchParams, setTimeout, clearTimeout, AbortController, fetch: save});
  return {sizeButton, element, document, window};
}

test('members can use the article controls when the admin-only summary settings link is absent', () => {
  const {sizeButton, document, element} = articlePage(memory());
  assert.equal(sizeButton.hidden, false);
  sizeButton.listeners.click();
  assert.equal(document.body.dataset.articleSize, 'large');
  assert.equal(typeof element('[data-article-bookmark]').listeners.click, 'function');
  assert.equal(typeof element('[data-article-mark-read]').listeners.click, 'function');
});

test('continuous reading saves before advancing, skips filtered entries and stops at the last visible story', async () => {
  const storage = memory();
  const options = {links: ['first', 'hidden', 'second', 'last'], hidden: ['hidden']};
  page(storage, options).open('first');
  const writes = [];
  const current = articlePage(storage, 'first', async (path, options) => {
    writes.push([path, options.body]);
    return {ok: writes.length > 1, text: async () => 'ok'};
  });
  const next = current.element('[data-article-next]');
  assert.equal(next.hidden, false);
  assert.equal(current.element('[data-article-next-title]').textContent, 'second title');
  await next.listeners.click();
  assert.equal(current.window.location.destination, undefined);
  assert.equal(next.disabled, false);
  await next.listeners.click();
  assert.deepEqual(writes[1], ['/reader/read-all', 'links=https%3A%2F%2Fexample.test%2Ffirst']);
  const destination = new URL(current.window.location.destination, 'http://localhost');
  assert.equal(destination.pathname, '/reader/article');
  assert.equal(destination.searchParams.get('link'), 'https://example.test/second');
  assert.equal(destination.searchParams.get('returnTo'), '/reader');
  assert.equal(articlePage(storage, 'last').element('[data-article-next]').hidden, true);
  assert.equal(page(storage, options).focused, 'last');
  assert.equal(articlePage(memory()).element('[data-article-next]').hidden, true);
});
