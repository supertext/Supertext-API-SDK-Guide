namespace Supertext.Sdk;

/// <summary>
/// Version-neutral facts about this SDK. Everything that talks to the API lives in a versioned
/// namespace (<c>Supertext.Sdk.V1</c>; a later API version gets <c>Supertext.Sdk.V2</c> next to it).
/// </summary>
public static class SdkInfo
{
    /// <summary>This SDK's version (plugins pass it to Supertext Plugin Health as <c>sdkVersion</c>). Same number as <c>&lt;Version&gt;</c> in Supertext.Sdk.csproj and CHANGELOG.md.</summary>
    public const string Version = "0.6.0";
}
