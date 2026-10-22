package app.ai;

import java.util.List;

public record AiModelList(List<String> models, String message) {
    public static AiModelList from(OllamaClient.ConnectionStatus status) {
        List<String> models = status.availableModels().stream().filter(model -> !model.isBlank()).distinct().toList();
        String message = status.error() != null ? status.error()
                : models.isEmpty() ? "未返回可用模型，可以手动填写模型 ID。" : "已获取 " + models.size() + " 个模型，请选择或手动填写。";
        return new AiModelList(models, message);
    }
}
