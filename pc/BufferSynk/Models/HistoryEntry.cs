namespace BufferSynk.Models;

public sealed class HistoryEntry
{
    public DateTime Time { get; set; } = DateTime.Now;
    /// <summary>toPhone | fromPhone</summary>
    public string Direction { get; set; } = "toPhone";
    /// <summary>text | image | file</summary>
    public string Kind { get; set; } = "text";
    public string Preview { get; set; } = "";
    public long Size { get; set; }
    /// <summary>sent | received | failed</summary>
    public string Status { get; set; } = "sent";
    public string? FilePath { get; set; }
}

public sealed class HistoryStore
{
    public List<HistoryEntry> Entries { get; set; } = new();
}
