package edu.mcw.rgdai.model;

public class Question {
    private String question;
    /** Chat model picked in the UI; null or unlisted means the configured default. */
    private String model;
    /** Type of the RGD page the chat was opened from (see page-context.json); null when none. */
    private String page;
    /** RGD ID or accession of the record on that page; null when the page shows none. */
    private String pageId;

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

    public String getPage() {
        return page;
    }

    public void setPage(String page) {
        this.page = page;
    }

    public String getPageId() {
        return pageId;
    }

    public void setPageId(String pageId) {
        this.pageId = pageId;
    }
}
