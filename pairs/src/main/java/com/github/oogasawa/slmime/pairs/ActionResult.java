package com.github.oogasawa.slmime.pairs;

/**
 * Whether a step succeeded, and its text: what the copied classes return, as ActionResult of POJO-actor 4.7.0 (not on
 * Maven Central) does.
 */
public record ActionResult(boolean success, String result) {

    public boolean isSuccess() {
        return success;
    }

    public String getResult() {
        return result;
    }
}
