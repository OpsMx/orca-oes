/*
 * Copyright 2024 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.webhook.http;

import java.util.HashMap;
import java.util.Map;
import org.springframework.lang.Nullable;

/**
 * OP-22069: As part of CVE fixes, spring-web has been upgraded. In the new package, HttpMethod is
 * converted to a class from Enum. Due to this change, ObjectMapper cannot
 * serialize @org.springframework.http.HttpMethod, so we created this Enum to support serialization.
 */
public enum HttpMethod {
  GET,
  HEAD,
  POST,
  PUT,
  PATCH,
  DELETE,
  OPTIONS,
  TRACE;

  private static final Map<String, HttpMethod> mappings = new HashMap<>(16);

  static {
    for (HttpMethod httpMethod : values()) {
      mappings.put(httpMethod.name(), httpMethod);
    }
  }

  /**
   * Resolve the given method value to an {@code HttpMethod}.
   *
   * @param method the method value as a String
   * @return the corresponding {@code HttpMethod}, or {@code null} if not found
   * @since 4.2.4
   */
  @Nullable
  public static HttpMethod resolve(@Nullable String method) {
    return (method != null ? mappings.get(method) : null);
  }

  /**
   * Determine whether this {@code HttpMethod} matches the given method value.
   *
   * @param method the HTTP method as a String
   * @return {@code true} if it matches, {@code false} otherwise
   * @since 4.2.4
   */
  public boolean matches(String method) {
    return name().equals(method);
  }
}
