package com.orgzly.org;

import com.orgzly.org.datetime.OrgRange;

import java.util.ArrayList;
import java.util.List;

/**
 * Heading with text below it.
 *
 * Does not contain any coordinates (position within the outline), not even a level.
 */
public class OrgHead {
    private String title;

    private List<String> tags;

    private String state;

    private String priority;

    private OrgRange scheduled;
    private OrgRange deadline;
    private OrgRange closed;

    private OrgRange clock; // TODO: Create OrgClock with elapsed time?

    private OrgProperties properties;

    private List<String> logbook;

    private OrgContent content;

    /**
     * Creates an empty heading.
     */
    public OrgHead() {
        this("");
    }

    public OrgHead(String str) {
        this.title = str;
    }

    /**
     * Title.
     *
     * @return title
     */
    public String getTitle() {
        if (title == null) {
            return "";
        } else {
            return title;
        }
    }

    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * Tags.
     *
     * @return list of tags
     */
    public List<String> getTags() {
        if (tags == null) {
            return new ArrayList<>();
        } else {
            return tags;
        }
    }

    public boolean hasTags() {
        return tags != null && !tags.isEmpty();
    }

    public void setTags(String[] tags) {
        if (tags == null) {
            throw new IllegalArgumentException("Tags passed to setTags cannot be null");
        }

        this.tags = new ArrayList<>();

        /* Only add non-null and non-empty strings. */
        for (String tag: tags) {
            if (!OrgStringUtils.isEmpty(tag)) {
                this.tags.add(tag);
            }
        }
    }

    private OrgContent getContentObject() {
        if (content == null) {
            content = new OrgContent();
        }
        return content;
    }

    /**
     * Content (body). Text after the heading.
     *
     * @return the content text
     */
    public String getContent() {
        return getContentObject().toString();
    }

    /**
     * @return {@code true} if there is a text below heading, {@code false} otherwise
     */
    public boolean hasContent() {
        return content != null && !content.isEmpty();
    }

    public void setContent(String s) {
        getContentObject().set(s);
    }

    public void appendContent(String s) {
        getContentObject().append(s);
    }

    /**
     * Scheduled time.
     *
     * @return scheduled time or {@code null} if not set
     */
    public OrgRange getScheduled() {
        if (hasScheduled()) {
            return scheduled;
        }
        return null;
    }

    public boolean hasScheduled() {
        return scheduled != null && scheduled.isSet();
    }

    public void setScheduled(OrgRange time) {
        scheduled = time;
    }

    /**
     * Closed time.
     *
     * @return closed time or {@code null} if not set
     */
    public OrgRange getClosed() {
        if (hasClosed()) {
            return closed;
        }
        return null;
    }

    public boolean hasClosed() {
        return closed != null && closed.isSet();
    }

    public void setClosed(OrgRange time) {
        closed = time;
    }

    /**
     * Deadline time.
     *
     * @return deadline time or {@code null} if not set
     */
    public OrgRange getDeadline() {
        if (hasDeadline()) {
            return deadline;
        }
        return null;
    }

    public boolean hasDeadline() {
        return deadline != null && deadline.isSet();
    }

    public void setDeadline(OrgRange time) {
        deadline = time;
    }

    /**
     * CLOCK time.
     *
     * @return clock time or {@code null} if not set
     */
    public OrgRange getClock() {
        if (hasClock()) {
            return clock;
        }
        return null;
    }

    public boolean hasClock() {
        return clock != null && clock.isSet();
    }

    public void setClock(OrgRange time) {
        clock = time;
    }

    /**
     * Priority.
     *
     * @return priority
     */
    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    /**
     * State.
     *
     * @return state
     */
    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    /**
     * Properties.
     *
     * @return list of properties
     */
    public OrgProperties getProperties() {
        if (properties == null) {
            properties = new OrgProperties();
        }

        return properties;
    }

    public boolean hasProperties() {
        return properties != null && !properties.isEmpty();
    }

    public void addProperty(String name, String value) {
        if (properties == null) {
            properties = new OrgProperties();
        }

        properties.put(name, value);
    }

    public void setProperties(OrgProperties properties) {
        this.properties = properties;
    }

    public void removeProperties() {
        properties = null;
    }

    /*
     * Logbook.
     */

    public void initLogbook() {
        if (logbook == null) {
            logbook = new ArrayList<>();
        }
    }

    public List<String> getLogbook() {
        if (logbook == null) {
            throw new IllegalArgumentException("Logbook does not exist");
        }

        return logbook;
    }

    public boolean hasLogbook() {
        return logbook != null;
    }

    public void addLog(String log) {
        initLogbook();

        logbook.add(log);
    }

    public String toString() {
        return title;
    }
}
