package com.blockhead.lumaframe;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

/** Opaque device credentials, private bounded cache, no account credentials on the frame. */
final class CloudClient {
  static final long MAX_OFFLINE_MS = 24 * 60 * 60 * 1000L;
  private static final int MAX_JSON = 4 * 1024 * 1024, MAX_IMAGE = 20 * 1024 * 1024;
  private final SharedPreferences prefs;
  private final File directory;
  private final String keyAlias = "luma-device-token-v1";

  CloudClient(Context context) {
    prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE);
    directory = new File(context.getFilesDir(), "cloud-frame");
    directory.mkdirs();
  }

  boolean paired() {
    return prefs.contains("token");
  }

  String base() {
    return prefs.getString("base", "");
  }

  static String validateBase(String raw) throws Exception {
    URI uri = new URI(raw.trim());
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null)
      throw new IOException("Use the HTTPS device API address shown in the companion app.");
    String path = uri.getPath().replaceAll("/+$", "");
    if (!"/functions/v1".equals(path))
      throw new IOException("The device API address must end with /functions/v1.");
    return new URI("https", null, uri.getHost(), uri.getPort(), path, null, null).toString();
  }

  JSONObject begin(String endpoint) throws Exception {
    if (paired()) throw new IOException("Unpair the existing device before pairing again.");
    String valid = validateBase(endpoint);
    JSONObject data =
        post(
            valid,
            "frame-pair-begin",
            new JSONObject()
                .put("device_name", android.os.Build.MODEL)
                .put("platform", "android")
                .put("app_version", "1.1.0"),
            null);
    data.getString("pairing_id");
    data.getString("pairing_secret");
    data.getString("user_code");
    prefs.edit().putString("base", valid).apply();
    return data;
  }

  JSONObject poll(JSONObject pairing) throws Exception {
    JSONObject response =
        post(
            base(),
            "frame-pair-poll",
            new JSONObject()
                .put("pairing_id", pairing.getString("pairing_id"))
                .put("pairing_secret", pairing.getString("pairing_secret")),
            null);
    if ("paired".equals(response.optString("status"))) {
      String token = response.getString("device_token");
      if (token.length() < 32) throw new IOException("Invalid pairing credential");
      prefs
          .edit()
          .putString("token", encrypt(token))
          .putString("device_id", response.getString("device_id"))
          .commit();
    }
    return response;
  }

  private SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(keyAlias)) return (SecretKey) store.getKey(keyAlias, null);
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                keyAlias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
    return generator.generateKey();
  }

  private String encrypt(String value) throws Exception {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key());
    return Base64.encodeToString(c.getIV(), Base64.NO_WRAP)
        + ":"
        + Base64.encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
  }

  private String token() throws Exception {
    String[] pieces = prefs.getString("token", "").split(":");
    if (pieces.length != 2) throw new IOException("Pair this frame again.");
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)));
    return new String(c.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }

  static class HttpError extends IOException {
    final int code, retrySeconds;

    HttpError(int code, int retry) {
      super("Service response " + code);
      this.code = code;
      this.retrySeconds = retry;
    }
  }

  private JSONObject post(String base, String function, JSONObject body, String credential)
      throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(base + "/" + function).openConnection();
    c.setInstanceFollowRedirects(false);
    c.setConnectTimeout(15000);
    c.setReadTimeout(25000);
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    c.setRequestProperty("Content-Type", "application/json");
    c.setRequestProperty("Accept", "application/json");
    if (credential != null) c.setRequestProperty("Authorization", "Bearer " + credential);
    try {
      try (OutputStream out = c.getOutputStream()) {
        out.write(body.toString().getBytes(StandardCharsets.UTF_8));
      }
      int status = c.getResponseCode();
      if (status < 200 || status >= 300) {
        int retry = 60;
        try {
          retry = Integer.parseInt(c.getHeaderField("Retry-After"));
        } catch (Exception ignored) {
        }
        throw new HttpError(status, Math.max(5, Math.min(3600, retry)));
      }
      try (InputStream in = c.getInputStream()) {
        return new JSONObject(new String(readBounded(in, MAX_JSON), StandardCharsets.UTF_8));
      }
    } finally {
      c.disconnect();
    }
  }

  static byte[] readBounded(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[16384];
    int n, total = 0;
    while ((n = in.read(buffer)) != -1) {
      total += n;
      if (total > max) throw new IOException("Response exceeds size limit");
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  private String fileName(JSONObject photo) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256")
            .digest(
                (photo.getString("id") + ":" + photo.optString("version"))
                    .getBytes(StandardCharsets.UTF_8));
    StringBuilder b = new StringBuilder();
    for (byte v : digest) b.append(String.format(Locale.ROOT, "%02x", v));
    return b + ".image";
  }

  JSONObject sync() throws Exception {
    JSONObject snapshot;
    try {
      snapshot = post(base(), "frame-sync", new JSONObject().put("app_version", "1.1.0"), token());
    } catch (HttpError e) {
      if (e.code == 401 || e.code == 403) clear();
      throw e;
    }
    if (snapshot.optInt("schema_version") != 1)
      throw new IOException("Unsupported device API version.");
    JSONArray source = snapshot.getJSONArray("photos");
    snapshot.getJSONArray("events");
    snapshot.getJSONObject("settings");
    // Remove withdrawn assets immediately, even if some newly assigned photos fail to download.
    Set<String> allowed = new HashSet<>();
    for (int i = 0; i < source.length(); i++) allowed.add(fileName(source.getJSONObject(i)));
    File[] existing = directory.listFiles();
    if (existing != null)
      for (File f : existing)
        if (f.getName().endsWith(".image") && !allowed.contains(f.getName())) f.delete();
    JSONArray local = new JSONArray();
    long total = 0;
    int skipped = 0;
    for (int i = 0; i < source.length(); i++) {
      JSONObject photo = source.getJSONObject(i);
      File dest = new File(directory, fileName(photo));
      try {
        if (!dest.isFile()) {
          URI uri = new URI(photo.getString("url"));
          if (!"https".equalsIgnoreCase(uri.getScheme())
              || uri.getHost() == null
              || uri.getUserInfo() != null) throw new IOException("Invalid photo URL");
          HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
          c.setInstanceFollowRedirects(false);
          c.setConnectTimeout(10000);
          c.setReadTimeout(20000);
          byte[] bytes;
          try {
            if (c.getResponseCode() != 200) throw new IOException("Photo unavailable");
            try (InputStream in = c.getInputStream()) {
              bytes = readBounded(in, MAX_IMAGE);
            }
          } finally {
            c.disconnect();
          }
          BitmapFactory.Options options = new BitmapFactory.Options();
          options.inJustDecodeBounds = true;
          BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
          if (options.outWidth <= 0 || options.outHeight <= 0)
            throw new IOException("Unsupported image");
          if (total + bytes.length > 250L * 1024 * 1024) {
            skipped++;
            continue;
          }
          Files.write(dest.toPath(), bytes);
        }
        if (total + dest.length() > 250L * 1024 * 1024) {
          dest.delete();
          skipped++;
          continue;
        }
        total += dest.length();
        local.put(
            new JSONObject()
                .put("path", dest.getAbsolutePath())
                .put("id", photo.getString("id"))
                .put("version", photo.optString("version")));
      } catch (Exception e) {
        skipped++;
      }
    }
    // Signed asset URLs are never persisted. Only sandbox file paths survive a restart.
    snapshot
        .put("photos", local)
        .put("cached_at", System.currentTimeMillis())
        .put("skipped_photos", skipped);
    File temporary = new File(directory, "snapshot.tmp");
    Files.write(temporary.toPath(), snapshot.toString().getBytes(StandardCharsets.UTF_8));
    Files.move(
        temporary.toPath(),
        new File(directory, "snapshot.json").toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    return snapshot;
  }

  JSONObject cached() {
    try {
      File f = new File(directory, "snapshot.json");
      if (!f.exists()) return null;
      JSONObject data =
          new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
      long age = System.currentTimeMillis() - data.getLong("cached_at");
      if (age < 0 || age > MAX_OFFLINE_MS) {
        clearCache();
        return null;
      }
      return data;
    } catch (Exception e) {
      clearCache();
      return null;
    }
  }

  void unpair() {
    try {
      if (paired()) post(base(), "frame-unpair", new JSONObject(), token());
    } catch (Exception ignored) {
    } finally {
      clear();
    }
  }

  void clear() {
    prefs.edit().remove("token").remove("device_id").commit();
    clearCache();
  }

  private void clearCache() {
    File[] files = directory.listFiles();
    if (files != null) for (File f : files) f.delete();
  }
}
