using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace ZeroSynk.Services;

public static class NetworkService
{
    public sealed record NetAddr(string Ip, string Label, int Priority);

    /// <summary>All IPv4s of this machine, sorted: Tailscale first, then LAN, then others.</summary>
    public static List<NetAddr> GetIPv4s()
    {
        var list = new List<NetAddr>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    var ip = ua.Address.ToString();
                    if (ip.StartsWith("127.")) continue;

                    int prio;
                    string label;
                    if (ip.StartsWith("100.64.") || ip.StartsWith("100.") && IsTailscaleRange(ip))
                    {
                        prio = 0; label = "Tailscale";
                    }
                    else if (ip.StartsWith("169.254."))
                    {
                        prio = 3; label = "APIPA";
                    }
                    else
                    {
                        prio = 1; label = "LAN";
                    }
                    list.Add(new NetAddr(ip, label, prio));
                }
            }
        }
        catch { }

        return list
            .DistinctBy(a => a.Ip)
            .OrderBy(a => a.Priority)
            .ThenBy(a => a.Ip)
            .ToList();
    }

    /// <summary>Primary IP to show by default: Tailscale if present, else first LAN.</summary>
    public static string GetPrimaryIp()
    {
        var addrs = GetIPv4s();
        return addrs.Count > 0 ? addrs[0].Ip : "127.0.0.1";
    }

    public static bool IsPortFree(int port)
    {
        var props = IPGlobalProperties.GetIPGlobalProperties();
        return !props.GetActiveTcpListeners().Any(ep => ep.Port == port);
    }

    // Tailscale CGNAT range 100.64.0.0/10
    private static bool IsTailscaleRange(string ip)
    {
        var parts = ip.Split('.');
        if (parts.Length != 4 || !int.TryParse(parts[1], out var second)) return false;
        return parts[0] == "100" && second >= 64 && second <= 127;
    }

    public static IEnumerable<string> GetAllIpsForQr(int port)
        => GetIPv4s().Select(a => a.Ip);
}
