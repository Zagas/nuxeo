package org.nuxeo.common.utils;

import static java.time.ZonedDateTime.ofInstant;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static java.time.format.DateTimeFormatter.ISO_ZONED_DATE_TIME;
import static java.time.format.DateTimeFormatter.ofPattern;
import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.NANO_OF_SECOND;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.Date;

public class DateUtils {

    private static String genericPattern = "yyyy[-MM][-dd['T'HH[:mm[:ss[.SSS]]]]][XXX]";

    public static DateTimeFormatter formatter = robustOfPattern(genericPattern);

    public static final DateTimeFormatter[] formatters = { ISO_OFFSET_DATE_TIME, ISO_ZONED_DATE_TIME, formatter, };

    public static final DateTimeFormatter dateTimeFormatter = ofPattern(genericPattern);

    public static final DateTimeFormatter dateFormatter = ofPattern("yyyy[-MM][-dd]");

    public static String format(Date date) {
        return format(toZonedDateTime(date));
    }

    public static String format(ZonedDateTime zdt) {
        return format(zdt, false);
    }

    public static String format(ZonedDateTime zdt, boolean dateOnly) {
        if (zdt == null) {
            return null;
        }
        if (dateOnly) {
            return dateFormatter.format(zdt);
        }
        return dateTimeFormatter.format(zdt);
    }

    public static Date nowIfNull(Date date) {
        return date == null ? new Date() : date;
    }

    public static Calendar nowIfNull(Calendar calendar) {
        return calendar == null ? Calendar.getInstance() : calendar;
    }

    public static ZonedDateTime nowIfNull(ZonedDateTime zdt) {
        return zdt == null ? ZonedDateTime.now() : zdt;
    }

    public static ZonedDateTime parse(String string) {
        return parse(string, formatters);
    }

    public static ZonedDateTime parse(String string, DateTimeFormatter... formatters) {
        // workaround to allow space instead of T after the date part
        if (string.length() > 10 && string.charAt(10) == ' ') {
            char[] s = string.toCharArray();
            s[10] = 'T';
            string = new String(s);
        }
        for (DateTimeFormatter formatter : formatters) {
            try {
                TemporalAccessor ta = formatter.parseBest(string, ZonedDateTime::from, LocalDate::from);
                if (ta instanceof ZonedDateTime) {
                    return (ZonedDateTime) ta;
                } else {
                    return ((LocalDate) ta).atStartOfDay(ZoneOffset.UTC);
                }
            } catch (Exception e) {
                // ignore, try another formatter
            }
        }
        throw new DateTimeException("Could not parse '" + string + "'");
    }

    public static final DateTimeFormatter robustOfPattern(String pattern) {
        return new DateTimeFormatterBuilder().appendPattern(pattern)
                .parseDefaulting(MONTH_OF_YEAR, 1)
                .parseDefaulting(DAY_OF_MONTH, 1)
                .parseDefaulting(HOUR_OF_DAY, 0)
                .parseDefaulting(MINUTE_OF_HOUR, 0)
                .parseDefaulting(SECOND_OF_MINUTE, 0)
                .parseDefaulting(NANO_OF_SECOND, 0)
                .toFormatter()
                .withZone(ZoneId.of("UTC"));
    }

    public static ZonedDateTime toZonedDateTime(Calendar calendar) {
        if (calendar == null) {
            return null;
        }
        return ofInstant(calendar.toInstant(), ZoneId.of("UTC"));
    }

    public static ZonedDateTime toZonedDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return ofInstant(date.toInstant(), ZoneId.of("UTC"));
    }



}