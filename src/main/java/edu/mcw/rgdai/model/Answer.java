package edu.mcw.rgdai.model;

public class Answer {
    private String answer;
    /** Whether the RGD page the chat was opened from was used as context for this answer. */
    private boolean pageUsed;

    public Answer() {
    }

    public Answer(String answer) {
        this.answer = answer;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public boolean isPageUsed() {
        return pageUsed;
    }

    public void setPageUsed(boolean pageUsed) {
        this.pageUsed = pageUsed;
    }
}
