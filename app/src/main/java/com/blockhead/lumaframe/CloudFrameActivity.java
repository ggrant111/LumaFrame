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
    active = true;
    handler.post(tick);
    schedulePhoto();
    if (pairing != null) pollPair();
    else if (client.paired()) sync();
  }

  @Override
  protected void onPause() {
    super.onPause();
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
    t.setTextSize(size);
    t.setPadding(dp(3), dp(3), dp(3), dp(3));
    return t;
  }

  private TextView button(String s, Runnable action) {
    TextView b = label(s, 14);
    b.setPadding(dp(14), dp(12), dp(14), dp(12));
    b.setMinHeight(dp(48));
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
    g.setCornerRadius(dp(24));
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
    l.setPadding(dp(18), dp(14), dp(18), dp(14));
    l.setBackground(
        glass((float) Math.max(.15, Math.min(.95, settings().optDouble("glass_opacity", .65)))));
    return l;
  }

  private void build() {
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
    bar.setPadding(dp(16), dp(8), dp(16), dp(8));
    TextView brand = label("LUMA / FRAME", 13);
    brand.setLetterSpacing(.15f);
    bar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    bar.addView(button("Settings", this::menu));
    FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-1, dp(64), Gravity.TOP);
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
      clock = label("", getResources().getConfiguration().screenWidthDp < 600 ? 42 : 58);
      clock.setTypeface(Typeface.create("sans-serif-light", 0));
      date = label("", 16);
      clockCard.addView(clock);
      clockCard.addView(date);
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
      boolean narrow = getResources().getConfiguration().screenWidthDp < 700;
      if (narrow) {
        ScrollView sc = new ScrollView(this);
        LinearLayout stack = column();
        for (LinearLayout corner : corners.values()) stack.addView(corner);
        sc.addView(stack);
        FrameLayout.LayoutParams pos = new FrameLayout.LayoutParams(-1, -1);
        pos.setMargins(dp(16), dp(72), dp(16), dp(80));
        root.addView(sc, pos);
      } else
        for (Map.Entry<String, LinearLayout> entry : corners.entrySet()) {
          boolean left = entry.getKey().endsWith("left"), up = entry.getKey().startsWith("top");
          ScrollView sc = new ScrollView(this);
          sc.setFillViewport(false);
          sc.addView(entry.getValue());
          int h = (getResources().getDisplayMetrics().heightPixels - dp(170)) / 2;
          FrameLayout.LayoutParams pos =
              new FrameLayout.LayoutParams(
                  Math.min(dp(390), (getResources().getDisplayMetrics().widthPixels - dp(60)) / 2),
                  Math.max(dp(100), h),
                  (up ? Gravity.TOP : Gravity.BOTTOM) | (left ? Gravity.LEFT : Gravity.RIGHT));
          pos.setMargins(dp(24), dp(74), dp(24), dp(80));
          root.addView(sc, pos);
        }
    }
    status = label(statusMessage, 12);
    status.setGravity(Gravity.CENTER);
    FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM);
    bottom.setMargins(dp(16), 0, dp(16), 0);
    root.addView(status, bottom);
    setContentView(root);
    updateClock();
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
    p.bottomMargin = dp(10);
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
    heading.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
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
      week.addView(t, new LinearLayout.LayoutParams(0, dp(24), 1));
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
      week.addView(t, new LinearLayout.LayoutParams(0, dp(35), 1));
      if (day >= 1 && day <= month.lengthOfMonth()) {
        LocalDate value = month.atDay(day);
        t.setFocusable(true);
        t.setContentDescription(value.toString());
        if (selected.equals(value)) t.setBackground(glass(.9f));
        t.setOnClickListener(
            v -> {
              selected = value;
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
              renderAgenda();
            }));
  }

  private void renderAgenda() {
    if (agenda == null || snapshot == null) return;
    agenda.removeAllViews();
    agenda.addView(label(selected.format(DateTimeFormatter.ofPattern("EEE, MMM d")), 18));
    JSONArray items = snapshot.optJSONArray("events");
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

  private void setStatus(String message) {
    statusMessage = message;
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
    EditText endpoint = new EditText(this);
    endpoint.setSingleLine(true);
    endpoint.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    endpoint.setHint("https://…supabase.co/functions/v1");
    endpoint.setText(client.base());
    new AlertDialog.Builder(this)
        .setTitle("Connect your companion")
        .setMessage("Copy the Device API address from the companion website's Devices page.")
        .setView(endpoint)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Get pairing code", (d, w) -> beginPair(endpoint.getText().toString()))
        .show();
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
                          .setMessage(
                              "On your companion website, open Devices → Pair device and enter this"
                                  + " code. Expires in 10 minutes.")
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
                      "Could not begin pairing. Check the HTTPS Device API address and"
                          + " connection.");
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
