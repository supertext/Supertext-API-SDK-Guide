package com.supertext.sdk;

/**
 * Facts about this SDK that don't depend on an API version. Everything that talks to the API is
 * versioned in its own package ({@code com.supertext.sdk.v1}); a later API version gets
 * {@code com.supertext.sdk.v2} next to it.
 */
public final class SdkInfo {

    /** This SDK's version (plugins pass it to Supertext Plugin Health as {@code sdkVersion}; the same number as {@code pom.xml}). */
    public static final String VERSION = "0.6.0";

    private SdkInfo() {
    }
}
