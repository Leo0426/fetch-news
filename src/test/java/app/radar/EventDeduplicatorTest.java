package app.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventDeduplicatorTest {
    @TempDir Path directory;
    static RadarTypes.Analysis analysis(String link,String profile,double score) {
        return new RadarTypes.Analysis(link,profile,"AI",score,"Reason","Summary",List.of(),false,link,null);
    }
    @Test void groupsOnlyWithinProfileAndSelectsHighestScoringPrimary() throws Exception {
        var first=analysis("https://a","tech-news",9);
        var second=analysis("https://b","tech-news",8);
        var blog=analysis("https://c","tech-blog",7);
        var run=RadarFixtures.run(RadarConfig.defaults(),List.of());
        String id=RadarJson.hash(first.link());
        var dedup=new EventDeduplicator(RadarFixtures.model(directory,"{\"match\":\""+id+"\"}"),RadarFixtures.MAPPER);
        var groups=dedup.group(List.of(second,blog,first),run);
        assertEquals(2,groups.size());
        var news=groups.stream().filter(g->g.primary().profile().equals("tech-news")).findFirst().orElseThrow();
        assertEquals("https://a",news.primary().link()); assertEquals("https://b",news.related().getFirst().link());
    }
    @Test void invalidOrDistinctDecisionsNeverDiscardArticles() throws Exception {
        var input=List.of(analysis("https://a","tech-news",8),analysis("https://b","tech-news",7));
        for(String response:List.of("{}","{\"match\":\"unknown\"}","{\"match\":\"distinct\"}")) {
            var events=new EventDeduplicator(RadarFixtures.model(directory,response),RadarFixtures.MAPPER).group(input,RadarFixtures.run(RadarConfig.defaults(),List.of()));
            assertEquals(2,events.size()); assertTrue(events.stream().allMatch(e->e.related().isEmpty()));
        }
    }
    @Test void doesNotMergeArticlesWithKnownDifferentPublicationDates() throws Exception {
        var a=analysis("https://a","tech-news",9);var b=analysis("https://b","tech-news",8);
        var input=List.of(new RadarTypes.Candidate(a.link(),"Version 1 released","body",null,"2026-10-18T01:00:00Z",1,List.of("/a")),
                new RadarTypes.Candidate(b.link(),"Version 2 released","body",null,"2026-10-19T01:00:00Z",2,List.of("/a")));
        var dedup=new EventDeduplicator(RadarFixtures.model(directory,"{\"match\":\""+RadarJson.hash(a.link())+"\"}"),RadarFixtures.MAPPER);
        assertEquals(2,dedup.group(List.of(a,b),RadarFixtures.run(RadarConfig.defaults(),input)).size());
    }

    @Test void differentExplicitVersionsAreDistinctEvenOnSameDay() throws Exception {
        var a=analysis("https://a","tech-news",9);var b=analysis("https://b","tech-news",8);
        var input=List.of(new RadarTypes.Candidate(a.link(),"Library v1.0 released","body",null,"2026-10-19T01:00:00Z",1,List.of("/a")),
                new RadarTypes.Candidate(b.link(),"Library v2.0 released","body",null,"2026-10-19T02:00:00Z",2,List.of("/a")));
        var dedup=new EventDeduplicator(RadarFixtures.model(directory,"{\"match\":\""+RadarJson.hash(a.link())+"\"}"),RadarFixtures.MAPPER);
        assertEquals(2,dedup.group(List.of(a,b),RadarFixtures.run(RadarConfig.defaults(),input)).size());
    }

}
