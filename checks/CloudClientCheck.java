package com.blockhead.lumaframe;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class CloudClientCheck {
  static void reject(String value) throws Exception {
    try {
      CloudClient.validateBase(value);
      throw new AssertionError("Accepted invalid API address");
    } catch (IOException | java.net.URISyntaxException expected) {
    }
  }

  public static void main(String[] args) throws Exception {
    if (!CloudClient.validateBase(" https://example.supabase.co/functions/v1/ ")
        .equals("https://example.supabase.co/functions/v1"))
      throw new AssertionError("Normalization");
    if (!CloudClient.pairUrl("https://example.com/functions/v1", "ABCD-2345")
        .equals("https://example.com/pair?code=ABCD2345"))
      throw new AssertionError("Pair URL");
    reject("http://example.com/functions/v1");
    reject("https://user:password@example.com/functions/v1");
    reject("https://example.com/functions/v1?token=secret");
    reject("https://example.com/functions/v1#fragment");
    reject("https://example.com/other");
    byte[] data = "small payload".getBytes(StandardCharsets.UTF_8);
    if (CloudClient.readBounded(new ByteArrayInputStream(data), data.length).length != data.length)
      throw new AssertionError("Read");
    try {
      CloudClient.readBounded(new ByteArrayInputStream(data), 3);
      throw new AssertionError("Exceeded byte limit");
    } catch (IOException expected) {
    }
    System.out.println("PASS: 8 cloud endpoint and bounded-response checks");
  }
}
