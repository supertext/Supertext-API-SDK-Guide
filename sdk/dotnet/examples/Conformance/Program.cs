// Conformance program for stand-in/conformance.mjs: reads one request as JSON on stdin,
// translates it with the v1 client's TranslateTextsAsync (language codes passed through as given)
// and prints the result (or the error) as JSON.
//
//   node stand-in/conformance.mjs -- dotnet sdk/dotnet/examples/Conformance/bin/Release/net10.0/Conformance.dll
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using Supertext.Sdk.V1;

// UTF-8 both ways, also on Windows consoles.
Console.InputEncoding = new System.Text.UTF8Encoding(false);
Console.OutputEncoding = new System.Text.UTF8Encoding(false);

var request = JsonNode.Parse(Console.In.ReadToEnd())!.AsObject();
var segments = request["segments"]!.AsArray()
    .Select(s => new KeyValuePair<string, Segment>(
        (string)s!["key"]!,
        new Segment((string)s["text"]!, (bool?)s["html"] ?? false)))
    .ToList();
var politeness = (string?)request["politeness"] switch
{
    "more" => Politeness.More,
    "less" => Politeness.Less,
    _ => Politeness.Default,
};

var client = new SupertextClient(
    Environment.GetEnvironmentVariable("SUPERTEXT_API_KEY"),
    new SupertextClientOptions
    {
        BaseUrl = Environment.GetEnvironmentVariable("SUPERTEXT_API_URL") ?? SupertextClient.LiveBaseUrl,
        PollInterval = TimeSpan.FromMilliseconds(100),
    });

JsonObject output;
try
{
    var translations = await client.TranslateTextsAsync(
        segments, (string)request["targetLang"]!, (string?)request["sourceLang"], politeness);
    var result = new JsonObject();
    foreach (var (key, text) in translations)
    {
        result[key] = text;
    }
    output = new JsonObject { ["translations"] = result };
}
catch (SupertextException e)
{
    output = new JsonObject { ["error"] = new JsonObject { ["code"] = e.Code, ["status"] = e.Status } };
}

Console.Out.Write(output.ToJsonString(new JsonSerializerOptions { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping }));
Console.Out.WriteLine();
