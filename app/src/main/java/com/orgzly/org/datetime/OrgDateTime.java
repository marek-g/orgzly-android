package com.orgzly.org.datetime;

import com.orgzly.org.OrgPatterns;
import com.orgzly.org.OrgStringUtils;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Org mode timestamp.
 *
 * For example {@literal <2014-05-26>} or {@literal [2014-05-26 Mon 09:15]}.
 *
 * http://orgmode.org/manual/Timestamps.html
 * http://orgmode.org/manual/Repeated-tasks.html
 *
 */
public class OrgDateTime {
    private final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd EEE", Locale.ENGLISH);

    private final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm", Locale.ENGLISH);

    private boolean isActive;

    /*
     * Lazy usage of strings and calendars.
     * TODO: Too confusing? Perhaps remove it and let the user do it, if he wants to?
     */
    private String string;
    private String stringWithoutBrackets;

    private Calendar cal;
    private boolean hasTime;

    private Calendar endCal;

    private OrgRepeater repeater;

    private OrgDelay delay; // or warning period for deadline time

    private OrgDateTime() {
    }

    public OrgDateTime(OrgDateTime orgDateTime) {
        this.string = orgDateTime.toString();
    }

    /**
     * Creates instance representing current time.
     *
     * @param isActive {@code true} to create active {@link OrgDateTime}, {@code false} for inactive
     *
     */
    public OrgDateTime(boolean isActive) {
        this.isActive = isActive;

        this.cal = GregorianCalendar.getInstance();
        this.cal.set(Calendar.SECOND, 0);
        this.cal.set(Calendar.MILLISECOND, 0);

        this.hasTime = true;
    }

    public OrgDateTime(long millis, boolean isActive) {
        this.isActive = isActive;

        this.cal = GregorianCalendar.getInstance();
        this.cal.setTimeInMillis(millis);
        this.cal.set(Calendar.SECOND, 0);
        this.cal.set(Calendar.MILLISECOND, 0);

        this.hasTime = true;
    }

    /**
     * Creates instance from the given string
     *
     * @param str Org timestamp such as {@code <2014-05-26> or [2014-05-26 Mon 09:15]}
     *
     * @return instance if the provided string is not empty
     */
    public static OrgDateTime parse(String str) {
        if (str == null) {
            throw new IllegalArgumentException("OrgDateTime cannot be created from null string");
        }

        if (str.length() == 0) {
            throw new IllegalArgumentException("OrgDateTime cannot be created from null string");
        }

        OrgDateTime time = new OrgDateTime();
        time.string = str;

        return time;
    }

    public static OrgDateTime parseOrNull(String str) {
        if (OrgStringUtils.isEmpty(str)) {
            return null;
        }

        OrgDateTime time = new OrgDateTime();
        time.string = str;

        return time;
    }

    // TODO: Rename to parse, rename other methods to getInstance, add *orThrow methods if needed
    public static OrgDateTime doParse(String str) {
        try {
            OrgDateTime time = OrgDateTime.parse(str);
            time.ensureCalendar();
            return time;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns {@link Calendar} representing this time.
     *
     * @return {@link Calendar} representation of the time.
     * @throws java.lang.IllegalStateException if time is not set
     */
    public Calendar getCalendar() {
        ensureCalendar();
        return cal;
    }

    public boolean isActive() {
        ensureCalendar();
        return isActive;
    }

    public boolean hasTime() {
        ensureCalendar();
        return hasTime;
    }

    public boolean hasEndTime() {
        ensureCalendar();
        return endCal != null;
    }

    public Calendar getEndCalendar() {
        ensureCalendar();
        return endCal;
    }

    public boolean hasRepeater() {
        ensureCalendar();
        return repeater != null;
    }

    public OrgRepeater getRepeater() {
        ensureCalendar();
        return repeater;
    }

    public boolean hasDelay() {
        ensureCalendar();
        return delay != null;
    }

    public OrgDelay getDelay() {
        ensureCalendar();
        return delay;
    }

    /*
     * Convert from Calendar to String.
     *   <2013-06-15 Sat>
     *   [2013-06-15 Sat 12:32]
     */
    private String fromCalendar(boolean withBrackets) {
        StringBuilder result = new StringBuilder();

        if (withBrackets) {
            result.append(isActive() ? '<' : '[');
        }

        result.append(DATE_FORMAT.format(cal.getTime()));

        if (hasTime) {
            result.append(" ");
            result.append(TIME_FORMAT.format(cal.getTime()));

            if (endCal != null) {
                result.append("-");
                result.append(TIME_FORMAT.format(endCal.getTime()));
            }
        }

        if (hasRepeater()) {
            result.append(" ");
            result.append(repeater);
        }

        if (hasDelay()) {
            result.append(" ");
            result.append(delay);
        }

        if (withBrackets) {
            result.append(isActive() ? '>' : ']');
        }

        return result.toString();
    }


    private void ensureCalendar() {
        if (cal == null) {
            if (string == null) {
                throw new IllegalStateException("Missing string");
            }

            parseString();
        }
    }

    /**
     * Parse {@link OrgDateTime#string} and populate other fields.
     */
    private void parseString() {
        Matcher m;

        cal = Calendar.getInstance();
        endCal = null;

        switch (string.charAt(0)) {
            case '<':
                isActive = true;
                break;

            case '[':
                isActive = false;
                break;

            default:
                throw new IllegalArgumentException("Timestamp \"" + string + "\" must start with < or [");
        }

        m = OrgPatterns.DT_MAYBE_WITH_TIME_P.matcher(string);
        if (! m.find()) {
            matchFailed(string, OrgPatterns.DT_MAYBE_WITH_TIME_P);
        }

        cal.set(Calendar.YEAR, Integer.valueOf(m.group(2)));
        cal.set(Calendar.MONTH, Integer.valueOf(m.group(3)) - 1);
        cal.set(Calendar.DAY_OF_MONTH, Integer.valueOf(m.group(4)));

        if (! OrgStringUtils.isEmpty(m.group(6))) { // Has time of day.
            parseTimeOfDay(string.substring(m.start(6)));
        } else {
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            hasTime = false;
        }

        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        /* Repeater with at-least interval. */
        m = OrgPatterns.REPEAT_P.matcher(string);
        if (m.find()) {
            repeater = OrgRepeater.parse(m.group(1));
        }

        /* Delay. */
        m = OrgPatterns.TIME_DELAY_P.matcher(string);
        if (m.find()) {
            delay = OrgDelay.parse(m.group());
        }
    }


    private void parseTimeOfDay(String str) {
        Matcher m = OrgPatterns.TIME_OF_DAY_P.matcher(str);
        if (! m.find()) {
            matchFailed(str, OrgPatterns.TIME_OF_DAY_P);
        }

        cal.set(Calendar.HOUR_OF_DAY, Integer.valueOf(m.group(2)));
        cal.set(Calendar.MINUTE, Integer.valueOf(m.group(3)));
        hasTime = true;

        if (! OrgStringUtils.isEmpty(m.group(4))) { // End time exists
            endCal = Calendar.getInstance();
            endCal.setTime(cal.getTime());
            endCal.set(Calendar.HOUR_OF_DAY, Integer.valueOf(m.group(6)));
            endCal.set(Calendar.MINUTE, Integer.valueOf(m.group(7)));
            endCal.set(Calendar.SECOND, 0);
            endCal.set(Calendar.MILLISECOND, 0);
        }
    }

    private void matchFailed(String string, Pattern pattern) {
        throw new IllegalArgumentException("Failed matching \"" + string + "\" against " + pattern);
    }

    public boolean shift(Calendar now) {
        /*
         * Calling the method below will also make sure
         * that string is parsed and calendars are updated.
         */
        if (hasRepeater()) {
            cal = getCalendar();
            endCal = getEndCalendar();

            /* Shift both calendars. */

            repeater.shiftCalendar(cal, now);

            if (endCal != null) {
                repeater.shiftCalendar(endCal, now);
            }

            /* Invalidate string representations. */
            string = null;
            stringWithoutBrackets = null;
        }

        return repeater != null;
    }

    public String toString() {
        if (string == null && cal != null) {
            string = fromCalendar(true);
        }
        return string;
    }

    public String toStringWithoutBrackets() {
        ensureCalendar();

        if (stringWithoutBrackets == null && cal != null) {
            stringWithoutBrackets = fromCalendar(false);
        }

        return stringWithoutBrackets;
    }

    /** @return inner state without trying to generate a new string from Calendar. */
    public String toDebugString() {
        return String.format("cal: %s string: %s", cal != null ? cal.getTime() : "null", string);
    }

    public static class Builder {
        private boolean isActive;

        private int year;
        private int month;
        private int day;

        private boolean hasTime;
        private int hour;
        private int minute;

        private boolean hasEndTime;
        private int endHour;
        private int endMinute;

        private OrgRepeater repeater;

        private OrgDelay delay;

        public Builder() {
        }

        public Builder(OrgDateTime orgDateTime) {
            this
                    .setIsActive(orgDateTime.isActive())
                    .setDateTime(orgDateTime.getCalendar().getTimeInMillis())
                    .setHasTime(orgDateTime.hasTime())
                    .setRepeater(orgDateTime.getRepeater())
                    .setDelay(orgDateTime.getDelay());

            if (orgDateTime.hasEndTime()) {
                this.setHasEndTime(true);
                this.setEndHourAndMinute(orgDateTime.getEndCalendar().getTimeInMillis());
            }
        }

        public Builder setIsActive(boolean isActive) {
            this.isActive = isActive;
            return this;
        }

        public Builder setHasTime(boolean hasTime) {
            this.hasTime = hasTime;
            return this;
        }

        public Builder setHasEndTime(boolean hasEndTime) {
            this.hasEndTime = hasEndTime;
            return this;
        }

        public Builder setYear(int year) {
            this.year = year;
            return this;
        }

        public Builder setMonth(int month) {
            this.month = month;
            return this;
        }

        public Builder setDay(int day) {
            this.day = day;
            return this;
        }

        public Builder setHour(int hour) {
            this.hour = hour;
            return this;
        }

        public Builder setMinute(int minute) {
            this.minute = minute;
            return this;
        }

        public Builder setEndHour(int hour) {
            this.endHour = hour;
            return this;
        }

        public Builder setEndMinute(int minute) {
            this.endMinute = minute;
            return this;
        }

        public Builder setDateTime(long timestamp) {
            Calendar cal = new GregorianCalendar();
            cal.setTimeInMillis(timestamp);

            return setYear(cal.get(Calendar.YEAR))
                    .setMonth(cal.get(Calendar.MONTH))
                    .setDay(cal.get(Calendar.DAY_OF_MONTH))
                    .setHour(cal.get(Calendar.HOUR_OF_DAY))
                    .setMinute(cal.get(Calendar.MINUTE));
        }

        public Builder setEndHourAndMinute(long timestamp) {
            Calendar cal = new GregorianCalendar();
            cal.setTimeInMillis(timestamp);

            return setEndHour(cal.get(Calendar.HOUR_OF_DAY))
                    .setEndMinute(cal.get(Calendar.MINUTE));
        }

        public Builder setRepeater(OrgRepeater repeater) {
            this.repeater = repeater;
            return this;
        }

        public Builder setDelay(OrgDelay delay) {
            this.delay = delay;
            return this;
        }

        public OrgDateTime build() {
            OrgDateTime time = new OrgDateTime();

            time.isActive = isActive;

            time.hasTime = hasTime;

            if (hasTime) {
                time.cal = new GregorianCalendar(year, month, day, hour, minute);
            } else {
                time.cal = new GregorianCalendar(year, month, day);

            }

            if (hasEndTime) {
                time.endCal = new GregorianCalendar(year, month, day, endHour, endMinute);
            }

            if (repeater != null) {
                time.repeater = repeater;
            }

            if (delay != null) {
                time.delay = delay;
            }

            return time;
        }
    }
}
