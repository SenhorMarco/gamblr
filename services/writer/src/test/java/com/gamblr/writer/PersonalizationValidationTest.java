package com.gamblr.writer;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PersonalizationValidationTest {
    @Test void acceptsAccentsDigitsAndThirtyCharacters() {
        assertEquals("Campeão 123 #1",PersonalizationService.value(Requests.PersonalizationAction.TITLE," Campeão 123 #1 "));
        assertEquals("a".repeat(30),PersonalizationService.value(Requests.PersonalizationAction.TITLE,"a".repeat(30)));
        assertEquals("é",PersonalizationService.value(Requests.PersonalizationAction.TITLE,"e\u0301"));
    }
    @Test void rejectsEmojiSequencesBlankAndControlCharacters() {
        for(String title:new String[]{"", "  ", "a".repeat(31),"🏆","👩‍💻","🇧🇷","1️⃣","👍🏽","a\nb","a\u202eb"})
            assertThrows(ApiError.class,()->PersonalizationService.value(Requests.PersonalizationAction.TITLE,title),title);
    }
    @Test void rgbUsesSixHexDigitsAndNeverAcceptsCssExpressions() {
        assertEquals("#ab01ff",PersonalizationService.value(Requests.PersonalizationAction.NAME_COLOR,"#AB01FF"));
        for(String color:new String[]{"red","#fff","#12345678","rgb(1,2,3)","#ffffff;display:none"})
            assertThrows(ApiError.class,()->PersonalizationService.value(Requests.PersonalizationAction.TITLE_COLOR,color));
    }
}
