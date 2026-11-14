package com.supertext.sdk

/**
 * This SDK's version (plugins pass it to Supertext Plugin Health as `sdkVersion`; the same number as `pom.xml`).
 *
 * Everything that talks to the API is versioned in its own package (`com.supertext.sdk.v1`); a later API version
 * gets `com.supertext.sdk.v2` next to it.
 */
public const val SDK_VERSION: String = "0.6.0"
