package app.support;

public final class SourceLabels {
    private SourceLabels() {}

    public static String shortName(String routePath) {
        if (routePath == null) return "?";
        String[] parts = routePath.split("/");
        if (parts.length < 2) return routePath;
        return switch (parts[1]) {
            case "telegram" -> switch (parts[parts.length - 1]) {
                case "scitech_fans" -> "周刊 TG";
                case "newlearner" -> "Newlearner TG";
                case "solidot" -> "Solidot TG";
                default -> "TG @" + parts[parts.length - 1];
            };
            case "x" -> switch (parts[parts.length - 1]) {
                case "openai" -> "OpenAI X";
                case "anthropic" -> "Anthropic X";
                case "huggingface" -> "Hugging Face X";
                case "karpathy" -> "Karpathy";
                case "simonw" -> "Simon Willison";
                case "github" -> "GitHub X";
                default -> "X @" + parts[parts.length - 1];
            };
            case "hn"            -> "HN";
            case "bbc"           -> "BBC";
            case "github"        -> "GitHub";
            case "openai"        -> "OpenAI";
            case "huggingface"   -> "Hugging Face";
            case "google"        -> parts.length > 2 && "security".equals(parts[2])
                    ? "Google Security" : "Google Research";
            case "ollama"        -> "Ollama";
            case "chinanews"     -> "中新网";
            case "un"            -> "联合国新闻";
            case "rthk"          -> "香港电台";
            case "guardian"      -> "The Guardian";
            case "nasa"          -> "NASA";
            case "quanta"        -> "Quanta";
            case "sciencenews"   -> "Science News";
            case "carbonbrief"   -> "Carbon Brief";
            case "noaa"          -> "NOAA";
            case "cloudflare"    -> "Cloudflare";
            case "martinfowler"  -> "Martin Fowler";
            case "netflix"       -> "Netflix";
            case "infoq"         -> "InfoQ 中文";
            case "krebs"         -> "Krebs on Security";
            case "schneier"      -> "Schneier";
            case "ecb"           -> "欧洲央行";
            case "federalreserve" -> "美联储";
            case "smashing"      -> "Smashing";
            case "aeon"          -> "Aeon";
            case "ruanyifeng"    -> "阮一峰";
            case "solidot"       -> "Solidot";
            case "ourworldindata" -> "Our World in Data";
            case "pewresearch" -> "Pew Research Center";
            case "hackaday" -> "Hackaday";
            case "raspberrypi" -> "Raspberry Pi";
            case "godot" -> "Godot Engine";
            case "factorio" -> "Factorio Friday Facts";
            case "jstor" -> "JSTOR Daily";
            case "public-domain-review" -> "公共领域评论";
            case "colossal" -> "Colossal";
            case "aperture" -> "Aperture";
            case "dezeen" -> "Dezeen";
            case "archdaily" -> "ArchDaily";
            case "kff" -> "KFF Health News";
            case "stat" -> "STAT";
            case "psyche" -> "Psyche";
            case "aps" -> "APS 心理科学研究";
            case "edsurge" -> "EdSurge";
            case "hechinger" -> "The Hechinger Report";
            case "deepmind" -> "DeepMind";
            case "bair" -> "BAIR";
            case "simonwillison" -> "Simon Willison";
            case "aihot" -> "AIHOT";
            case "nvidia" -> "NVIDIA";
            case "importai" -> "Import AI";
            case "latentspace" -> "Latent Space";
            case "interconnects" -> "Interconnects";
            case "oneusefulthing" -> "One Useful Thing";
            case "lilianweng" -> "Lil'Log";
            case "qbitai" -> "量子位";
            case "leiphone" -> "雷峰网";
            case "vllm" -> "vLLM";
            case "huxiu" -> "虎嗅";
            case "tmtpost" -> "钛媒体";
            case "ithome" -> "IT之家";
            case "zhidx" -> "智东西";
            case "appinn" -> "小众软件";
            case "oschina" -> "开源中国";
            case "woshipm" -> "人人都是产品经理";
            case "npr" -> "NPR";
            case "lwn" -> "LWN";
            case "ftchinese" -> "FT 中文网";
            case "arstechnica" -> "Ars Technica";
            case "ifanr" -> "爱范儿";
            case "geekpark" -> "极客公园";
            case "gcores"        -> "机核";
            case "yystv"         -> "游研社";
            case "reddit"        -> "Reddit";
            case "stackoverflow" -> "SO";
            case "arxiv"         -> "arXiv";
            case "sspai"         -> "少数派";
            case "36kr"          -> "36氪";
            case "xinhua"        -> "新华";
            case "huanqiu"       -> "环球";
            case "cctv"          -> "CCTV";
            case "bilibili"      -> "B站";
            case "cnblogs"       -> "博客园";
            case "microsoft"     -> parts.length > 2 && "research".equals(parts[2])
                    ? "Microsoft Research" : "MSDN";
            case "techcrunch"    -> "TC";
            case "wired"         -> "Wired";
            case "producthunt"   -> "PH";
            case "mritd"         -> "mritd";
            case "diygod"        -> "DIYGod";
            default              -> parts[1];
        };
    }
}
