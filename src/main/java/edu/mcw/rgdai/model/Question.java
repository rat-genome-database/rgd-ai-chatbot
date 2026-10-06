package edu.mcw.rgdai.model;

public class Question {
    private String question;
    /** Chat model picked in the UI; null or unlisted means the configured default. */
    private String model;

    public Question() {
    }

    public Question(String question) {
        this.question = question;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
