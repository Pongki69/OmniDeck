using System.IO;

namespace RemoteGameStreamer.Host;

public enum LogLevel { Info, Warning, Error }

public sealed record LogEntry(DateTime Time, LogLevel Level, string Message);

/// <summary>Application log: shown live in the window and saved to a daily file.</summary>
public static class AppLog
{
    private static readonly object Gate = new();
    private static readonly List<LogEntry> Recent = new();
    private const int MaxRecent = 500;
    private static StreamWriter? _file;

    public static event Action<LogEntry>? Written;

    public static string Folder { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OmniDeck", "logs");

    public static void Initialize()
    {
        try
        {
            Directory.CreateDirectory(Folder);
            string path = Path.Combine(Folder, $"omnideck-{DateTime.Now:yyyy-MM-dd}.log");
            var stream = new FileStream(path, FileMode.Append, FileAccess.Write, FileShare.ReadWrite);
            lock (Gate) _file = new StreamWriter(stream) { AutoFlush = true };
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Warn($"Log file unavailable: {ex.Message}");
        }
    }

    public static void Info(string message) => Write(LogLevel.Info, message);
    public static void Warn(string message) => Write(LogLevel.Warning, message);
    public static void Error(string message) => Write(LogLevel.Error, message);

    public static IReadOnlyList<LogEntry> Snapshot()
    {
        lock (Gate) return Recent.ToArray();
    }

    private static void Write(LogLevel level, string message)
    {
        var entry = new LogEntry(DateTime.Now, level, message);
        lock (Gate)
        {
            Recent.Add(entry);
            if (Recent.Count > MaxRecent) Recent.RemoveAt(0);
            try
            {
                _file?.WriteLine($"{entry.Time:yyyy-MM-dd HH:mm:ss} {level,-7} {message}");
            }
            catch (IOException)
            {
                // A full disk must never stop the stream.
            }
        }
        Written?.Invoke(entry);
    }
}
