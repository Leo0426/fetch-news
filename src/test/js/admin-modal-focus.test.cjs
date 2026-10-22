const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const {test} = require('node:test');
const {runInNewContext} = require('node:vm');

const script = readFileSync(join(__dirname, '../../main/resources/static/admin.js'), 'utf8');

function page() {
  const events = new Map();
  const windowEvents = new Map();
  const factories = new Map();
  const ticks = [];
  const document = {
    body: {style: {overflow: ''}},
    addEventListener: (name, handler) => events.set(name, handler),
    activeElement: null
  };
  function element() {
    return {isConnected: true, inert: false,
      focus() { document.activeElement = this; },
      getClientRects: () => [1], closest: () => null, matches: () => true};
  }
  const heading = element();
  const card = {...element(), querySelector: () => heading,
    scrollIntoView() { this.scrolled = true; }};
  const input = element();
  const main = element();
  const elements = {'app-shell': element(), 'console-main': element(), 'main-content': main,
    'new-source': card};
  const modal = {...element(), querySelector: () => null, querySelectorAll: () => [input]};
  document.getElementById = id => elements[id] || null;
  document.querySelector = selector => selector === '.modal' ? modal : null;
  const window = {
    location: new URL('http://localhost/index'),
    matchMedia: () => ({matches: false, addEventListener() {}}),
    addEventListener: (name, handler) => windowEvents.set(name, handler),
    FetchNewsUI: {motionBehavior: () => 'auto'}
  };
  runInNewContext(script, {document, window,
    Alpine: {data: (name, factory) => factories.set(name, factory)},
    URL, URLSearchParams, setTimeout, clearTimeout});
  events.get('alpine:init')();
  const shell = factories.get('appShell')();
  const watchers = new Map();
  shell.$watch = (name, handler) => watchers.set(name, handler);
  shell.$nextTick = callback => ticks.push(callback);
  for (const property of ['modal', 'sbOpen']) {
    let value = shell[property];
    Object.defineProperty(shell, property, {
      get: () => value,
      set(next) {
        if (next === value) return;
        value = next;
        queueMicrotask(() => watchers.get(property)?.(next));
      }
    });
  }
  shell.init();
  return {
    shell, document, heading, card, input, main,
    created() { events.get('subscriptionCreated')({detail: {cardId: 'new-source'}}); },
    close() { windowEvents.get('closemodal')(); },
    async flush() {
      await Promise.resolve();
      while (ticks.length) ticks.shift()();
      await Promise.resolve();
    }
  };
}

test('created source keeps focus after the modal close watcher completes', async () => {
  const view = page();
  await view.flush();
  view.shell.modal = true;
  await view.flush();
  assert.equal(view.document.activeElement, view.input);
  view.created();
  await view.flush();
  assert.equal(view.shell.modal, false);
  assert.equal(view.document.activeElement, view.heading);
  assert.equal(view.card.scrolled, true);
});

test('created-source return focus is consumed before another modal is closed', async () => {
  const view = page();
  await view.flush();
  view.shell.modal = true;
  await view.flush();
  view.created();
  await view.flush();
  assert.equal(view.document.activeElement, view.heading);
  view.shell.modal = true;
  await view.flush();
  view.close();
  await view.flush();
  assert.equal(view.document.activeElement, view.main);
});

test('a removed created card cannot take focus from the current page', async () => {
  const view = page();
  await view.flush();
  view.shell.modal = true;
  await view.flush();
  view.created();
  view.card.isConnected = false;
  await view.flush();
  assert.equal(view.document.activeElement, view.main);
  assert.equal(view.card.scrolled, undefined);
});
