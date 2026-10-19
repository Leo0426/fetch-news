package app.ai;

import java.net.URI;
import java.util.List;
import java.util.Set;

public record AiConfig(String host, String model, boolean enabled, String protocol, String apiKey,
                       int maxTokens, boolean jsonMode, String tokenLimitParameter) {
    public static final String DEFAULT_HOST="http://localhost:11434";
    public static final String DEFAULT_MODEL="llama3.2";

    public AiConfig(String host,String model,boolean enabled) {
        this(host,model,enabled,"ollama","",4096,true,"max_tokens");
    }
    public AiConfig {
        if(protocol==null || protocol.isBlank()) { protocol="ollama";jsonMode=true; }
        if(!Set.of("ollama","openai","anthropic").contains(protocol)) throw new IllegalArgumentException("Unknown AI protocol");
        host=host==null || host.isBlank() ? switch(protocol) {
            case "openai" -> "https://api.openai.com/v1";
            case "anthropic" -> "https://api.anthropic.com/v1";
            default -> DEFAULT_HOST;
        } : host.strip().replaceAll("/+$","");
        URI uri;
        try { uri=URI.create(host); }
        catch(IllegalArgumentException e) { throw new IllegalArgumentException("API URL must be a valid HTTP(S) address"); }
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("API URL must be HTTP(S) without credentials, query or fragment");
        model=model==null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        apiKey=apiKey==null ? "" : apiKey.strip();
        if(apiKey.chars().anyMatch(c->c<33 || c>126)) throw new IllegalArgumentException("API key must contain printable ASCII characters without whitespace");
        maxTokens=maxTokens==0 ? 4096 : maxTokens;
        if(maxTokens<1 || maxTokens>1000000) throw new IllegalArgumentException("Output token limit must be 1–1000000");
        tokenLimitParameter=tokenLimitParameter==null ? "max_tokens" : tokenLimitParameter;
        if(!Set.of("max_tokens","max_completion_tokens").contains(tokenLimitParameter)) throw new IllegalArgumentException("Invalid token limit parameter");
    }
    public static AiConfig defaults() { return new AiConfig(null,null,false); }
    public AiConfig withEnabled(boolean value) { return new AiConfig(host,model,value,protocol,apiKey,maxTokens,jsonMode,tokenLimitParameter); }
    public AiConfig withoutCredentials() { return new AiConfig(host,model,enabled,protocol,"",maxTokens,jsonMode,tokenLimitParameter); }
    public List<Object> identity() { return List.of(protocol,host,model,maxTokens,jsonMode,tokenLimitParameter); }
    public boolean sameModel(AiConfig other) { return other!=null && identity().equals(other.identity()); }
    @Override public String toString() { return "AiConfig[protocol="+protocol+", model="+model+", enabled="+enabled+", apiKey=REDACTED]"; }
}
