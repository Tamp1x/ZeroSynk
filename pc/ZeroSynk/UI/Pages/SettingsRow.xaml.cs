using System.Windows;
using System.Windows.Controls;

namespace ZeroSynk.UI.Pages;

public partial class SettingsRow : System.Windows.Controls.UserControl
{
    public static readonly DependencyProperty HeaderProperty =
        DependencyProperty.Register(nameof(Header), typeof(string), typeof(SettingsRow),
            new PropertyMetadata("", (d, e) => ((SettingsRow)d).HeaderText.Text = (string)e.NewValue ?? ""));

    public static readonly DependencyProperty SubProperty =
        DependencyProperty.Register(nameof(Sub), typeof(string), typeof(SettingsRow),
            new PropertyMetadata("", (d, e) => ((SettingsRow)d).SubText.Text = (string)e.NewValue ?? ""));

    public static readonly DependencyProperty IsOnProperty =
        DependencyProperty.Register(nameof(IsOn), typeof(bool), typeof(SettingsRow),
            new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.BindsTwoWayByDefault,
                (d, e) =>
                {
                    var row = (SettingsRow)d;
                    if (row.Switch.IsChecked != (bool)e.NewValue)
                        row.Switch.IsChecked = (bool)e.NewValue;
                }));

    public string Header
    {
        get => (string)GetValue(HeaderProperty);
        set => SetValue(HeaderProperty, value);
    }

    public string Sub
    {
        get => (string)GetValue(SubProperty);
        set => SetValue(SubProperty, value);
    }

    public bool IsOn
    {
        get => (bool)GetValue(IsOnProperty);
        set => SetValue(IsOnProperty, value);
    }

    public event RoutedEventHandler? Clicked;

    public SettingsRow()
    {
        InitializeComponent();
        HeaderText.Text = Header;
        SubText.Text = Sub;
    }

    private void OnChanged(object sender, RoutedEventArgs e)
    {
        if (Switch.IsChecked is bool v && v != IsOn)
            SetCurrentValue(IsOnProperty, v);
        Clicked?.Invoke(this, e);
    }
}
