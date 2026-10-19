package app.radar;

import app.radar.RadarTypes.Report;
import java.net.URI;

public class RadarRenderer {
    public String markdown(Report report) {
        StringBuilder result=new StringBuilder("# 精选日报 · ").append(report.date()).append("\n\nAI 生成 · ")
                .append(escape(report.zoneId())).append(" · 候选 ").append(report.totalCandidates()).append(" · 未覆盖 ").append(report.omitted()).append(" · 失败 ").append(report.failures()).append("\n\n");
        if(report.stories().isEmpty()) result.append("没有符合筛选标准的内容。\n");
        for(String profile:report.stories().stream().map(s->s.event().primary().profile()).distinct().sorted().toList()) {
            result.append("## ").append(escape(profile)).append("\n\n");
            for(var story:report.stories().stream().filter(s->s.event().primary().profile().equals(profile)).toList()) {
                var item=story.event().primary();
                result.append("### ").append(item.score()).append(" · ").append(link(report.titles().getOrDefault(item.link(),item.summary()),item.link())).append("\n\n")
                        .append(escape(item.reason())).append("\n\n");
                for(var block:story.blocks().entrySet()) {
                    result.append("**").append(escape(block.getKey())).append("**\n\n");
                    for(String text:block.getValue()) result.append(escape(text)).append("\n\n");
                }
                if(story.error()!=null) result.append("区块缺失：").append(escape(story.error())).append("\n\n");
                for(var related:story.event().related()) result.append("- 关联原文：").append(link(report.titles().getOrDefault(related.link(),related.summary()),related.link())).append("\n");
                result.append("\n");
            }
        }
        return result.toString();
    }
    public static String safeUrl(String value) {
        try {
            URI uri=URI.create(value);
            if(uri.getHost()==null || uri.getUserInfo()!=null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return "";
            return uri.toASCIIString().replace("<","%3C").replace(">","%3E").replace("\\","%5C");
        } catch(Exception e) { return ""; }
    }
    private String link(String label,String url) {
        String safe=safeUrl(url);
        return safe.isEmpty() ? escape(label) : "["+escape(label)+"](<"+safe+">)";
    }
    private String escape(String value) {
        String text=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        for(String c:new String[]{"\\","[","]","(",")","*","_","`","#","!","|"}) text=text.replace(c,"\\"+c);
        return text;
    }
}
