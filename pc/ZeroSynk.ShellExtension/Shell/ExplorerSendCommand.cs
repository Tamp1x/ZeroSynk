using System.Diagnostics;
using System.Runtime.InteropServices;

namespace ZeroSynk.Shell;

[ComVisible(true)]
[Guid(ClassId)]
[ClassInterface(ClassInterfaceType.None)]
public sealed class ExplorerSendCommand : IExplorerCommand
{
    public const string ClassId = "2B9B4D83-167B-4F74-AC1E-3C9454AD6C2A";
    private const int S_OK = 0;
    private const int E_NOTIMPL = unchecked((int)0x80004001);
    private const uint SIGDN_FILESYSPATH = 0x80058000;

    public int GetTitle(IntPtr items, out IntPtr title) => Alloc("Отправить в ZeroSynk", out title);

    public int GetIcon(IntPtr items, out IntPtr icon)
    {
        var exe = GetExecutablePath();
        return Alloc($"{exe},0", out icon);
    }

    public int GetToolTip(IntPtr items, out IntPtr tooltip) => Alloc("Send the selected item to your paired phone", out tooltip);
    public int GetCanonicalName(out Guid commandName) { commandName = new Guid(ClassId); return S_OK; }
    public int GetState(IntPtr items, bool slow, out uint state) { state = 0; return S_OK; }
    public int GetFlags(out uint flags) { flags = 0; return S_OK; }
    public int EnumSubCommands(out IntPtr commands) { commands = IntPtr.Zero; return E_NOTIMPL; }

    public int Invoke(IntPtr items, IntPtr bindContext)
    {
        try
        {
            if (items == IntPtr.Zero) return E_NOTIMPL;
            var array = (IShellItemArray)Marshal.GetObjectForIUnknown(items);
            array.GetItemAt(0, out var item);
            item.GetDisplayName(SIGDN_FILESYSPATH, out var pathPointer);
            try
            {
                var path = Marshal.PtrToStringUni(pathPointer);
                if (string.IsNullOrWhiteSpace(path)) return E_NOTIMPL;
                Process.Start(new ProcessStartInfo(GetExecutablePath())
                {
                    UseShellExecute = true,
                    ArgumentList = { "--send-interactive", path },
                });
                return S_OK;
            }
            finally { Marshal.FreeCoTaskMem(pathPointer); }
        }
        catch { return E_NOTIMPL; }
    }

    private static string GetExecutablePath() => Path.Combine(
        Path.GetDirectoryName(typeof(ExplorerSendCommand).Assembly.Location)!, "ZeroSynk.exe");
    private static int Alloc(string value, out IntPtr output) { output = Marshal.StringToCoTaskMemUni(value); return S_OK; }
}

[ComVisible(true)]
[Guid("A08CE4D0-FA25-44AB-B57C-C7B1C323E0B9")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IExplorerCommand
{
    [PreserveSig] int GetTitle(IntPtr items, out IntPtr title);
    [PreserveSig] int GetIcon(IntPtr items, out IntPtr icon);
    [PreserveSig] int GetToolTip(IntPtr items, out IntPtr tooltip);
    [PreserveSig] int GetCanonicalName(out Guid commandName);
    [PreserveSig] int GetState(IntPtr items, [MarshalAs(UnmanagedType.Bool)] bool slow, out uint state);
    [PreserveSig] int Invoke(IntPtr items, IntPtr bindContext);
    [PreserveSig] int GetFlags(out uint flags);
    [PreserveSig] int EnumSubCommands(out IntPtr commands);
}

[ComVisible(true)]
[Guid("B63EA76D-1F85-456F-A19C-48159EFA858B")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IShellItemArray
{
    [PreserveSig] int BindToHandler(IntPtr bindContext, ref Guid handlerId, ref Guid interfaceId, out IntPtr result);
    [PreserveSig] int GetPropertyStore(uint flags, ref Guid interfaceId, out IntPtr propertyStore);
    [PreserveSig] int GetPropertyDescriptionList(IntPtr keyType, ref Guid interfaceId, out IntPtr descriptions);
    [PreserveSig] int GetAttributes(uint flags, uint attributes, out uint result);
    [PreserveSig] int GetCount(out uint count);
    [PreserveSig] int GetItemAt(uint index, [MarshalAs(UnmanagedType.Interface)] out IShellItem item);
}

[ComVisible(true)]
[Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IShellItem
{
    [PreserveSig] int BindToHandler(IntPtr bindContext, ref Guid handlerId, ref Guid interfaceId, out IntPtr result);
    [PreserveSig] int GetParent(out IntPtr parent);
    [PreserveSig] int GetDisplayName(uint nameType, out IntPtr name);
    [PreserveSig] int GetAttributes(uint mask, out uint attributes);
    [PreserveSig] int Compare(IntPtr other, uint hint, out int order);
}



