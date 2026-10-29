package com.blockhead.lumaframe;
import java.time.*;

final class CalendarEvent {
        final long id,start,end;final String title,location;final boolean allDay;
        CalendarEvent(long id,String title,long start,long end,boolean allDay,String location){this.id=id;this.title=title;this.start=start;this.end=end;this.allDay=allDay;this.location=location;}
        boolean overlaps(LocalDate day){ZoneId zone=allDay?ZoneOffset.UTC:ZoneId.systemDefault();long a=day.atStartOfDay(zone).toInstant().toEpochMilli(),b=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();return start<b && Math.max(end,start+1)>a;}
    }
