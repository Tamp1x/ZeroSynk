using System.Text.Json;
using System.Text.Json.Serialization;

namespace BufferSynk.Models;

/// <summary>Wire protocol shared with the Android app. Keep in sync with Protocol.kt.</summary>
public static class Protocol
{
    public const int Version = 1;
    public const int MaxImageBytes = 3 * 1024 * 1024; // ~4MB base64 on the wire

    public static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        PropertyNameCaseInsensitive = true,
    };

    public static WsMessage? Parse(string json)
    {
        try { return JsonSerializer.Deserialize<WsMessage>(json, Json); }
        catch { return null; }
    }

    public static string Serialize(WsMessage msg) => JsonSerializer.Serialize(msg, Json);
}

public sealed class WsMessage
{
    [JsonPropertyName("type")] public string Type { get; set; } = "";

    // hello / welcome
    [JsonPropertyName("deviceName")] public string? DeviceName { get; set; }
    [JsonPropertyName("deviceId")] public string? DeviceId { get; set; }
    [JsonPropertyName("token")] public string? Token { get; set; }
    [JsonPropertyName("pcName")] public string? PcName { get; set; }
    [JsonPropertyName("pin")] public string? Pin { get; set; }
    [JsonPropertyName("ok")] public bool? Ok { get; set; }
    [JsonPropertyName("message")] public string? Message { get; set; }

    // clipboard
    [JsonPropertyName("kind")] public string? Kind { get; set; }
    [JsonPropertyName("text")] public string? Text { get; set; }
    [JsonPropertyName("data")] public string? Data { get; set; }
    [JsonPropertyName("hash")] public string? Hash { get; set; }
    [JsonPropertyName("mime")] public string? MimeType { get; set; }

    // file offer
    [JsonPropertyName("fileId")] public string? FileId { get; set; }
    [JsonPropertyName("fileName")] public string? FileName { get; set; }
    [JsonPropertyName("fileSize")] public long? FileSize { get; set; }

    // media (PC -> phone) / mediaControl (phone -> PC)
    [JsonPropertyName("state")] public string? State { get; set; }
    [JsonPropertyName("title")] public string? Title { get; set; }
    [JsonPropertyName("artist")] public string? Artist { get; set; }
    [JsonPropertyName("app")] public string? App { get; set; }
    [JsonPropertyName("action")] public string? Action { get; set; }

    // notification mirroring
    [JsonPropertyName("key")] public string? Key { get; set; }
    [JsonPropertyName("pkg")] public string? Pkg { get; set; }
    [JsonPropertyName("appName")] public string? AppName { get; set; }
    [JsonPropertyName("replyable")] public bool? Replyable { get; set; }
    [JsonPropertyName("origin")] public string? Origin { get; set; } // phone | pc

    [JsonPropertyName("ts")] public long? Ts { get; set; }

    public static WsMessage Hello(string deviceName, string deviceId, string? token) => new()
    {
        Type = "hello",
        DeviceName = deviceName,
        DeviceId = deviceId,
        Token = token,
        Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
    };

    public static WsMessage ClipboardText(string text, string hash) => new()
    {
        Type = "clipboard",
        Kind = "text",
        Text = text,
        Hash = hash,
        Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
    };

    public static WsMessage ClipboardImage(string base64Png, string hash) => new()
    {
        Type = "clipboard",
        Kind = "image",
        MimeType = "image/png",
        Data = base64Png,
        Hash = hash,
        Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
    };

    public static WsMessage FileOffer(string fileId, string fileName, long fileSize) => new()
    {
        Type = "fileOffer",
        FileId = fileId,
        FileName = fileName,
        FileSize = fileSize,
        Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
    };

    public static WsMessage Ping() => new()
    {
        Type = "ping",
        Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
    };

    public static WsMessage Media(string state, string title, string artist, string app) => new()
    {
        Type = "media",
        State = state,
        Title = title,
        Artist = artist,
        App = app,
        Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
    };

    public static WsMessage Notif(string key, string pkg, string appName, string title, string text, bool replyable, string origin) => new()
    {
        Type = "notif",
        Key = key,
        Pkg = pkg,
        AppName = appName,
        Title = title,
        Text = text,
        Replyable = replyable,
        Origin = origin,
        Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
    };

    public static WsMessage NotifReply(string key, string text) => new()
    {
        Type = "notifReply",
        Key = key,
        Text = text,
        Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
    };

    public static WsMessage NotifRemoved(string key) => new()
    {
        Type = "notifRemoved",
        Key = key,
        Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
    };

    public static WsMessage Status(string state) => new()
    {
        Type = "status",
        State = state,
        Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
    };

    public static WsMessage Bye() => new()
    {
        Type = "bye",
        Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
    };
}
