package com.openminis.app.network

import okhttp3.OkHttpClient

/**
 * Shared default [OkHttpClient] so catalog / models fetches reuse one
 * connection pool and dispatcher instead of constructing a fresh client
 * (and thread pool) per `*ModelsApi`.
 *
 * Callers that need custom timeouts or interceptors should still build
 * their own client — this is only the default no-arg `OkHttpClient()`.
 */
object SharedHttpClients {
    val default: OkHttpClient by lazy { OkHttpClient() }
}
