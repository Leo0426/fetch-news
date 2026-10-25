// AI platform form in /index: protocol presets, model discovery and the model list.
document.addEventListener('alpine:init', () => {
  Alpine.data('aiProviderForm', () => ({
    protocol: '',
    model: '',
    models: [],
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
      this.models = Array.from(this.$root.querySelectorAll('[data-saved-model]'), field => field.value);
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
      if (!this.model) this.model = id;
      this.$refs.modelDialog.close();
    },
    removeModel(id) {
      if (id !== this.model) this.models = this.models.filter(value => value !== id);
    },
    invalidateModels() {
      this.modelRequest += 1;
      this.modelController?.abort();
      this.availableModels = [];
      this.modelMessage = '';
      this.modelsLoading = false;
    },
    providerChanged(event) {
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
