/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider


import kotlinx.serialization.Serializable


@Serializable
internal data class ProviderCatalogDocument(
    val schemaVersion: Int,
    val providers: List<ProviderCatalogEntry>,
)

@Serializable
data class ProviderCatalogEntry(
    val id: String,
    val displayName: String,
    val targetPackages: List<String>,
    val available: Boolean,
    val versionName: String? = null,
    val versionCode: Int? = null,
    val assetUrl: String? = null,
    val sha256: String? = null,
    // 旧 App 开启 ignoreUnknownKeys 会忽略这两个字段；顶层字段仍指向「最低仍支持
    // 的主程序也能用的最新版」，保证旧 App 不会看到装不上的更新。
    val releases: List<ProviderCatalogRelease> = emptyList(),
    val revokedVersionCodes: List<Int> = emptyList(),
) {
    /** releases 格式下只要有任何发行版即视为已发布。 */
    val published: Boolean get() = available || releases.isNotEmpty()
}

