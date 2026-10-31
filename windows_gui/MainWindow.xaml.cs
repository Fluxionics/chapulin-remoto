using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ChapulinRemoto.Net;
using Color = System.Windows.Media.Color;
using Clipboard = System.Windows.Clipboard;

namespace ChapulinRemoto
{
    public partial class MainWindow : Window
    {
        private readonly ChpHostServer _hostServer = new();
        private readonly ChpClient _phoneClient = new();

        public MainWindow()
        {
            InitializeComponent();
            Loaded += MainWindow_Loaded;
            Closing += MainWindow_Closing;
            // PreviewKeyDown captura antes que los controles internos; TextInput
            // trae los caracteres reales (letras, numeros, tildes) para escribir.
            PreviewKeyDown += MainWindow_PreviewKeyDown;
            TextInput += MainWindow_TextInput;
        }

        private void MainWindow_Loaded(object sender, RoutedEventArgs e)
        {
            LoadLogo();

            string localIp = GetLocalIpAddress();
            TxtLocalIp.Text = localIp;

            _hostServer.OnStatusChanged += () => Dispatcher.Invoke(UpdateHostUi);
            _hostServer.Start(47474);
            TxtHostPin.Text = _hostServer.Pin;

            _phoneClient.OnFrameReceived += (jpeg, w, h) => Dispatcher.Invoke(() => UpdatePhoneFrame(jpeg, w, h));
            _phoneClient.OnDisconnected += (reason) => Dispatcher.Invoke(() => HandlePhoneDisconnected(reason));
        }

        private void MainWindow_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
        {
            _phoneClient.Disconnect();
            _hostServer.Stop();
        }

        private void LoadLogo()
        {
            try
            {
                string iconPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "logo.ico");
                if (!File.Exists(iconPath))
                {
                    iconPath = Path.GetFullPath(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"..\..\..\..\windows\resources\logo.ico"));
                }
                if (File.Exists(iconPath))
                {
                    AppLogoImage.Source = new BitmapImage(new Uri(iconPath));
                }
            }
            catch { }
        }

        private string GetLocalIpAddress()
        {
            try
            {
                var host = Dns.GetHostEntry(Dns.GetHostName());
                foreach (var ip in host.AddressList)
                {
                    if (ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))
                    {
                        return ip.ToString();
                    }
                }
            }
            catch { }
            return "127.0.0.1";
        }

        private void UpdateHostUi()
        {
            if (_hostServer.IsClientConnected)
            {
                HostStatusDot.Fill = new SolidColorBrush(Color.FromRgb(0x4C, 0xAF, 0x50));
                TxtHostStatus.Text = $"Celular conectado: {_hostServer.ConnectedClientIp}";
                TxtHostStatus.Foreground = new SolidColorBrush(Color.FromRgb(0x81, 0xC7, 0x84));
            }
            else
            {
                HostStatusDot.Fill = new SolidColorBrush(Color.FromRgb(0x7C, 0xB3, 0x42));
                TxtHostStatus.Text = "Esperando cliente celular en la red Wi-Fi...";
                TxtHostStatus.Foreground = new SolidColorBrush(Color.FromRgb(0xE2, 0xE8, 0xF0));
            }
        }

        private void TitleBar_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.LeftButton == MouseButtonState.Pressed)
            {
                DragMove();
            }
        }

        private void BtnMinimize_Click(object sender, RoutedEventArgs e)
        {
            WindowState = WindowState.Minimized;
        }

        private void BtnClose_Click(object sender, RoutedEventArgs e)
        {
            Close();
        }

        private bool _isFullscreen;

        private void Tab_Checked(object sender, RoutedEventArgs e)
        {
            if (PanelHost == null || PanelClient == null || PanelViewer == null) return;

            // Al salir de la pestaña visor se apaga el modo pantalla completa.
            if (_isFullscreen && TabViewerRadio.IsChecked != true) SetFullscreen(false);

            PanelHost.Visibility = Visibility.Collapsed;
            PanelClient.Visibility = Visibility.Collapsed;
            PanelViewer.Visibility = Visibility.Collapsed;

            if (TabHostRadio.IsChecked == true) PanelHost.Visibility = Visibility.Visible;
            else if (TabClientRadio.IsChecked == true) PanelClient.Visibility = Visibility.Visible;
            else if (TabViewerRadio.IsChecked == true) PanelViewer.Visibility = Visibility.Visible;
        }

        // Pantalla completa del visor: oculta barra de titulo y tabs, maximiza
        // y deja la imagen del celular a todo el monitor. ESC o F11 lo apagan.
        private void SetFullscreen(bool enable)
        {
            if (_isFullscreen == enable) return;
            if (RootBorder == null || TitleBarGrid == null || TabStripBorder == null
                || ContentGrid == null || PanelViewer == null || HintFullscreen == null) return;
            _isFullscreen = enable;

            TitleBarGrid.Visibility = enable ? Visibility.Collapsed : Visibility.Visible;
            TabStripBorder.Visibility = enable ? Visibility.Collapsed : Visibility.Visible;
            ContentGrid.Margin = enable ? new Thickness(0) : new Thickness(16, 0, 16, 16);
            PanelViewer.CornerRadius = enable ? new CornerRadius(0) : new CornerRadius(14);
            PanelViewer.BorderThickness = enable ? new Thickness(0) : new Thickness(1);
            RootBorder.CornerRadius = enable ? new CornerRadius(0) : new CornerRadius(16);
            HintFullscreen.Visibility = enable ? Visibility.Visible : Visibility.Collapsed;
            WindowState = enable ? WindowState.Maximized : WindowState.Normal;

            if (enable) Keyboard.Focus(PhoneScreenImage);
        }

        private void BtnFullscreen_Click(object sender, RoutedEventArgs e) => SetFullscreen(!_isFullscreen);

        private async void BtnCopyIp_Click(object sender, RoutedEventArgs e)
        {
            Clipboard.SetText(TxtLocalIp.Text);
            BtnCopyIp.Content = "✓ ¡Copiado!";
            await Task.Delay(1800);
            BtnCopyIp.Content = "Copiar IP";
        }

        private async void BtnCopyPin_Click(object sender, RoutedEventArgs e)
        {
            Clipboard.SetText(TxtHostPin.Text);
            BtnCopyPin.Content = "✓ ¡Copiado!";
            await Task.Delay(1800);
            BtnCopyPin.Content = "Copiar PIN";
        }

        private async void BtnConnectPhone_Click(object sender, RoutedEventArgs e)
        {
            string ip = InputPhoneIp.Text.Trim();
            string portStr = InputPhonePort.Text.Trim();
            string pin = InputPhonePin.Text.Trim();

            if (string.IsNullOrEmpty(ip) || string.IsNullOrEmpty(pin))
            {
                TxtClientStatus.Text = "Completa la IP y el PIN mostrados en tu celular.";
                TxtClientStatus.Foreground = new SolidColorBrush(Color.FromRgb(0xEF, 0x44, 0x44));
                return;
            }

            if (!int.TryParse(portStr, out int port)) port = 47474;

            // Mostrar IP acortada para mantener limpio el UI
            string ipDisplay = ip;
            var ipParts = ip.Split('.');
            if (ipParts.Length == 4) ipDisplay = $"{ipParts[0]}.{ipParts[1]}.{ipParts[2]}.xxx";
            TxtClientStatus.Text = $"Conectando a {ipDisplay}:{port}...";
            TxtClientStatus.Foreground = new SolidColorBrush(Color.FromRgb(0x9C, 0xCC, 0x65));
            BtnConnectPhone.IsEnabled = false;

            try
            {
                await _phoneClient.ConnectAsync(ip, port, pin);

                TxtClientStatus.Text = "¡Conectado exitosamente!";
                TxtClientStatus.Foreground = new SolidColorBrush(Color.FromRgb(0x4C, 0xAF, 0x50));
                string ipViewer = ip;
                var ipPartsV = ip.Split('.');
                if (ipPartsV.Length == 4) ipViewer = $"{ipPartsV[0]}.{ipPartsV[1]}.{ipPartsV[2]}.xxx";
                TxtViewerStatus.Text = $"Celular Conectado ({ipViewer})";

                TabViewerRadio.IsChecked = true;
            }
            catch (Exception ex)
            {
                TxtClientStatus.Text = $"Error al conectar: {ex.Message}";
                TxtClientStatus.Foreground = new SolidColorBrush(Color.FromRgb(0xEF, 0x44, 0x44));
            }
            finally
            {
                BtnConnectPhone.IsEnabled = true;
            }
        }

        private void BtnDisconnectPhone_Click(object sender, RoutedEventArgs e)
        {
            _phoneClient.Disconnect();
            TabClientRadio.IsChecked = true;
            TxtClientStatus.Text = "Desconectado del celular.";
            TxtClientStatus.Foreground = new SolidColorBrush(Color.FromRgb(0x94, 0xA3, 0xB8));
        }

        private void HandlePhoneDisconnected(string reason)
        {
            PhoneScreenImage.Source = null;
            TxtViewerPlaceholder.Visibility = Visibility.Visible;
            TxtClientStatus.Text = $"Conexión finalizada: {reason}";
            TxtClientStatus.Foreground = new SolidColorBrush(Color.FromRgb(0x94, 0xA3, 0xB8));
            TabClientRadio.IsChecked = true;
        }

        private void UpdatePhoneFrame(byte[] jpeg, ushort width, ushort height)
        {
            try
            {
                using var ms = new MemoryStream(jpeg);
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.StreamSource = ms;
                bmp.EndInit();
                bmp.Freeze();

                PhoneScreenImage.Source = bmp;
                TxtViewerPlaceholder.Visibility = Visibility.Collapsed;
            }
            catch { }
        }

        private (ushort x, ushort y) GetNormalizedCoords(System.Windows.Input.MouseEventArgs e)
        {
            var img = PhoneScreenImage;
            if (img.Source == null || img.ActualWidth <= 0 || img.ActualHeight <= 0)
            {
                return (0, 0);
            }

            // La imagen usa Stretch="Uniform": su area renderizada puede no llenar
            // todo el control. Computamos ese rectangulo real para mapear bien.
            var src = (System.Windows.Media.Imaging.BitmapSource)img.Source;
            double srcAspect = (double)src.PixelWidth / src.PixelHeight;
            double ctrlAspect = img.ActualWidth / img.ActualHeight;

            double renderW, renderH, offX, offY;
            if (ctrlAspect > srcAspect)
            {
                // el control es mas ancho: la imagen se centra con bandas a los lados
                renderH = img.ActualHeight;
                renderW = renderH * srcAspect;
                offX = (img.ActualWidth - renderW) / 2.0;
                offY = 0;
            }
            else
            {
                // el control es mas alto: bandas arriba/abajo
                renderW = img.ActualWidth;
                renderH = renderW / srcAspect;
                offX = 0;
                offY = (img.ActualHeight - renderH) / 2.0;
            }

            var pos = e.GetPosition(img);
            double nx = Math.Clamp((pos.X - offX) / renderW, 0.0, 1.0);
            double ny = Math.Clamp((pos.Y - offY) / renderH, 0.0, 1.0);

            return ((ushort)(nx * 65535.0), (ushort)(ny * 65535.0));
        }

        private void PhoneScreen_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left)
            {
                PhoneScreenImage.CaptureMouse();
                var (x, y) = GetNormalizedCoords(e);
                _phoneClient.SendInput(1, 0, x, y);   // BUTTON_DOWN (izq)
            }
            else if (e.ChangedButton == MouseButton.Right)
            {
                var (x, y) = GetNormalizedCoords(e);
                _phoneClient.SendInput(1, 1, x, y);   // BUTTON_DOWN (der) -> long-press
            }
        }

        private void PhoneScreen_MouseMove(object sender, System.Windows.Input.MouseEventArgs e)
        {
            if ((e.LeftButton == MouseButtonState.Pressed || e.RightButton == MouseButtonState.Pressed)
                && PhoneScreenImage.IsMouseCaptured)
            {
                var (x, y) = GetNormalizedCoords(e);
                _phoneClient.SendInput(0, 0, x, y);
            }
        }

        private void PhoneScreen_MouseUp(object sender, MouseButtonEventArgs e)
        {
            bool wasLeft = e.ChangedButton == MouseButton.Left && PhoneScreenImage.IsMouseCaptured;
            bool wasRight = e.ChangedButton == MouseButton.Right && PhoneScreenImage.IsMouseCaptured;
            if (wasLeft || wasRight)
            {
                PhoneScreenImage.ReleaseMouseCapture();
                var (x, y) = GetNormalizedCoords(e);
                // button 1 (der) avisa al celular que NO dispare click/swipe extra
                // (el long-press ya se disparo al bajar).
                byte button = wasRight ? (byte)1 : (byte)0;
                _phoneClient.SendInput(2, button, x, y);   // BUTTON_UP
            }
        }

        private void PhoneScreen_MouseWheel(object sender, MouseWheelEventArgs e)
        {
            var (x, y) = GetNormalizedCoords(e);
            _phoneClient.SendInput(3, 0, x, y, (short)e.Delta);  // WHEEL en la posicion
        }

        // Teclas del visor: navegacion/edicion que no generan TextInput.
        // Scancodes Set 1 (Keymap.kt del celular).
        private void PhoneScreen_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
        {
            if (!_phoneClient.IsConnected) return;

            ushort scan = e.Key switch
            {
                Key.Escape => 0x01,      // ESC -> Back
                Key.Enter => 0x1C,       // Enter
                Key.Back => 0x0E,        // Backspace
                Key.Tab => 0x0F,         // Tab
                Key.Delete => 0x53,      // Supr (extended)
                Key.Left => 0x4B,        // Flecha izq (extended)
                Key.Right => 0x4D,       // Flecha der (extended)
                Key.Up => 0x48,          // Flecha arr (extended)
                Key.Down => 0x50,        // Flecha abajo (extended)
                Key.Home => 0x47,        // Inicio (extended)
                Key.End => 0x4F,         // Fin (extended)
                Key.PageUp => 0x49,      // Av Pag (extended)
                Key.PageDown => 0x51,    // Re Pag (extended)
                _ => (ushort)0
            };

            if (scan != 0)
            {
                _phoneClient.SendKey(scan);
                e.Handled = true;
            }
        }

        // Teclas de navegacion/edicion -> scancodes del celular (Keymap.kt, Set 1).
        private static readonly Dictionary<Key, ushort> NavScanCodes = new()
        {
            [Key.Escape] = 0x01, [Key.Enter] = 0x1C, [Key.Back] = 0x0E,
            [Key.Tab] = 0x0F, [Key.Space] = 0x39, [Key.Delete] = 0x53,
            [Key.Left] = 0x4B, [Key.Right] = 0x4D, [Key.Up] = 0x48, [Key.Down] = 0x50,
            [Key.Home] = 0x47, [Key.End] = 0x4F, [Key.PageUp] = 0x49, [Key.PageDown] = 0x51,
        };

        private bool ViewerFocused =>
            TabViewerRadio.IsChecked == true && _phoneClient.IsConnected;

        private void MainWindow_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
        {
            // F11 alterna pantalla completa del visor; ESC la apaga (y se
            // traga el ESC para que NO viaje al celular).
            if (e.Key == Key.F11 && TabViewerRadio.IsChecked == true)
            {
                SetFullscreen(!_isFullscreen);
                e.Handled = true;
                return;
            }
            if (_isFullscreen && e.Key == Key.Escape)
            {
                SetFullscreen(false);
                e.Handled = true;
                return;
            }

            if (!ViewerFocused) return;

            // Shift/Ctrl/Alt no viajan solos: se ignoran aqui y el caracter final
            // (con modificador aplicado) lo entrega TextInput.
            if (e.Key is Key.LeftShift or Key.RightShift or Key.LeftCtrl or Key.RightCtrl
                or Key.LeftAlt or Key.RightAlt or Key.LWin or Key.RWin)
                return;

            // Escape y las flechas no generan TextInput: se envian aqui.
            if (NavScanCodes.TryGetValue(e.Key, out ushort scan) &&
                e.Key is Key.Escape or Key.Left or Key.Right or Key.Up or Key.Down
                    or Key.Home or Key.End or Key.PageUp or Key.PageDown)
            {
                _phoneClient.SendKey(scan);
                e.Handled = true;
            }
        }

        private void MainWindow_TextInput(object sender, TextCompositionEventArgs e)
        {
            if (!ViewerFocused) return;

            foreach (char c in e.Text)
            {
                if (c == '\r' || c == '\n') { _phoneClient.SendKey(0x1C); }
                else if (c == '\b') { _phoneClient.SendKey(0x0E); }
                else if (c == '\t') { _phoneClient.SendKey(0x0F); }
                else if (!char.IsControl(c))
                {
                    // INPUT_CHAR: el celular inserta el Unicode en el campo activo.
                    _phoneClient.SendInput(6, 0, 0, 0, 0, (ushort)c);
                }
            }
            e.Handled = true;
        }

        // Scancodes Set 1 (Keymap.kt): Esc=0x01, Win=0x5B, Tab=0x0F. Power no
        // existe como scancode, se manda como codigo legacy 26 que el celular
        // sigue reconociendo en processInput.
        private void NavBack_Click(object sender, RoutedEventArgs e) => _phoneClient.SendKey(0x01);
        private void NavHome_Click(object sender, RoutedEventArgs e) => _phoneClient.SendKey(0x5B);
        private void NavRecents_Click(object sender, RoutedEventArgs e) => _phoneClient.SendKey(0x0F);
        private void NavPower_Click(object sender, RoutedEventArgs e) => _phoneClient.SendKey(26);
    }
}
