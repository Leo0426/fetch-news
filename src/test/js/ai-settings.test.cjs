const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const {test} = require('node:test');
const {runInNewContext} = require('node:vm');

const script = readFileSync(join(__dirname, '../../main/resources/static/ai-settings.js'), 'utf8');

function editor(events = new Map()) {
  let factory;
  runInNewContext(script, {document: {addEventListener: (name, fn) => {
    if (name === 'alpine:init') fn();
    else events.set(name, fn);
  }},
    Alpine: {data: (_, fn) => { factory = fn; }}});
  const view = factory();
  view.$root = {
    dataset: {protocol: 'openai'},
    elements: {model: {value: 'primary'}, host: {value: 'https://api.openai.com/v1'}},
    querySelectorAll: selector => selector === '[data-saved-model]' ? [{value: 'primary'}, {value: 'secondary'}] : [],
    querySelector: () => ({textContent: '{"secondary":{"maxTokens":2048,"parameters":{"stream":true}}}'})
  };
  view.$refs = {modelDialog: {close() {}}};
  view.init();
  return view;
}

test('editing another model preserves draft settings and leaves the default unchanged', () => {
  const view = editor();
  view.selectedDraft.parameters = '{"temperature":0.7}';
  view.editModel = 'secondary';
  assert.equal(view.selectedDraft.maxTokens, 2048);
  view.selectedDraft.apiStyle = 'responses';
  view.selectedDraft.parameters = '[]';
  assert.match(view.invalidDraft, /secondary/);
  view.editModel = 'primary';
  assert.equal(view.capabilitiesJson, '');
  assert.equal(view.selectedDraft.parameters, '{"temperature":0.7}');
  view.editModel = 'secondary';
  view.selectedDraft.parameters = '{"reasoning":{"effort":"high"},"stream":true}';
  const drafts = JSON.parse(view.capabilitiesJson);
  assert.deepEqual(drafts.secondary.parameters, {reasoning: {effort: 'high'}, stream: true});
  assert.equal(drafts.secondary.apiStyle, 'responses');
  assert.equal(drafts.primary.parameters.temperature, 0.7);
  assert.equal(view.model, 'primary');
});

test('malformed JSON and a valid non-object produce distinct feedback without exposing typed values', () => {
  const view = editor();
  view.selectedDraft.parameters = '{\n  "private-value": 1,\n}';
  assert.match(view.invalidDraft, /primary.*JSON 语法错误/);
  assert.doesNotMatch(view.invalidDraft, /private-value/);
  view.selectedDraft.parameters = '["private-value"]';
  assert.match(view.invalidDraft, /必须是 JSON 对象/);
  assert.doesNotMatch(view.invalidDraft, /private-value/);
});

test('protocol changes select a suitable endpoint and restore each protocol’s model options', () => {
  const view = editor();
  view.chooseProtocol('gemini');
  assert.equal(view.$root.elements.host.value, 'https://generativelanguage.googleapis.com/v1beta');
  view.editModel = 'secondary';
  assert.equal(view.selectedDraft.maxTokens, 4096);
  view.selectedDraft.parameters = '{"topK":10}';
  view.$root.elements.host.value = 'https://relay.example/v1';
  view.chooseProtocol('openai');
  assert.equal(view.$root.elements.host.value, 'https://relay.example/v1');
  assert.equal(view.selectedDraft.maxTokens, 2048);
  assert.equal(JSON.parse(view.capabilitiesJson).secondary.parameters.stream, true);
  view.chooseProtocol('gemini');
  assert.equal(JSON.parse(view.capabilitiesJson).secondary.parameters.topK, 10);
});

test('adding and removing a non-default model updates the editor and serialized form', () => {
  const view = editor();
  view.draftModel = '__proto__';
  view.addModel();
  assert.equal(view.editModel, '__proto__');
  assert.equal(view.selectedDraft.maxTokens, 4096);
  assert.equal(Object.hasOwn(JSON.parse(view.capabilitiesJson), '__proto__'), true);
  view.removeModel('__proto__');
  assert.equal(view.editModel, 'primary');
  assert.equal(Object.hasOwn(JSON.parse(view.capabilitiesJson), '__proto__'), false);
  view.removeModel(view.model);
  assert.equal(view.models.length, 2);
});

test('settings edited during a probe discard the old result and clear the busy state', () => {
  const events = new Map();
  const view = editor(events);
  const target = {id: 'ai-test-result', textContent: '',
    hasAttribute: name => name === 'data-ai-probe-result',
    setAttribute(name, value) { this[name] = value; },
    removeAttribute(name) { delete this[name]; },
    replaceChildren() { this.textContent = ''; }};
  const elt = {dataset: {aiProbe: '正在测试'}, hasAttribute: () => false};
  const detail = {target, elt, xhr: {}, shouldSwap: true};
  events.get('htmx:beforeRequest')({detail});
  assert.equal(target['aria-busy'], 'true');
  assert.equal(target.textContent, '正在测试');
  view.$root.querySelectorAll = () => [target];
  view.clearProbeResults();
  events.get('htmx:beforeSwap')({detail});
  assert.equal(detail.shouldSwap, false);
  assert.equal(target['aria-busy'], undefined);
  assert.equal(target.textContent, '');
});
