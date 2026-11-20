// AI platform form in /index: protocol presets, model discovery and the model list.
const aiFocusAfterSwap = new WeakMap();
const aiPendingProbe = new WeakMap();

document.addEventListener('htmx:beforeRequest', event => {
  const {elt, target, xhr} = event.detail;
  if (elt.hasAttribute('data-ai-open-editor')) aiFocusAfterSwap.set(xhr, '#ai-editor-heading');
  if (target.id === 'main-content' && elt.closest('[data-ai-settings]'))
    aiFocusAfterSwap.set(xhr, '.ai-notice');
  if (!elt.dataset.aiProbe) return;
  aiPendingProbe.set(target, xhr);
  target.textContent = elt.dataset.aiProbe;
  target.setAttribute('aria-busy', 'true');
});

document.addEventListener('htmx:beforeSwap', event => {
  const {target, xhr} = event.detail;
  if (target.hasAttribute('data-ai-probe-result') && aiPendingProbe.get(target) !== xhr)
    event.detail.shouldSwap = false;
});

document.addEventListener('htmx:afterSettle', event => {
  const selector = aiFocusAfterSwap.get(event.detail.xhr);
  if (selector) {
    aiFocusAfterSwap.delete(event.detail.xhr);
    const heading = event.detail.target.querySelector(selector);
    if (heading) {
      heading.focus({preventScroll: true});
      heading.scrollIntoView({block: window.matchMedia('(max-width: 590px)').matches ? 'start' : 'nearest'});
    }
  }
});

document.addEventListener('htmx:afterRequest', event => {
  const {target, xhr, failed} = event.detail;
  if (aiPendingProbe.get(target) !== xhr || !target.hasAttribute('data-ai-probe-result')) return;
  target.removeAttribute('aria-busy');
  if (failed) {
    const error = new DOMParser().parseFromString(xhr.responseText || '', 'text/html').querySelector('.form-msg.err');
    target.textContent = error?.textContent || '测试中断，请检查连接后重试。';
  }
});

document.addEventListener('alpine:init', () => {
  Alpine.data('aiProviderForm', () => ({
    protocol: '',
    model: '',
    editModel: '',
    models: [],
    drafts: [],
    availableModels: [],
    draftModel: '',
    modelError: '',
    modelsLoading: false,
    modelMessage: '',
    modelRequest: 0,
    modelController: null,
    init() {
      this.protocol = this.$root.dataset.protocol;
      this.model = this.$root.elements.model.value;
      this.editModel = this.model;
      this.models = Array.from(this.$root.querySelectorAll('[data-saved-model]'), field => field.value);
      const saved = JSON.parse(this.$root.querySelector('[data-saved-capabilities]').textContent);
      for (const id of this.models) this.createDraft(id, Object.hasOwn(saved, id) ? saved[id] : undefined);
    },
    createDraft(id, options = {}) {
      this.drafts.push({
        id, protocol: this.protocol, maxTokens: options.maxTokens ?? 4096,
        jsonMode: options.jsonMode ?? (this.protocol === 'ollama'),
        tokenLimitParameter: options.tokenLimitParameter || 'auto', apiStyle: options.apiStyle || 'auto',
        parameters: Object.keys(options.parameters || {}).length ? JSON.stringify(options.parameters, null, 2) : ''
      });
    },
    get currentDrafts() { return this.drafts.filter(draft => draft.protocol === this.protocol); },
    get selectedDraft() { return this.currentDrafts.find(draft => draft.id === this.editModel); },
    parsedParameters(draft) {
      const value = JSON.parse(draft.parameters.trim() ? draft.parameters : '{}');
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('Object required');
      return value;
    },
    get invalidDraft() {
      for (const draft of this.currentDrafts) {
        if (!Number.isInteger(draft.maxTokens) || draft.maxTokens < 1 || draft.maxTokens > 1000000)
          return '请为 ' + draft.id + ' 设置 1 到 1000000 之间的最大输出 Token。';
        try { this.parsedParameters(draft); }
        catch (error) {
          if (!(error instanceof SyntaxError)) return draft.id + ' 的附加参数必须是 JSON 对象。';
          // Parser messages may quote the input. Only display a location when the engine provides one.
          const at = /\(line (\d+) column (\d+)\)$/.exec(error.message);
          const location = at ? '（第 ' + at[1] + ' 行，第 ' + at[2] + ' 列）' : '';
          return draft.id + ' 的附加参数存在 JSON 语法错误' + location + '，请检查引号、逗号和括号。';
        }
      }
      return '';
    },
    get capabilitiesJson() {
      if (this.invalidDraft) return '';
      const settings = this.currentDrafts.map(draft => [draft.id, {
        maxTokens: draft.maxTokens, jsonMode: draft.jsonMode, tokenLimitParameter: draft.tokenLimitParameter,
        apiStyle: draft.apiStyle, parameters: this.parsedParameters(draft)
      }]);
      return JSON.stringify(Object.fromEntries(settings));
    },
    chooseProtocol(next) {
      const hosts = {ollama: 'http://localhost:11434', openai: 'https://api.openai.com/v1',
        anthropic: 'https://api.anthropic.com/v1', gemini: 'https://generativelanguage.googleapis.com/v1beta'};
      const endpoint = this.$root.elements.host;
      if (!endpoint.value || endpoint.value === hosts[this.protocol]) endpoint.value = hosts[next];
      this.protocol = next;
      for (const id of this.models) {
        if (!this.currentDrafts.some(draft => draft.id === id)) this.createDraft(id);
      }
      this.invalidateModels();
    },
    get parameterExample() {
      switch (this.protocol) {
        case 'ollama': return '{"temperature": 0.3}';
        case 'anthropic': return '{"thinking": {"type": "enabled", "budget_tokens": 1024}}';
        case 'gemini': return '{"thinkingConfig": {"thinkingBudget": 1024}}';
        default: return this.selectedDraft?.apiStyle === 'responses'
          ? '{"reasoning": {"effort": "low"}, "stream": true}' : '{"reasoning_effort": "low", "stream": true}';
      }
    },
    openModelDialog() {
      this.draftModel = '';
      this.modelError = '';
      this.$refs.modelDialog.showModal();
      this.$refs.modelId.focus();
    },
    addModel() {
      const id = this.draftModel.trim();
      if (!id) { this.modelError = '请填写模型 ID，或从接口列表中选择。'; return; }
      if (this.models.includes(id)) { this.modelError = '此模型已在列表中。'; return; }
      this.models.push(id);
      this.createDraft(id);
      if (!this.model) this.model = id;
      this.editModel = id;
      this.clearProbeResults();
      this.$refs.modelDialog.close();
    },
    removeModel(id) {
      if (id === this.model) return;
      this.models = this.models.filter(value => value !== id);
      this.drafts = this.drafts.filter(draft => draft.id !== id);
      if (this.editModel === id) this.editModel = this.model;
      this.clearProbeResults();
    },
    invalidateModels() {
      this.modelRequest += 1;
      this.modelController?.abort();
      this.availableModels = [];
      this.modelMessage = '';
      this.modelsLoading = false;
    },
    clearProbeResults() {
      for (const target of this.$root.querySelectorAll('[data-ai-probe-result]')) {
        aiPendingProbe.delete(target);
        target.removeAttribute('aria-busy');
        target.replaceChildren();
      }
    },
    providerChanged(event) {
      if (event.target.closest('dialog')) return;
      this.clearProbeResults();
      if (['host', 'protocol', 'apiKey', 'clearKey'].includes(event.target.name)) this.invalidateModels();
    },
    destroy() {
      this.invalidateModels();
      this.$refs.modelDialog?.close();
    },
    async loadModels() {
      const form = this.$root;
      if (!form.elements.host.checkValidity()) {
        this.modelMessage = '请先关闭弹窗并填写有效的 API 基础地址。';
        return;
      }
      this.invalidateModels();
      const request = this.modelRequest;
      this.modelsLoading = true;
      this.modelController = new AbortController();
      const body = new URLSearchParams();
      for (const name of ['profileId', 'host', 'protocol', 'apiKey', 'clearKey']) {
        const field = form.elements[name];
        if (field && (field.type !== 'checkbox' || field.checked)) body.set(name, field.value);
      }
      try {
        const response = await fetch('/index/ai/models', {method: 'POST', body, signal: this.modelController.signal});
        const result = await response.json();
        if (request !== this.modelRequest) return;
        this.availableModels = response.ok && Array.isArray(result.models) ? result.models : [];
        this.modelMessage = result.message || '获取失败，请检查地址和密钥，或手动填写模型 ID。';
      } catch (error) {
        if (request === this.modelRequest) this.modelMessage = '获取失败，请检查连接后重试，或手动填写模型 ID。';
      } finally {
        if (request === this.modelRequest) this.modelsLoading = false;
      }
    }
  }));
});
