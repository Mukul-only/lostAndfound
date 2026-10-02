package com.example.lostandfound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.lostandfound.ui.claims.ClaimCardContent;
import org.junit.Test;

/**
 * The claim card shows the finder's question and the claimant's answer, but only
 * when there is real text. LOST reports carry no question, so the "no question"
 * cases are the common path, not an edge case.
 */
public class ClaimCardContentTest {

    @Test
    public void nullQuestionIsNotShown() {
        assertFalse(ClaimCardContent.hasQuestion(null));
        assertNull(ClaimCardContent.question(null));
    }

    @Test
    public void blankAndWhitespaceQuestionsAreNotShown() {
        assertFalse(ClaimCardContent.hasQuestion(""));
        assertFalse(ClaimCardContent.hasQuestion("   "));
        assertFalse(ClaimCardContent.hasQuestion("\n\t  \n"));
        assertNull(ClaimCardContent.question("   "));
    }

    @Test
    public void realQuestionIsShownAndTrimmed() {
        assertTrue(ClaimCardContent.hasQuestion("What is written on the base?"));
        assertEquals("What is written on the base?",
                ClaimCardContent.question("What is written on the base?"));
        assertEquals("What is written on the base?",
                ClaimCardContent.question("  What is written on the base?\n"));
    }

    @Test
    public void answerIsTrimmed() {
        assertEquals("It says Bob on the base",
                ClaimCardContent.answer("  It says Bob on the base\n"));
    }

    @Test
    public void missingAnswerIsNullSoTheBlockHides() {
        assertNull(ClaimCardContent.answer(null));
        assertNull(ClaimCardContent.answer(""));
        assertNull(ClaimCardContent.answer("  \n "));
    }

    @Test
    public void answerStillRendersWhenThereIsNoQuestion() {
        // The important case: a LOST item has no question, but the claimant's
        // answer must still be visible.
        assertFalse(ClaimCardContent.hasQuestion(null));
        assertEquals("It says Bob on the base", ClaimCardContent.answer("It says Bob on the base"));
    }
}
