using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows;

namespace RemoteGameStreamer.Host;

public sealed record AppRelease(Version Version, string ZipUrl, string SignatureUrl);

/// <summary>
/// Finds, verifies and installs new versions from GitHub Releases. Nothing runs unless the
/// download is signed by the release key, whose public half is built in here.
/// </summary>
public static class AppUpdater
{
    private const string LatestReleaseApi = "https://api.github.com/repos/Pongki69/OmniDeck/releases/latest";
    private const string DownloadPrefix = "https://github.com/Pongki69/OmniDeck/releases/download/";
    private const string ZipName = "OmniDeck-windows.zip";
    private const string SignatureName = ZipName + ".sig";
    private const string ExeName = "OmniDeck.exe";
    private const string UpdatePublicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEKfUFYuMvsEi9aL/7frBw6OfKGoWljezDVHf7+Ssah4eiq76fmYZSLHWjGn5FWOxKQ9z5Elh6L8r6kfUk9Ynyqw==";

    private static readonly HttpClient Http = CreateClient();
    private static readonly string StagingRoot = Path.Combine(Path.GetDirectoryName(AppLog.Folder)!, "update");

    private static HttpClient CreateClient()
    {
        var client = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("OmniDeck-Updater");
        return client;
    }

    public static Version Current => Normalize(Assembly.GetEntryAssembly()?.GetName().Version ?? new Version(0, 0, 0));

    private static Version Normalize(Version v) => new(v.Major, v.Minor, Math.Max(v.Build, 0));

    public static Version? ParseTag(string? tag) =>
        tag is { Length: > 1 } && tag[0] == 'v' && Version.TryParse(tag[1..], out var v) && v.Build >= 0 && v.Revision < 0 ? v : null;

    /// <summary>Returns the release if it is newer than <paramref name="current"/> and has both signed files.</summary>
    public static AppRelease? ParseRelease(string json, Version current)
    {
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        if (root.TryGetProperty("draft", out var d) && d.GetBoolean()) return null;
        if (root.TryGetProperty("prerelease", out var p) && p.GetBoolean()) return null;
        if (!root.TryGetProperty("tag_name", out var tag) || ParseTag(tag.GetString()) is not { } version) return null;
        if (version <= Normalize(current) || !root.TryGetProperty("assets", out var assets)) return null;

        string? AssetUrl(string name) => assets.EnumerateArray()
            .Where(a => a.GetProperty("name").GetString() == name)
            .Select(a => a.GetProperty("browser_download_url").GetString())
            .FirstOrDefault(u => u != null && u.StartsWith(DownloadPrefix, StringComparison.Ordinal));

        return AssetUrl(ZipName) is { } zip && AssetUrl(SignatureName) is { } signature
            ? new AppRelease(version, zip, signature)
            : null;
    }

    /// <summary>Null means no newer version exists. Throws if GitHub can't be reached.</summary>
    public static async Task<AppRelease?> CheckAsync()
    {
        CleanUpOldStaging();
        using var response = await Http.GetAsync(LatestReleaseApi);
        if (response.StatusCode == System.Net.HttpStatusCode.NotFound) return null; // nothing published yet
        response.EnsureSuccessStatusCode();
        return ParseRelease(await response.Content.ReadAsStringAsync(), Current);
    }

    public static bool VerifySignature(Stream content, byte[] signature, byte[] publicKey)
    {
        using var ecdsa = ECDsa.Create();
        ecdsa.ImportSubjectPublicKeyInfo(publicKey, out _);
        try
        {
            return ecdsa.VerifyHash(SHA256.HashData(content), signature, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (CryptographicException)
        {
            return false;
        }
    }

    /// <summary>Downloads and verifies the release, then unpacks it. Returns the new program's path.</summary>
    public static async Task<string> DownloadAndStageAsync(AppRelease release, IProgress<int> progress)
    {
        if (Directory.Exists(StagingRoot)) Directory.Delete(StagingRoot, recursive: true);
        Directory.CreateDirectory(StagingRoot);

        string zipPath = Path.Combine(StagingRoot, ZipName);
        using (var response = await Http.GetAsync(release.ZipUrl, HttpCompletionOption.ResponseHeadersRead))
        {
            response.EnsureSuccessStatusCode();
            long? total = response.Content.Headers.ContentLength;
            await using var input = await response.Content.ReadAsStreamAsync();
            await using var output = File.Create(zipPath);

            var buffer = new byte[256 * 1024];
            long done = 0;
            int read;
            while ((read = await input.ReadAsync(buffer)) > 0)
            {
                await output.WriteAsync(buffer.AsMemory(0, read));
                done += read;
                if (total > 0) progress.Report((int)(done * 100 / total.Value));
            }
        }

        byte[] signature = await Http.GetByteArrayAsync(release.SignatureUrl);
        await using (var zip = File.OpenRead(zipPath))
        {
            if (!VerifySignature(zip, signature, Convert.FromBase64String(UpdatePublicKey)))
                throw new InvalidDataException("The download's signature is invalid, so it was not installed.");
        }

        string appDir = Path.Combine(StagingRoot, "app");
        ZipFile.ExtractToDirectory(zipPath, appDir);
        File.Delete(zipPath);

        // The signature covers the program, so this stops an old signed build being offered as new.
        string exe = Path.Combine(appDir, ExeName);
        var file = FileVersionInfo.GetVersionInfo(exe);
        if (new Version(file.FileMajorPart, file.FileMinorPart, file.FileBuildPart) != release.Version)
            throw new InvalidDataException("The download's version doesn't match the release, so it was not installed.");
        return exe;
    }

    /// <summary>Starts the unpacked copy, which waits for this process to exit and then replaces it.</summary>
    public static void LaunchFinisher(string stagedExe)
    {
        var info = new ProcessStartInfo(stagedExe) { UseShellExecute = false, WorkingDirectory = Path.GetDirectoryName(stagedExe)! };
        info.ArgumentList.Add("--finish-update");
        info.ArgumentList.Add(AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar));
        info.ArgumentList.Add(Environment.ProcessId.ToString());
        Process.Start(info);
    }

    /// <summary>Runs in the unpacked copy. Always reopens OmniDeck, even if the update failed.</summary>
    public static void FinishUpdate(string targetDir, int processId)
    {
        try
        {
            using var old = Process.GetProcessById(processId);
            old.WaitForExit(30_000);
        }
        catch (ArgumentException)
        {
            // Already gone.
        }

        string? problem = null;
        for (int attempt = 0; attempt < 8; attempt++)
        {
            try
            {
                CopyApplication(AppContext.BaseDirectory, targetDir);
                problem = null;
                break;
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                problem = ex.Message;
                Thread.Sleep(1000);
            }
        }

        if (problem != null)
            MessageBox.Show($"OmniDeck couldn't finish updating:\n{problem}\n\nDownload the latest version from the project's GitHub Releases page.", "OmniDeck", MessageBoxButton.OK, MessageBoxImage.Warning);

        Process.Start(new ProcessStartInfo(Path.Combine(targetDir, ExeName)) { UseShellExecute = true, WorkingDirectory = targetDir });
    }

    /// <summary>The program file goes last, so an interrupted copy can't leave a new program with old libraries.</summary>
    public static void CopyApplication(string sourceDir, string targetDir)
    {
        var files = Directory.GetFiles(sourceDir, "*", SearchOption.AllDirectories)
            .OrderBy(f => Path.GetFileName(f).Equals(ExeName, StringComparison.OrdinalIgnoreCase));

        foreach (string file in files)
        {
            string target = Path.Combine(targetDir, Path.GetRelativePath(sourceDir, file));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Copy(file, target, overwrite: true);
        }
    }

    private static void CleanUpOldStaging()
    {
        try
        {
            if (Directory.Exists(StagingRoot) && !AppContext.BaseDirectory.StartsWith(StagingRoot, StringComparison.OrdinalIgnoreCase))
                Directory.Delete(StagingRoot, recursive: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Tried again next launch.
        }
    }
}
