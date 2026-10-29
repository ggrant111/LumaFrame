package com.blockhead.lumaframe;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;

/** Native, offline photo frame. No account credentials or photo uploads. */
public class MainActivity extends Activity {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService images = Executors.newSingleThreadExecutor();
  private final ExecutorService calendar = Executors.newSingleThreadExecutor();
  private final ArrayList<Uri> photos = new ArrayList<>();
  private final ArrayList<CalendarEvent> events = new ArrayList<>();
  private final ArrayList<GlassPanel> glass = new ArrayList<>();
  private android.content.SharedPreferences prefs;
  private FrameLayout root;
  private ImageView backdrop;
  private TextView clock, date, photoCount, playback;
  private LinearLayout agenda, days;
  private TextView monthLabel;
  private Bitmap currentPhoto, frost;
  private int index, photoGeneration, eventGeneration;
  private boolean resumed, playing = true, frameOnly, calendarConnected;
  private int seconds = 15;
  private LocalDate selected = LocalDate.now();
  private YearMonth month = YearMonth.now();
  private String calendarError = "";
  private final ContentObserver observer =
      new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
          loadEvents();
        }
      };
  private boolean observing;
  private final Runnable ticker =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          clock.setText(
              android.text.format.DateFormat.getTimeFormat(MainActivity.this).format(new Date()));
          date.setText(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")));
          main.postDelayed(this, 1000);
        }
      };
  private final Runnable advance =
      new Runnable() {
        @Override
        public void run() {
          if (resumed && playing && photos.size() > 1) showPhoto(index + 1);
          schedule();
        }
      };
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          if (resumed) {
            loadEvents();
            main.postDelayed(this, 60000);
          }
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    prefs = getSharedPreferences("frame", MODE_PRIVATE);
    seconds = prefs.getInt("seconds", 15);
    playing = prefs.getBoolean("playing", true);
    if (state != null) {
      selected = LocalDate.parse(state.getString("selected", LocalDate.now().toString()));
      month = YearMonth.from(selected);
      index = state.getInt("index");
      frameOnly = state.getBoolean("frameOnly");
    }
    try {
      JSONArray a = new JSONArray(prefs.getString("photos", "[]"));
      for (int n = 0; n < a.length(); n++) photos.add(Uri.parse(a.getString(n)));
    } catch (Exception ignored) {
    }
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    build();
    showPhoto(index);
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putString("selected", selected.toString());
    b.putInt("index", index);
    b.putBoolean("frameOnly", frameOnly);
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    main.post(ticker);
    schedule();
    main.post(refresh);
    if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        && !observing) {
      try {
        getContentResolver().registerContentObserver(CalendarContract.CONTENT_URI, true, observer);
        observing = true;
      } catch (Exception ignored) {
      }
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    resumed = false;
    main.removeCallbacks(ticker);
    main.removeCallbacks(advance);
    main.removeCallbacks(refresh);
    if (observing) {
      getContentResolver().unregisterContentObserver(observer);
      observing = false;
    }
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    photoGeneration++;
    eventGeneration++;
    images.shutdownNow();
    calendar.shutdownNow();
  }

  private int dp(float v) {
    return (int) (getResources().getDisplayMetrics().density * v + .5f);
  }

  private void schedule() {
    main.removeCallbacks(advance);
    if (resumed && playing) main.postDelayed(advance, seconds * 1000L);
  }

  private TextView text(String value, float size) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(Color.WHITE);
    t.setFontFeatureSettings("tnum, kern");
    t.setPadding(0, dp(3), 0, dp(3));
    return t;
  }

  private LinearLayout vertical() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private TextView action(String label, Runnable run) {
    TextView b = text(label, 14);
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(14), dp(10), dp(14), dp(10));
    b.setMinHeight(dp(48));
    b.setBackground(pill(0x25FFFFFF, 18));
    b.setFocusable(true);
    b.setClickable(true);
    b.setContentDescription(label);
    b.setOnFocusChangeListener(
        (v, focus) -> v.setBackground(pill(focus ? 0x55FFFFFF : 0x25FFFFFF, 18)));
    b.setOnClickListener(v -> run.run());
    return b;
  }

  private GradientDrawable pill(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    d.setStroke(dp(1), 0x25FFFFFF);
    return d;
  }

  private void gap(LinearLayout l, int size) {
    View v = new View(this);
    l.addView(
        v,
        new LinearLayout.LayoutParams(
            l.getOrientation() == LinearLayout.VERTICAL ? 1 : dp(size),
            l.getOrientation() == LinearLayout.VERTICAL ? dp(size) : 1));
  }

  private GlassPanel panel() {
    GlassPanel p = new GlassPanel();
    p.setPadding(dp(22), dp(18), dp(22), dp(18));
    glass.add(p);
    return p;
  }

  private void build() {
    glass.clear();
    root = new FrameLayout(this);
    root.setBackgroundColor(0xFF172C3B);
    backdrop = new ImageView(this);
    backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
    root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
    View shade = new View(this);
    shade.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {0x10000000, 0x25061020, 0xA0081520}));
    root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    int pad = getResources().getConfiguration().screenWidthDp >= 700 ? 32 : 18;
    scroll.setPadding(dp(pad), dp(30), dp(pad), dp(24));
    root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout page = vertical();
    scroll.addView(page, new ScrollView.LayoutParams(-1, -1));
    LinearLayout top = row();
    TextView brand = text("LUMA  /  FRAME", 13);
    brand.setLetterSpacing(.2f);
    top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(action("Cloud", () -> startActivity(new Intent(this, CloudFrameActivity.class))));
    top.addView(action("Settings", this::settings));
    page.addView(top);
    gap(page, 24);
    LinearLayout clockBox = vertical();
    clock = text("", getResources().getConfiguration().screenWidthDp >= 700 ? 80 : 60);
    clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    clockBox.addView(clock);
    date = text("", 19);
    clockBox.addView(date);
    page.addView(clockBox);
    gap(page, 24);
    boolean wide = getResources().getConfiguration().screenWidthDp >= 840;
    LinearLayout content = wide ? row() : vertical();
    content.setGravity(Gravity.TOP);
    content.setVisibility(frameOnly ? View.GONE : View.VISIBLE);
    GlassPanel calPanel = panel();
    LinearLayout cal = vertical();
    calPanel.addView(cal);
    LinearLayout heading = row();
    heading.addView(action("‹", () -> changeMonth(-1)));
    monthLabel = text("", 19);
    monthLabel.setGravity(Gravity.CENTER);
    heading.addView(monthLabel, new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(action("›", () -> changeMonth(1)));
    cal.addView(heading);
    gap(cal, 10);
    days = vertical();
    cal.addView(days);
    gap(cal, 8);
    cal.addView(
        action(
            "Today",
            () -> {
              selected = LocalDate.now();
              month = YearMonth.from(selected);
              renderMonth();
              renderAgenda();
              loadEvents();
            }));
    GlassPanel eventPanel = panel();
    LinearLayout eventContent = vertical();
    eventPanel.addView(eventContent);
    LinearLayout eh = row();
    eh.addView(text("Your day, at a glance", 19), new LinearLayout.LayoutParams(0, -2, 1));
    eh.addView(action("+", this::addEvent));
    eventContent.addView(eh);
    gap(eventContent, 12);
    agenda = vertical();
    eventContent.addView(agenda);
    if (wide) {
      content.addView(calPanel, new LinearLayout.LayoutParams(0, -2, 1));
      gap(content, 18);
      content.addView(eventPanel, new LinearLayout.LayoutParams(0, -2, 1));
    } else {
      content.addView(calPanel, new LinearLayout.LayoutParams(-1, -2));
      gap(content, 16);
      content.addView(eventPanel, new LinearLayout.LayoutParams(-1, -2));
    }
    page.addView(content);
    View spacer = new View(this);
    page.addView(spacer, new LinearLayout.LayoutParams(1, dp(28), 1));
    GlassPanel dock = panel();
    LinearLayout controls = vertical();
    dock.addView(controls);
    LinearLayout buttons = row();
    buttons.setGravity(Gravity.CENTER);
    buttons.addView(
        action(
            "‹",
            () -> {
              showPhoto(index - 1);
              schedule();
            }));
    gap(buttons, 8);
    playback =
        action(
            playing ? "Pause" : "Play",
            () -> {
              playing = !playing;
              prefs.edit().putBoolean("playing", playing).apply();
              playback.setText(playing ? "Pause" : "Play");
              schedule();
            });
    buttons.addView(playback);
    gap(buttons, 8);
    buttons.addView(
        action(
            "›",
            () -> {
              showPhoto(index + 1);
              schedule();
            }));
    gap(buttons, 8);
    buttons.addView(action("Photos", this::pickPhotos));
    HorizontalScrollView controlScroll = new HorizontalScrollView(this);
    controlScroll.setHorizontalScrollBarEnabled(false);
    controlScroll.setFillViewport(true);
    controlScroll.addView(buttons);
    controls.addView(controlScroll);
    gap(controls, 8);
    photoCount = text("", 12);
    photoCount.setGravity(Gravity.CENTER);
    controls.addView(photoCount);
    page.addView(dock, new LinearLayout.LayoutParams(-1, -2));
    scroll.setOnScrollChangeListener(
        (v, x, y, ox, oy) -> {
          for (GlassPanel p : glass) p.invalidate();
        });
    setContentView(root);
    renderMonth();
    renderAgenda();
    updateCount();
    if (currentPhoto != null) backdrop.setImageBitmap(currentPhoto);
    main.removeCallbacks(ticker);
    if (resumed) main.post(ticker);
  }

  private void changeMonth(int delta) {
    month = month.plusMonths(delta);
    selected = month.atDay(1);
    renderMonth();
    renderAgenda();
    loadEvents();
  }

  private void renderMonth() {
    monthLabel.setText(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")));
    days.removeAllViews();
    LinearLayout week = row();
    for (String s : new String[] {"M", "T", "W", "T", "F", "S", "S"}) {
      TextView t = text(s, 12);
      t.setTextColor(0xBFFFFFFF);
      t.setGravity(Gravity.CENTER);
      week.addView(t, new LinearLayout.LayoutParams(0, dp(30), 1));
    }
    days.addView(week);
    int offset = month.atDay(1).getDayOfWeek().getValue() - 1;
    int count = ((offset + month.lengthOfMonth() + 6) / 7) * 7;
    for (int n = 0; n < count; n++) {
      if (n % 7 == 0) {
        week = row();
        days.addView(week);
      }
      int d = n - offset + 1;
      TextView cell = text(d < 1 || d > month.lengthOfMonth() ? "" : String.valueOf(d), 15);
      cell.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(43), 1);
      lp.setMargins(dp(1), dp(1), dp(1), dp(1));
      week.addView(cell, lp);
      if (d > 0 && d <= month.lengthOfMonth()) {
        LocalDate day = month.atDay(d);
        cell.setContentDescription(day.toString());
        cell.setFocusable(true);
        cell.setClickable(true);
        if (day.equals(selected)) {
          cell.setBackground(pill(0xE6FFFFFF, 15));
          cell.setTextColor(0xFF18364B);
        } else if (day.equals(LocalDate.now())) cell.setBackground(pill(0x35FFFFFF, 15));
        cell.setOnClickListener(
            v -> {
              selected = day;
              renderMonth();
              renderAgenda();
            });
      }
    }
  }

  private void renderAgenda() {
    if (agenda == null) return;
    agenda.removeAllViews();
    TextView day = text(selected.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), 13);
    day.setTextColor(0xBBFFFFFF);
    agenda.addView(day);
    gap(agenda, 8);
    if (checkSelfPermission(Manifest.permission.READ_CALENDAR)
        != PackageManager.PERMISSION_GRANTED) {
      agenda.addView(text("Make room for what matters.", 21));
      agenda.addView(
          text(
              "Connect the calendars synced to this device to see your plans alongside your"
                  + " photos.",
              14));
      gap(agenda, 12);
      agenda.addView(action("Connect calendar", this::connectCalendar));
      return;
    }
    if (!calendarError.isEmpty()) {
      agenda.addView(text(calendarError, 14));
      agenda.addView(action("Retry", this::loadEvents));
      return;
    }
    int count = 0;
    for (CalendarEvent e : events)
      if (e.overlaps(selected)) {
        LinearLayout card = vertical();
        card.setPadding(dp(13), dp(10), dp(13), dp(10));
        card.setBackground(pill(0x19FFFFFF, 16));
        TextView title = text(e.title, 16);
        card.addView(title);
        String time =
            e.allDay
                ? "All day"
                : Instant.ofEpochMilli(e.start)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("h:mm a"));
        TextView meta = text(time + (e.location.isEmpty() ? "" : "  ·  " + e.location), 12);
        meta.setTextColor(0xCCFFFFFF);
        card.addView(meta);
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(v -> openEvent(e));
        agenda.addView(card, new LinearLayout.LayoutParams(-1, -2));
        gap(agenda, 8);
        count++;
      }
    if (count == 0) {
      agenda.addView(
          text(calendarConnected ? "A little breathing room." : "Loading your calendar…", 22));
      agenda.addView(
          text(
              calendarConnected
                  ? "No events on this day. Enjoy the moment."
                  : "Your events will appear here.",
              14));
    }
  }

  private void connectCalendar() {
    if (checkSelfPermission(Manifest.permission.READ_CALENDAR)
        == PackageManager.PERMISSION_GRANTED) {
      loadEvents();
      return;
    }
    if (prefs.getBoolean("requested", false)
        && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)) {
      new AlertDialog.Builder(this)
          .setTitle("Enable calendar access")
          .setMessage("Allow Calendar in Android app permissions, then return to Luma Frame.")
          .setPositiveButton(
              "Open settings",
              (d, w) ->
                  safeStart(
                      new Intent(
                          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          Uri.parse("package:" + getPackageName()))))
          .setNegativeButton("Cancel", null)
          .show();
      return;
    }
    prefs.edit().putBoolean("requested", true).apply();
    requestPermissions(new String[] {Manifest.permission.READ_CALENDAR}, 20);
  }

  @Override
  public void onRequestPermissionsResult(int r, String[] p, int[] g) {
    super.onRequestPermissionsResult(r, p, g);
    if (r == 20) {
      loadEvents();
      renderAgenda();
    }
  }

  private void loadEvents() {
    if (checkSelfPermission(Manifest.permission.READ_CALENDAR)
        != PackageManager.PERMISSION_GRANTED) {
      events.clear();
      calendarConnected = false;
      renderAgenda();
      return;
    }
    final int generation = ++eventGeneration;
    final YearMonth requested = month;
    calendar.execute(
        () -> {
          ArrayList<CalendarEvent> result = new ArrayList<>();
          String error = "";
          long start =
              requested
                  .atDay(1)
                  .atStartOfDay(ZoneId.systemDefault())
                  .minusDays(1)
                  .toInstant()
                  .toEpochMilli();
          long end =
              requested
                  .plusMonths(1)
                  .atDay(1)
                  .atStartOfDay(ZoneId.systemDefault())
                  .plusDays(1)
                  .toInstant()
                  .toEpochMilli();
          String[] projection = {
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION
          };
          try (Cursor c =
              CalendarContract.Instances.query(getContentResolver(), projection, start, end)) {
            if (c != null)
              while (c.moveToNext())
                result.add(
                    new CalendarEvent(
                        c.getLong(0),
                        c.isNull(1) ? "Untitled event" : c.getString(1),
                        c.getLong(2),
                        c.getLong(3),
                        c.getInt(4) == 1,
                        c.isNull(5) ? "" : c.getString(5)));
          } catch (Exception e) {
            error = "Calendar unavailable. Check permission and calendar sync in Android settings.";
          }
          result.sort(Comparator.comparingLong(e -> e.start));
          final String message = error;
          main.post(
              () -> {
                if (isDestroyed() || generation != eventGeneration) return;
                events.clear();
                events.addAll(result);
                calendarError = message;
                calendarConnected = true;
                renderAgenda();
              });
        });
  }

  private void openEvent(CalendarEvent e) {
    Intent i =
        new Intent(
            Intent.ACTION_VIEW,
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id));
    i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.start);
    i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end);
    safeStart(i);
  }

  private void addEvent() {
    long start = selected.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    Intent i = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI);
    i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start);
    i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3600000);
    safeStart(i);
  }

  private void safeStart(Intent i) {
    try {
      startActivity(i);
    } catch (ActivityNotFoundException e) {
      Toast.makeText(
              this, "Install a compatible calendar or settings app to continue.", Toast.LENGTH_LONG)
          .show();
    }
  }

  private void pickPhotos() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("image/*");
    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    i.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    try {
      startActivityForResult(i, 10);
    } catch (ActivityNotFoundException e) {
      Toast.makeText(
              this, "No photo document picker is installed on this device.", Toast.LENGTH_LONG)
          .show();
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 10 || result != RESULT_OK || data == null) return;
    ArrayList<Uri> added = new ArrayList<>();
    if (data.getClipData() != null) {
      for (int n = 0; n < data.getClipData().getItemCount(); n++)
        added.add(data.getClipData().getItemAt(n).getUri());
    } else if (data.getData() != null) added.add(data.getData());
    int failed = 0;
    for (Uri uri : added)
      if (!photos.contains(uri)) {
        try {
          getContentResolver()
              .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
          photos.add(uri);
        } catch (SecurityException e) {
          failed++;
        }
      }
    savePhotos();
    showPhoto(index);
    schedule();
    if (failed > 0)
      Toast.makeText(
              this,
              "Some photos could not be saved. Try choosing files stored on this device.",
              Toast.LENGTH_LONG)
          .show();
  }

  private void savePhotos() {
    JSONArray a = new JSONArray();
    for (Uri u : photos) a.put(u.toString());
    prefs.edit().putString("photos", a.toString()).apply();
    updateCount();
  }

  private void updateCount() {
    if (photoCount != null)
      photoCount.setText(
          photos.isEmpty()
              ? "Add your photos to begin  ·  Luma Frame"
              : (index + 1) + " / " + photos.size() + " photos  ·  " + seconds + " sec");
  }

  private void showPhoto(int requested) {
    int generation = ++photoGeneration;
    index = photos.isEmpty() ? 0 : Math.floorMod(requested, photos.size());
    updateCount();
    Uri uri = photos.isEmpty() ? null : photos.get(index);
    images.execute(
        () -> {
          Bitmap bitmap = null;
          try {
            if (uri != null) {
              ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
              bitmap =
                  ImageDecoder.decodeBitmap(
                      source,
                      (decoder, info, src) -> {
                        int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                        float scale = Math.min(1f, 2048f / Math.max(w, h));
                        decoder.setTargetSize(
                            Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)));
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                      });
            } else bitmap = ambient();
          } catch (Exception | OutOfMemoryError ignored) {
          }
          final Bitmap b = bitmap;
          final Bitmap blurred =
              b == null
                  ? null
                  : Bitmap.createScaledBitmap(
                      b, Math.max(1, b.getWidth() / 48), Math.max(1, b.getHeight() / 48), true);
          main.post(
              () -> {
                if (isDestroyed() || generation != photoGeneration) return;
                if (b == null) {
                  Toast.makeText(
                          this,
                          "Photo unavailable. Removed from slideshow; original file is unchanged.",
                          Toast.LENGTH_SHORT)
                      .show();
                  photos.remove(uri);
                  savePhotos();
                  showPhoto(index);
                  return;
                }
                currentPhoto = b;
                frost = blurred;
                backdrop.animate().cancel();
                backdrop.setAlpha(0f);
                backdrop.setImageBitmap(b);
                backdrop.animate().alpha(1f).setDuration(700).start();
                for (GlassPanel p : glass) p.invalidate();
              });
        });
  }

  private Bitmap ambient() {
    Bitmap b = Bitmap.createBitmap(1440, 1000, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(b);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setShader(
        new LinearGradient(
            0,
            0,
            1440,
            1000,
            new int[] {0xFFBAD1CC, 0xFF659195, 0xFF172E49},
            null,
            Shader.TileMode.CLAMP));
    c.drawRect(0, 0, 1440, 1000, p);
    p.setShader(null);
    for (int i = 0; i < 4; i++) {
      Path path = new Path();
      path.moveTo(0, 420 + i * 120);
      path.cubicTo(390, 100 + i * 130, 650, 850 - i * 60, 1440, 230 + i * 170);
      path.lineTo(1440, 1000);
      path.lineTo(0, 1000);
      path.close();
      p.setColor(new int[] {0x55537781, 0x6650747D, 0x77405D72, 0xBB142E49}[i]);
      c.drawPath(path, p);
    }
    return b;
  }

  private void settings() {
    String[] items = {
      "Add photos",
      "Slideshow speed (" + seconds + " seconds)",
      frameOnly ? "Show calendar panels" : "Photo-only mode",
      "Manage selected photos",
      "Calendar permission / sync",
      "About Luma Frame"
    };
    new AlertDialog.Builder(this)
        .setTitle("Luma Frame")
        .setItems(
            items,
            (d, which) -> {
              switch (which) {
                case 0:
                  pickPhotos();
                  break;
                case 1:
                  new AlertDialog.Builder(this)
                      .setTitle("Time per photo")
                      .setItems(
                          new String[] {
                            "5 seconds", "15 seconds", "30 seconds", "1 minute", "5 minutes"
                          },
                          (a, b) -> {
                            seconds = new int[] {5, 15, 30, 60, 300}[b];
                            prefs.edit().putInt("seconds", seconds).apply();
                            updateCount();
                            schedule();
                          })
                      .show();
                  break;
                case 2:
                  frameOnly = !frameOnly;
                  build();
                  break;
                case 3:
                  managePhotos();
                  break;
                case 4:
                  if (checkSelfPermission(Manifest.permission.READ_CALENDAR)
                      != PackageManager.PERMISSION_GRANTED) connectCalendar();
                  else safeStart(new Intent(Settings.ACTION_SYNC_SETTINGS));
                  break;
                case 5:
                  new AlertDialog.Builder(this)
                      .setTitle("Luma Frame 1.1")
                      .setMessage(
                          "A quieter home for your photos and plans.\n\n"
                              + "Photos stay on your device. Calendar access is read-only. New"
                              + " events open in your calendar app.\n\n"
                              + "Local mode uses calendars synced to Android. Tap Cloud to pair"
                              + " with the companion website for assigned albums, calendars and"
                              + " layouts.")
                      .setPositiveButton("Done", null)
                      .show();
                  break;
              }
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void managePhotos() {
    if (photos.isEmpty()) {
      pickPhotos();
      return;
    }
    String[] names = new String[photos.size()];
    boolean[] remove = new boolean[photos.size()];
    for (int i = 0; i < names.length; i++) names[i] = "Photo " + (i + 1);
    new AlertDialog.Builder(this)
        .setTitle("Remove from slideshow")
        .setMultiChoiceItems(names, remove, (d, w, b) -> remove[w] = b)
        .setPositiveButton(
            "Remove selected",
            (d, w) -> {
              for (int i = remove.length - 1; i >= 0; i--)
                if (remove[i]) {
                  Uri u = photos.remove(i);
                  try {
                    getContentResolver()
                        .releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                  } catch (Exception ignored) {
                  }
                }
              savePhotos();
              showPhoto(0);
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  /** Samples the same photo region behind each panel, then applies a milky tint. */
  private class GlassPanel extends FrameLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    GlassPanel() {
      super(MainActivity.this);
      setWillNotDraw(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      RectF bounds = new RectF(0, 0, getWidth(), getHeight());
      Path clip = new Path();
      clip.addRoundRect(bounds, dp(28), dp(28), Path.Direction.CW);
      canvas.save();
      canvas.clipPath(clip);
      if (frost != null) {
        int[] here = new int[2], origin = new int[2];
        getLocationInWindow(here);
        root.getLocationInWindow(origin);
        canvas.save();
        canvas.translate(origin[0] - here[0], origin[1] - here[1]);
        float s =
            Math.max(
                (float) root.getWidth() / frost.getWidth(),
                (float) root.getHeight() / frost.getHeight());
        float w = frost.getWidth() * s, h = frost.getHeight() * s;
        canvas.drawBitmap(
            frost,
            null,
            new RectF(
                (root.getWidth() - w) / 2,
                (root.getHeight() - h) / 2,
                (root.getWidth() + w) / 2,
                (root.getHeight() + h) / 2),
            paint);
        canvas.restore();
      }
      canvas.drawColor(0x99435765);
      paint.setShader(
          new LinearGradient(
              0, 0, getWidth(), getHeight(), 0x30FFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP));
      canvas.drawRect(bounds, paint);
      paint.setShader(null);
      canvas.restore();
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(1));
      paint.setColor(0x55FFFFFF);
      bounds.inset(dp(.5f), dp(.5f));
      canvas.drawRoundRect(bounds, dp(28), dp(28), paint);
      paint.setStyle(Paint.Style.FILL);
    }
  }
}
