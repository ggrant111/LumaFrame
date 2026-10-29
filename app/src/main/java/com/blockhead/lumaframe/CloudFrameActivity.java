package com.blockhead.lumaframe;

import android.app.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Paired frame mode; local-only MainActivity remains available independently. */
public class CloudFrameActivity extends Activity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private final ExecutorService decoder = Executors.newSingleThreadExecutor();
  private CloudClient client;
  private JSONObject snapshot, pairing;
  private FrameLayout root;
  private ImageView background;
  private TextView status, clock, date;
  private View chromeBar;
  private boolean chromeHidden, stickyStatus, upcoming;
  private static final long CHROME_HIDE_MS = 8000;
  private final Runnable hideChrome =
      () -> {
        if (snapshot == null || stickyStatus) return;
        chromeHidden = true;
        if (chromeBar != null) chromeBar.animate().alpha(0f).setDuration(600).start();
        if (status != null) status.animate().alpha(0f).setDuration(600).start();
      };

  private void wake() {
    handler.removeCallbacks(hideChrome);
    chromeHidden = false;
    if (chromeBar != null) chromeBar.animate().alpha(1f).setDuration(250).start();
    if (status != null) status.animate().alpha(1f).setDuration(250).start();
    if (!stickyStatus) handler.postDelayed(hideChrome, CHROME_HIDE_MS);
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent ev) {
    if (chromeHidden) {
      // First touch only wakes the controls; swallow the whole gesture.
      if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) wake();
      return true;
    }
    if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) wake();
    return super.dispatchTouchEvent(ev);
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent ev) {
    if (chromeHidden) {
      if (ev.getAction() == KeyEvent.ACTION_UP) wake();
      return true;
    }
    if (ev.getAction() == KeyEvent.ACTION_DOWN) wake();
    return super.dispatchKeyEvent(ev);
  }
  private LinearLayout agenda, monthGrid;
  private Bitmap photo;
  private int index, generation;
  private boolean active, busy, paused, closing;
  private long pairDeadline;
  private int nextSync = 60;
  private String layoutSignature = "", photoSignature = "";
  private String statusMessage = "Ready to pair";
  private LocalDate selected;
  private LocalDate previousToday;
  private AlertDialog pairingDialog;
  private final Runnable syncTimer = () -> sync();
  private final Runnable pairTimer = () -> pollPair();
  private final Runnable photoTimer =
      new Runnable() {
        public void run() {
          if (active && !paused) {
            showPhoto(index + 1);
            schedulePhoto();
          }
        }
      };
  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (!active) return;
          updateClock();
          if (snapshot != null
              && (System.currentTimeMillis() < snapshot.optLong("cached_at")
                  || System.currentTimeMillis() - snapshot.optLong("cached_at")
                      > CloudClient.MAX_OFFLINE_MS)) {
            client.cached();
            snapshot = null;
            photo = null;
            generation++;
            build();
          }
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    client = new CloudClient(this);
    snapshot = client.cached();
    getWindow()
        .addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SECURE);
    getWindow().getDecorView().setSystemUiVisibility(5894);
    build();
    if (snapshot != null) showPhoto(0);
  }

  @Override
  protected void onResume() {
    super.onResume();
    upcoming = !getSharedPreferences("cloud_ui", MODE_PRIVATE).getBoolean("agenda_day_mode", false);
    active = true;
    handler.post(tick);
    schedulePhoto();
    if (pairing != null) pollPair();
    else if (client.paired()) sync();
  }

  @Override
  protected void onPause() {
    super.onPause();
    handler.removeCallbacks(hideChrome);
    active = false;
    handler.removeCallbacks(tick);
    handler.removeCallbacks(syncTimer);
    handler.removeCallbacks(pairTimer);
    handler.removeCallbacks(photoTimer);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    generation++;
    network.shutdownNow();
    decoder.shutdownNow();
  }

  // Layout profile: text/padding scale and card width as a share of the screen.
  private float ts = 1f;
  private float cardPct = .34f;
  private int edge, cellH, rowGap;

  private void computeProfile() {
    android.content.res.Configuration c = getResources().getConfiguration();
    boolean tv =
        (c.uiMode & android.content.res.Configuration.UI_MODE_TYPE_MASK)
                == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
            || c.screenWidthDp >= 960;
    if (tv) {
      ts = .75f;
      cardPct = .26f;
    } else if (c.screenWidthDp >= 600) {
      ts = .9f;
      cardPct = .34f;
    } else {
      ts = 1f;
      cardPct = 1f;
    }
    android.util.DisplayMetrics m = getResources().getDisplayMetrics();
    edge = Math.max(dp(16), (int) (m.widthPixels * .04f));
    rowGap = dp(10 * ts);
  }

  private int dp(float n) {
    return (int) (getResources().getDisplayMetrics().density * n + .5f);
  }

  private JSONObject settings() {
    return snapshot == null ? new JSONObject() : snapshot.optJSONObject("settings");
  }

  private ZoneId zone() {
    try {
      return ZoneId.of(settings().optString("timezone", ZoneId.systemDefault().getId()));
    } catch (Exception e) {
      return ZoneId.systemDefault();
    }
  }

  private TextView label(String s, int size) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextColor(Color.WHITE);
    t.setTextSize(size * ts);
    t.setPadding(dp(3 * ts), dp(3 * ts), dp(3 * ts), dp(3 * ts));
    return t;
  }

  private TextView button(String s, Runnable action) {
    TextView b = label(s, 14);
    b.setPadding(dp(14 * ts), dp(10 * ts), dp(14 * ts), dp(10 * ts));
    b.setMinHeight(dp(44 * ts));
    b.setMinWidth(dp(44 * ts));
    b.setGravity(Gravity.CENTER);
    b.setBackground(glass(.7f));
    b.setFocusable(true);
    b.setOnClickListener(v -> action.run());
    b.setOnFocusChangeListener((v, f) -> v.setAlpha(f ? .65f : 1f));
    return b;
  }

  private GradientDrawable glass(float alpha) {
    GradientDrawable g =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[] {
              ((int) (alpha * 255) << 24) | 0x526C7C, ((int) (alpha * 255) << 24) | 0x213B4C
            });
    g.setCornerRadius(dp(22 * ts));
    g.setStroke(dp(1), 0x66FFFFFF);
    return g;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout card() {
    LinearLayout l = column();
    l.setPadding(dp(18 * ts), dp(14 * ts), dp(18 * ts), dp(14 * ts));
    l.setBackground(
        glass((float) Math.max(.15, Math.min(.95, settings().optDouble("glass_opacity", .65)))));
    return l;
  }

  private void build() {
    computeProfile();
    root = new FrameLayout(this);
    root.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xFF759B9E, 0xFF112A43}));
    background = new ImageView(this);
    background.setScaleType(
        "contain".equals(settings().optString("photo_fit"))
            ? ImageView.ScaleType.FIT_CENTER
            : ImageView.ScaleType.CENTER_CROP);
    root.addView(background, new FrameLayout.LayoutParams(-1, -1));
    if (photo != null) background.setImageBitmap(photo);
    View shade = new View(this);
    shade.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x22000000, 0x44000000}));
    root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(edge, dp(8 * ts), edge, dp(8 * ts));
    TextView brand = label("LUMA / FRAME", 13);
    brand.setLetterSpacing(.15f);
    bar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    bar.addView(button("Settings", this::menu));
    chromeBar = bar;
    FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-1, dp(64 * ts) + dp(8), Gravity.TOP);
    root.addView(bar, top);
    if (snapshot == null) {
      LinearLayout welcome = card();
      welcome.addView(
          label(client.paired() ? "Your frame is connected" : "Your memories. Together.", 30));
      welcome.addView(
          label(
              client.paired()
                  ? "Waiting for photos, calendars and display settings from your companion"
                      + " account."
                  : "Pair this display with Luma Frame Companion to bring your albums and calendars"
                      + " home.",
              17));
      welcome.addView(
          button(
              client.paired() ? "Sync now" : "Pair device",
              () -> {
                if (client.paired()) sync();
                else beginPairDialog();
              }));
      FrameLayout.LayoutParams center =
          new FrameLayout.LayoutParams(
              Math.min(dp(520), getResources().getDisplayMetrics().widthPixels - dp(36)),
              -2,
              Gravity.CENTER);
      root.addView(welcome, center);
    } else {
      if (selected == null) selected = LocalDate.now(zone());
      LinearLayout clockCard = card();
      clock = label("", 58);
      clock.setTypeface(Typeface.create("sans-serif-light", 0));
      date = label("", 16);
      date.setAlpha(.85f);
      clockCard.addView(clock);
      clockCard.addView(date);
      android.util.DisplayMetrics m = getResources().getDisplayMetrics();
      int topInset = dp(64 * ts) + dp(8), bottomInset = dp(48 * ts) + dp(8);
      int cornerMaxH = (m.heightPixels - topInset - bottomInset - rowGap) / 2;
      // Fit the month grid into a corner: heading + weekday row + 6 weeks + Today button.
      cellH =
          Math.max(
              dp(22), Math.min(dp(35 * ts), (cornerMaxH - dp(150 * ts)) / 6));
      LinearLayout calendarCard = card();
      monthGrid = column();
      calendarCard.addView(monthGrid);
      renderMonth();
      LinearLayout agendaCard = card();
      agenda = column();
      agendaCard.addView(agenda);
      renderAgenda();
      Map<String, LinearLayout> corners = new LinkedHashMap<>();
      for (String key : new String[] {"top-left", "top-right", "bottom-left", "bottom-right"})
        corners.put(key, column());
      addToCorner(corners, clockCard, "clock");
      addToCorner(corners, calendarCard, "calendar");
      addToCorner(corners, agendaCard, "agenda");
      boolean narrow = getResources().getConfiguration().screenWidthDp < 600;
      if (narrow) {
        ScrollView sc = new ScrollView(this);
        LinearLayout stack = column();
        for (LinearLayout corner : corners.values()) stack.addView(corner);
        sc.addView(stack);
        FrameLayout.LayoutParams pos = new FrameLayout.LayoutParams(-1, -1);
        pos.setMargins(edge, topInset, edge, bottomInset);
        root.addView(sc, pos);
      } else {
        int width = Math.max(dp(260 * ts), (int) (m.widthPixels * cardPct));
        width = Math.min(width, (m.widthPixels - edge * 3) / 2);
        final int maxH = Math.max(dp(100), cornerMaxH);
        for (Map.Entry<String, LinearLayout> entry : corners.entrySet()) {
          if (entry.getValue().getChildCount() == 0) continue;
          boolean left = entry.getKey().endsWith("left"), up = entry.getKey().startsWith("top");
          // Wraps its content but never grows past half the usable height.
          ScrollView sc =
              new ScrollView(this) {
                @Override
                protected void onMeasure(int w, int h) {
                  super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
                }
              };
          sc.setVerticalScrollBarEnabled(false);
          sc.addView(entry.getValue());
          FrameLayout.LayoutParams pos =
              new FrameLayout.LayoutParams(
                  width,
                  -2,
                  (up ? Gravity.TOP : Gravity.BOTTOM) | (left ? Gravity.LEFT : Gravity.RIGHT));
          pos.setMargins(edge, topInset, edge, bottomInset);
          root.addView(sc, pos);
        }
      }
    }
    status = label(statusMessage, 12);
    status.setGravity(Gravity.CENTER);
    FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, dp(48 * ts), Gravity.BOTTOM);
    bottom.setMargins(edge, 0, edge, dp(8));
    root.addView(status, bottom);
    setContentView(root);
    updateClock();
    chromeHidden = false;
    wake();
  }

  private void addToCorner(Map<String, LinearLayout> corners, View card, String kind) {
    if (!settings().optBoolean(kind + "_visible", true)) return;
    String fallback =
        kind.equals("clock")
            ? "top-left"
            : kind.equals("calendar") ? "bottom-left" : "bottom-right";
    LinearLayout box = corners.get(settings().optString(kind + "_position", fallback));
    if (box == null) box = corners.get(fallback);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    if (box.getChildCount() > 0) p.topMargin = rowGap;
    box.addView(card, p);
  }

  private void updateClock() {
    if (snapshot == null || clock == null) return;
    ZonedDateTime now = ZonedDateTime.now(zone());
    LocalDate today = now.toLocalDate();
    if (previousToday != null && !today.equals(previousToday) && previousToday.equals(selected)) {
      selected = today;
      renderMonth();
      renderAgenda();
    }
    previousToday = today;
    boolean twentyFour = android.text.format.DateFormat.is24HourFormat(this);
    clock.setText(now.format(DateTimeFormatter.ofPattern(twentyFour ? "HH:mm" : "h:mm a")));
    date.setText(now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")));
  }

  private void renderMonth() {
    if (monthGrid == null) return;
    monthGrid.removeAllViews();
    YearMonth month = YearMonth.from(selected);
    LinearLayout heading = new LinearLayout(this);
    heading.addView(
        button(
            "‹",
            () -> {
              selected = selected.minusMonths(1).withDayOfMonth(1);
              renderMonth();
              renderAgenda();
            }));
    TextView title = label(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), 16);
    title.setGravity(Gravity.CENTER);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(
        button(
            "›",
            () -> {
              selected = selected.plusMonths(1).withDayOfMonth(1);
              renderMonth();
              renderAgenda();
            }));
    monthGrid.addView(heading);
    LinearLayout week = new LinearLayout(this);
    for (String d : new String[] {"M", "T", "W", "T", "F", "S", "S"}) {
      TextView t = label(d, 11);
      t.setGravity(Gravity.CENTER);
      week.addView(t, new LinearLayout.LayoutParams(0, dp(22 * ts), 1));
    }
    monthGrid.addView(week);
    int offset = month.atDay(1).getDayOfWeek().getValue() - 1;
    int count = ((offset + month.lengthOfMonth() + 6) / 7) * 7;
    for (int n = 0; n < count; n++) {
      if (n % 7 == 0) {
        week = new LinearLayout(this);
        monthGrid.addView(week);
      }
      int day = n - offset + 1;
      TextView t = label(day < 1 || day > month.lengthOfMonth() ? "" : String.valueOf(day), 13);
      t.setGravity(Gravity.CENTER);
      week.addView(t, new LinearLayout.LayoutParams(0, cellH > 0 ? cellH : dp(35), 1));
      if (day >= 1 && day <= month.lengthOfMonth()) {
        LocalDate value = month.atDay(day);
        t.setFocusable(true);
        t.setContentDescription(value.toString());
        if (selected.equals(value)) t.setBackground(glass(.9f));
        t.setOnClickListener(
            v -> {
              selected = value;
              upcoming = false;
              getSharedPreferences("cloud_ui", MODE_PRIVATE).edit().putBoolean("agenda_day_mode", true).apply();
              handler.removeCallbacks(backToUpcoming);
              handler.postDelayed(backToUpcoming, 60000);
              renderMonth();
              renderAgenda();
            });
      }
    }
    monthGrid.addView(
        button(
            "Today",
            () -> {
              selected = LocalDate.now(zone());
              renderMonth();
              setUpcoming(true);
            }));
  }

  private void renderAgenda() {
    if (agenda == null || snapshot == null) return;
    agenda.removeAllViews();
    LinearLayout head = new LinearLayout(this);
    head.setGravity(Gravity.CENTER_VERTICAL);
    head.addView(
        label(upcoming ? "Up next" : selected.format(DateTimeFormatter.ofPattern("EEE, MMM d")), 18),
        new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(
        button(
            upcoming ? "Show day" : "Show next 5",
            () -> {
              setUpcoming(!upcoming);
            }));
    agenda.addView(head);
    JSONArray items = snapshot.optJSONArray("events");
    if (upcoming) {
      renderUpcoming(items);
      return;
    }
    int count = 0;
    if (items != null)
      for (int i = 0; i < items.length(); i++) {
        JSONObject e = items.optJSONObject(i);
        if (e == null) continue;
        boolean all = e.optBoolean("all_day");
        ZoneId z = all ? ZoneOffset.UTC : zone();
        long start = e.optLong("start_ms"), end = e.optLong("end_ms");
        long a = selected.atStartOfDay(z).toInstant().toEpochMilli(),
            b = selected.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli();
        if (start >= b || Math.max(end, start + 1) <= a) continue;
        agenda.addView(label(e.optString("title", "Busy"), 16));
        String when =
            all
                ? "All day"
                : Instant.ofEpochMilli(start)
                    .atZone(zone())
                    .format(DateTimeFormatter.ofPattern("h:mm a"));
        String location = e.optString("location");
        TextView meta = label(when + (location.isEmpty() ? "" : " · " + location), 12);
        meta.setAlpha(.75f);
        agenda.addView(meta);
        count++;
      }
    if (count == 0) agenda.addView(label("No synced events on this day.", 14));
  }

  private final Runnable backToUpcoming = () -> setUpcoming(true);

  private void setUpcoming(boolean value) {
    upcoming = value;
    handler.removeCallbacks(backToUpcoming);
    if (!value) handler.postDelayed(backToUpcoming, 60000);
    getSharedPreferences("cloud_ui", MODE_PRIVATE).edit().putBoolean("agenda_day_mode", !value).apply();
    renderAgenda();
  }

  private void renderUpcoming(JSONArray items) {
    long now = System.currentTimeMillis();
    List<JSONObject> list = new ArrayList<>();
    if (items != null)
      for (int i = 0; i < items.length(); i++) {
        JSONObject e = items.optJSONObject(i);
        if (e == null) continue;
        long start = e.optLong("start_ms"), end = Math.max(e.optLong("end_ms"), start + 1);
        if (end > now) list.add(e);
      }
    Collections.sort(list, (x, y) -> Long.compare(x.optLong("start_ms"), y.optLong("start_ms")));
    LocalDate today = LocalDate.now(zone());
    int shown = 0;
    for (JSONObject e : list) {
      if (shown == 5) break;
      boolean all = e.optBoolean("all_day");
      long start = e.optLong("start_ms");
      // All-day events use UTC-midnight date boundaries.
      ZonedDateTime at = Instant.ofEpochMilli(start).atZone(all ? ZoneOffset.UTC : zone());
      LocalDate day = at.toLocalDate();
      if (all && day.isBefore(today)) day = today; // multi-day event already underway
      String dayLabel =
          day.equals(today)
              ? "Today"
              : day.equals(today.plusDays(1))
                  ? "Tomorrow"
                  : day.format(DateTimeFormatter.ofPattern("EEE, MMM d"));
      agenda.addView(label(e.optString("title", "Busy"), 16));
      String location = e.optString("location");
      TextView meta =
          label(
              dayLabel
                  + " · "
                  + (all ? "All day" : at.format(DateTimeFormatter.ofPattern("h:mm a")))
                  + (location.isEmpty() ? "" : " · " + location),
              12);
      meta.setAlpha(.75f);
      agenda.addView(meta);
      shown++;
    }
    if (shown == 0) agenda.addView(label("No upcoming synced events.", 14));
  }

  private void setStatus(String message) {
    statusMessage = message;
    String m = message.toLowerCase(Locale.ROOT);
    stickyStatus =
        m.contains("revoked") || m.contains("unavailable") || m.contains("expired")
            || m.contains("interrupted") || m.contains("failed") || m.contains("error");
    wake();
    if (status != null) status.setText(message);
  }

  private void sync() {
    if (!active || busy || closing || !client.paired()) return;
    busy = true;
    handler.removeCallbacks(syncTimer);
    setStatus("Syncing with your companion…");
    network.execute(
        () -> {
          try {
            JSONObject data = client.sync();
            handler.post(
                () -> {
                  busy = false;
                  if (isDestroyed() || closing) return;
                  snapshot = data;
                  nextSync = Math.max(15, Math.min(3600, data.optInt("sync_after_seconds", 60)));
                  String signature = settings().toString();
                  String images = data.optJSONArray("photos").toString();
                  boolean rebuild = !signature.equals(layoutSignature),
                      reload = !images.equals(photoSignature);
                  layoutSignature = signature;
                  photoSignature = images;
                  String suffix =
                      data.optInt("skipped_photos") > 0
                          ? " · " + data.optInt("skipped_photos") + " photos pending / cache limit"
                          : "";
                  setStatus(
                      "Synced "
                          + ZonedDateTime.now(zone()).format(DateTimeFormatter.ofPattern("h:mm a"))
                          + suffix);
                  if (rebuild || clock == null) build();
                  else {
                    renderMonth();
                    renderAgenda();
                  }
                  if (reload || photo == null) showPhoto(0);
                  schedulePhoto();
                  if (active) handler.postDelayed(syncTimer, nextSync * 1000L);
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  busy = false;
                  if (isDestroyed() || closing) return;
                  if (!client.paired()) {
                    snapshot = null;
                    photo = null;
                    generation++;
                    build();
                    setStatus("Device access revoked. Pair again to reconnect.");
                    return;
                  }
                  if (client.cached() == null) {
                    snapshot = null;
                    photo = null;
                    generation++;
                    build();
                  }
                  nextSync =
                      e instanceof CloudClient.HttpError
                          ? ((CloudClient.HttpError) e).retrySeconds
                          : Math.min(3600, Math.max(60, nextSync * 2));
                  setStatus(
                      "Sync unavailable · retrying in "
                          + nextSync
                          + " sec"
                          + (snapshot == null ? "" : " · showing saved content"));
                  if (active) handler.postDelayed(syncTimer, nextSync * 1000L);
                });
          }
        });
  }

  private void schedulePhoto() {
    handler.removeCallbacks(photoTimer);
    if (active && !paused && snapshot != null)
      handler.postDelayed(
          photoTimer,
          Math.max(5, Math.min(300, settings().optInt("interval_seconds", 15))) * 1000L);
  }

  private void showPhoto(int desired) {
    JSONArray list = snapshot == null ? null : snapshot.optJSONArray("photos");
    int request = ++generation;
    if (list == null || list.length() == 0) {
      photo = null;
      background.setImageDrawable(null);
      return;
    }
    index = Math.floorMod(desired, list.length());
    String path = list.optJSONObject(index).optString("path");
    decoder.execute(
        () -> {
          Bitmap loaded = null;
          try {
            loaded =
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(new File(path)),
                    (d, info, s) -> {
                      float scale =
                          Math.min(
                              1f,
                              2048f
                                  / Math.max(
                                      info.getSize().getWidth(), info.getSize().getHeight()));
                      d.setTargetSize(
                          Math.max(1, (int) (info.getSize().getWidth() * scale)),
                          Math.max(1, (int) (info.getSize().getHeight() * scale)));
                      d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
          } catch (Exception | OutOfMemoryError ignored) {
          }
          final Bitmap b = loaded;
          handler.post(
              () -> {
                if (isDestroyed() || request != generation) return;
                photo = b;
                background.setAlpha(0f);
                background.setImageBitmap(b);
                background.animate().alpha(1f).setDuration(700).start();
              });
        });
  }

  private void beginPairDialog() {
    if (busy) return;
    beginPair(client.base());
  }

  private void advancedAddressDialog() {
    EditText endpoint = new EditText(this);
    endpoint.setSingleLine(true);
    endpoint.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    endpoint.setHint(CloudClient.DEFAULT_BASE);
    endpoint.setText(client.base());
    new AlertDialog.Builder(this)
        .setTitle("Companion address")
        .setMessage("Only change this if you run your own companion server.")
        .setView(endpoint)
        .setNegativeButton("Cancel", null)
        .setNeutralButton("Use default", (d, w) -> beginPair(CloudClient.DEFAULT_BASE))
        .setPositiveButton("Get pairing code", (d, w) -> beginPair(endpoint.getText().toString()))
        .show();
  }

  /** QR code for the phone sign-in page, drawn with ZXing core (no camera/scanner needed here). */
  private Bitmap qrBitmap(String text, int size) {
    try {
      com.google.zxing.common.BitMatrix m =
          new com.google.zxing.qrcode.QRCodeWriter()
              .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
      Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
      for (int y = 0; y < size; y++)
        for (int x = 0; x < size; x++) bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
      return bmp;
    } catch (Exception e) {
      return null;
    }
  }

  private View pairingView(JSONObject p) {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setGravity(Gravity.CENTER_HORIZONTAL);
    int pad = (int) (20 * getResources().getDisplayMetrics().density);
    box.setPadding(pad, pad / 2, pad, 0);
    try {
      Bitmap qr = qrBitmap(CloudClient.pairUrl(client.base(), p.optString("user_code")), 480);
      if (qr != null) {
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(qr);
        int s = (int) (220 * getResources().getDisplayMetrics().density);
        box.addView(iv, new LinearLayout.LayoutParams(s, s));
      }
    } catch (Exception ignored) {
    }
    TextView t = new TextView(this);
    t.setGravity(Gravity.CENTER);
    t.setPadding(0, pad / 2, 0, 0);
    t.setText(
        "Scan with your phone and sign in to connect this frame.\nOr open Devices → Pair device"
            + " and enter the code. Expires in 10 minutes.");
    box.addView(t);
    return box;
  }

  private void signInDialog(JSONObject p, String householdId) {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (20 * getResources().getDisplayMetrics().density);
    box.setPadding(pad, 0, pad, 0);
    EditText email = new EditText(this);
    email.setSingleLine(true);
    email.setHint("Email");
    email.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
    EditText password = new EditText(this);
    password.setSingleLine(true);
    password.setHint("Password");
    password.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    box.addView(email);
    box.addView(password);
    new AlertDialog.Builder(this)
        .setTitle("Sign in to Luma Frame")
        .setMessage("Household owners only. Your password is used once and isn't saved.")
        .setView(box)
        .setNegativeButton(
            "Back",
            (d, w) -> {
              if (pairing != null && pairingDialog != null) pairingDialog.show();
            })
        .setPositiveButton(
            "Sign in",
            (d, w) ->
                loginPair(p, email.getText().toString(), password.getText().toString(), householdId))
        .show();
  }

  private void loginPair(JSONObject p, String email, String password, String householdId) {
    busy = true;
    setStatus("Signing in…");
    network.execute(
        () -> {
          try {
            JSONObject r = client.loginPair(p, email, password, householdId);
            handler.post(
                () -> {
                  busy = false;
                  if (isDestroyed()) return;
                  String st = r.optString("status");
                  if ("paired".equals(st)) {
                    pairing = null;
                    handler.removeCallbacks(pairTimer);
                    if (pairingDialog != null) pairingDialog.dismiss();
                    setStatus("Connected. Loading your frame…");
                    sync();
                  } else if ("choose_household".equals(st)) {
                    JSONArray hs = r.optJSONArray("households");
                    int n = hs == null ? 0 : hs.length();
                    String[] names = new String[n];
                    String[] ids = new String[n];
                    for (int i = 0; i < n; i++) {
                      names[i] = hs.optJSONObject(i).optString("name");
                      ids[i] = hs.optJSONObject(i).optString("id");
                    }
                    new AlertDialog.Builder(this)
                        .setTitle("Choose household")
                        .setItems(names, (d, w) -> loginPair(p, email, password, ids[w]))
                        .show();
                  } else {
                    setStatus("Pairing code expired. Pair again.");
                    if (pairingDialog != null) pairingDialog.dismiss();
                  }
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  busy = false;
                  int code = e instanceof CloudClient.HttpError ? ((CloudClient.HttpError) e).code : 0;
                  setStatus(
                      code == 401
                          ? "Wrong email or password."
                          : code == 403
                              ? "Only a household owner can connect a frame."
                              : code == 429
                                  ? "Too many attempts. Try again in a few minutes."
                                  : "Could not sign in. Check the connection.");
                  if (pairing != null && pairingDialog != null) pairingDialog.show();
                });
          }
        });
  }

  private void beginPair(String endpoint) {
    busy = true;
    setStatus("Requesting a pairing code…");
    network.execute(
        () -> {
          try {
            JSONObject p = client.begin(endpoint);
            handler.post(
                () -> {
                  busy = false;
                  if (isDestroyed()) return;
                  pairing = p;
                  long remaining = 600000;
                  try {
                    remaining =
                        Math.min(
                            600000,
                            Instant.parse(p.getString("expires_at")).toEpochMilli()
                                - System.currentTimeMillis());
                  } catch (Exception ignored) {
                  }
                  pairDeadline = SystemClock.elapsedRealtime() + Math.max(0, remaining);
                  pairingDialog =
                      new AlertDialog.Builder(this)
                          .setTitle(p.optString("user_code"))
                          .setView(pairingView(p))
                          .setPositiveButton("Sign in on this frame", (d, w) -> signInDialog(p, null))
                          .setNeutralButton("Advanced", (d, w) -> advancedAddressDialog())
                          .setNegativeButton(
                              "Cancel",
                              (d, w) -> {
                                pairing = null;
                                handler.removeCallbacks(pairTimer);
                              })
                          .create();
                  pairingDialog.setOnCancelListener(
                      d -> {
                        pairing = null;
                        handler.removeCallbacks(pairTimer);
                      });
                  pairingDialog.show();
                  pollPair();
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  busy = false;
                  setStatus(
                      "Could not begin pairing. Check the internet connection, or set the"
                          + " companion address under Advanced.");
                });
          }
        });
  }

  private void pollPair() {
    if (!active || busy || pairing == null) return;
    if (SystemClock.elapsedRealtime() > pairDeadline) {
      pairing = null;
      setStatus("Pairing code expired. Request a new code.");
      if (pairingDialog != null) pairingDialog.dismiss();
      return;
    }
    busy = true;
    JSONObject request = pairing;
    network.execute(
        () -> {
          try {
            JSONObject response = client.poll(request);
            handler.post(
                () -> {
                  busy = false;
                  if (isDestroyed()) return;
                  String result = response.optString("status");
                  if ("paired".equals(result)) {
                    pairing = null;
                    if (pairingDialog != null) pairingDialog.dismiss();
                    setStatus("Paired. Loading your frame…");
                    sync();
                  } else if ("expired".equals(result)) {
                    pairing = null;
                    if (pairingDialog != null) pairingDialog.dismiss();
                    setStatus("Code expired. Pair again.");
                  } else if (pairing != null && active)
                    handler.postDelayed(
                        pairTimer, Math.max(5, request.optInt("poll_interval_seconds", 5)) * 1000L);
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  busy = false;
                  setStatus("Pairing connection interrupted. Retrying…");
                  if (pairing != null && active)
                    handler.postDelayed(
                        pairTimer,
                        e instanceof CloudClient.HttpError
                            ? ((CloudClient.HttpError) e).retrySeconds * 1000L
                            : 10000);
                });
          }
        });
  }

  private void menu() {
    if (!client.paired()) {
      new AlertDialog.Builder(this)
          .setTitle("Luma Companion")
          .setItems(
              new String[] {"Pair device", "Local frame mode"},
              (d, w) -> {
                if (w == 0) beginPairDialog();
                else finish();
              })
          .show();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Luma Companion")
        .setItems(
            new String[] {
              "Sync now",
              paused ? "Play slideshow" : "Pause slideshow",
              "Previous photo",
              "Next photo",
              "Unpair this device",
              "Local frame mode"
            },
            (d, w) -> {
              switch (w) {
                case 0:
                  sync();
                  break;
                case 1:
                  paused = !paused;
                  schedulePhoto();
                  break;
                case 2:
                  showPhoto(index - 1);
                  schedulePhoto();
                  break;
                case 3:
                  showPhoto(index + 1);
                  schedulePhoto();
                  break;
                case 4:
                  new AlertDialog.Builder(this)
                      .setTitle("Unpair this frame?")
                      .setMessage(
                          "Downloaded cloud photos and calendars will be removed from this device."
                              + " Your account originals stay intact.")
                      .setNegativeButton("Cancel", null)
                      .setPositiveButton(
                          "Unpair",
                          (a, b) -> {
                            closing = true;
                            generation++;
                            handler.removeCallbacks(syncTimer);
                            handler.removeCallbacks(photoTimer);
                            snapshot = null;
                            photo = null;
                            background.setImageDrawable(null);
                            build();
                            setStatus("Unpairing…");
                            network.execute(
                                () -> {
                                  client.unpair();
                                  handler.post(
                                      () -> {
                                        closing = false;
                                        layoutSignature = "";
                                        photoSignature = "";
                                        build();
                                        setStatus(
                                            "Unpaired. If you were offline, also revoke this frame"
                                                + " from the website.");
                                      });
                                });
                          })
                      .show();
                  break;
                case 5:
                  finish();
                  break;
              }
            })
        .show();
  }
}
