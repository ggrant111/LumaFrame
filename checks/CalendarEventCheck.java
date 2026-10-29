package com.blockhead.lumaframe;

import java.time.*;
import java.util.TimeZone;

public class CalendarEventCheck {
  private static long utc(String s) {
    return Instant.parse(s).toEpochMilli();
  }

  private static void check(boolean result, String message) {
    if (!result) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"));
    CalendarEvent all =
        new CalendarEvent(
            1, "All day", utc("2026-10-29T00:00:00Z"), utc("2026-10-30T00:00:00Z"), true, "");
    check(all.overlaps(LocalDate.parse("2026-10-29")), "all-day UTC date");
    check(!all.overlaps(LocalDate.parse("2026-10-28")), "no preceding-day shift");
    check(!all.overlaps(LocalDate.parse("2026-10-30")), "exclusive end");
    CalendarEvent multi =
        new CalendarEvent(
            2, "Overnight", utc("2026-10-30T05:00:00Z"), utc("2026-10-30T08:00:00Z"), false, "");
    check(multi.overlaps(LocalDate.parse("2026-10-29")), "overnight first day");
    check(multi.overlaps(LocalDate.parse("2026-10-30")), "overnight second day");
    CalendarEvent dst =
        new CalendarEvent(
            3, "DST", utc("2026-11-01T07:30:00Z"), utc("2026-11-01T09:30:00Z"), false, "");
    check(dst.overlaps(LocalDate.parse("2026-11-01")), "DST fall-back day");
    check(!dst.overlaps(LocalDate.parse("2026-11-02")), "DST next day excluded");
    CalendarEvent zero =
        new CalendarEvent(
            4, "Instant", utc("2026-10-29T18:00:00Z"), utc("2026-10-29T18:00:00Z"), false, "");
    check(zero.overlaps(LocalDate.parse("2026-10-29")), "zero duration event");
    System.out.println("PASS: 8 date-boundary checks");
  }
}
