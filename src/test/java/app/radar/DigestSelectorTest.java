package app.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DigestSelectorTest {
    @Test void appliesCrossProfileCategoryCapsWithoutFillingFromBelowThreshold() throws Exception {
        var config=RadarFixtures.MAPPER.readValue("{\"maxItems\":3,\"categoryGroups\":{\"ai\":{\"limit\":1,\"categories\":[\"AI\"]}}}",RadarConfig.class);
        var a=EventDeduplicatorTest.analysis("https://a","tech-news",9);
        var b=EventDeduplicatorTest.analysis("https://b","tech-blog",8);
        var c=new RadarTypes.Analysis("https://c","tech-blog","other",7.0,"r","s",List.of(),false,"c",null);
        var low=new RadarTypes.Analysis("https://low","tech-news","other",6.99,"r","s",List.of(),false,"low",null);
        var selection=new DigestSelector().select(List.of(new RadarTypes.Event("a",a,List.of()),new RadarTypes.Event("b",b,List.of()),new RadarTypes.Event("c",c,List.of()),new RadarTypes.Event("low",low,List.of())),config);
        assertEquals(List.of("https://a","https://c"),selection.selected().stream().map(e->e.primary().link()).toList());
        assertEquals("category_limit",selection.decisions().stream().filter(d->d.link().equals("https://b")).findFirst().orElseThrow().reason());
    }
    @Test void capsAtTwentyEventsAndRelatedArticlesDoNotUseSlots() {
        var events=new ArrayList<RadarTypes.Event>();
        for(int i=0;i<25;i++) events.add(new RadarTypes.Event(""+i,EventDeduplicatorTest.analysis("https://a/"+i,"tech-news",8),List.of()));
        assertEquals(20,new DigestSelector().select(events,RadarConfig.defaults()).selected().size());
        assertTrue(new DigestSelector().select(List.of(),RadarConfig.defaults()).selected().isEmpty());
    }
    @Test void explicitOtherGroupCannotCollideWithUnmappedCategoryCount() throws Exception {
        var config=RadarFixtures.MAPPER.readValue("{\"categoryGroups\":{\"other\":{\"limit\":1,\"categories\":[\"AI\"]}}}",RadarConfig.class);
        var news=new RadarTypes.Analysis("https://news","tech-news","News",9.0,"r","s",List.of(),false,"news",null);
        var ai=EventDeduplicatorTest.analysis("https://ai","tech-news",8);
        assertEquals(2,new DigestSelector().select(List.of(new RadarTypes.Event("news",news,List.of()),new RadarTypes.Event("ai",ai,List.of())),config).selected().size());
    }

}
