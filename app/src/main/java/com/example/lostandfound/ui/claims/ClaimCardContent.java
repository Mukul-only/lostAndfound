package com.example.lostandfound.ui.claims;

import androidx.annotation.Nullable;

/**
 * Presentation rules for the claim card's question/answer pair.
 *
 * Kept separate from the adapter so the visibility contract is testable without
 * inflating a ViewHolder: a question is shown only when there is real text to
 * show, and the answer is always shown when there is text for it.
 */
public final class ClaimCardContent {

    private ClaimCardContent() {}

    /** True when the finder asked something worth displaying. Null, blank and
     *  whitespace-only questions are all treated as no question — LOST reports
     *  carry no question at all, and the owner-review path never embeds one. */
    public static boolean hasQuestion(@Nullable String question) {
        return question != null && !question.trim().isEmpty();
    }

    /** The question as it should be rendered, or null when there is none.
     *  Trimmed so a stray newline from the API cannot pad the row. */
    @Nullable
    public static String question(@Nullable String question) {
        return hasQuestion(question) ? question.trim() : null;
    }

    /** The answer as it should be rendered, or null when there is none. */
    @Nullable
    public static String answer(@Nullable String answer) {
        if (answer == null) return null;
        String trimmed = answer.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
